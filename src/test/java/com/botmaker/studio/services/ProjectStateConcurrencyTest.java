package com.botmaker.studio.services;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 1 — {@link ProjectState} under concurrent access.</b> Gates <b>SV5</b>
 * (<b>B10</b>). Without this the fix is unverifiable by construction: the whole claim is about what happens
 * when two threads touch the object, and no test had ever started a second one.
 *
 * <p><b>Phase 4 rewrote this file, and how it had to change is the interesting part of the fix.</b> Its two
 * red-by-design tests reproduced B10 through the accessors that made the failures expressible —
 * {@code getMutableNodeToBlockMap()} handed out the live registry, and the getter pair
 * {@code getCurrentCode()} / {@code getResolvedClasspath()} let a background thread read two fields across an
 * edit. Neither accessor exists now, so neither test could be un-{@code @Disabled} in place: the fix removed
 * the API the failure needed rather than making the operation safe. What follows asserts the contract that
 * replaced them — a registry published complete, and a {@link ProjectState.Snapshot} that is one revision of
 * the project.
 *
 * <p>The green tests come first, because they are what the fix must <em>not</em> break.
 */
class ProjectStateConcurrencyTest {

    private static final Path FILE = Paths.get("Subject.java").toAbsolutePath();

    private static ProjectState stateWithAFile() {
        ProjectState state = new ProjectState();
        state.addFile(new ProjectFile(FILE, "class Subject {}"));
        state.setActiveFile(FILE);
        return state;
    }

    /** {@code n} distinct AST nodes to key a block map with — real nodes, since the map is typed on them. */
    private static List<ASTNode> nodes(int n) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> (ASTNode) ast.newSimpleName("n" + i))
                .toList();
    }

    private static Map<ASTNode, CodeBlock> mapOver(List<ASTNode> keys) {
        Map<ASTNode, CodeBlock> map = new HashMap<>();
        for (ASTNode key : keys) map.put(key, null); // the values are irrelevant; the structure is the subject
        return map;
    }

    // ---- What already holds ----

    @Test
    void theBlockMapIsHandedOutAsAnUnmodifiableView() {
        ProjectState state = stateWithAFile();
        state.setNodeToBlockMap(mapOver(nodes(3)));

        Map<ASTNode, CodeBlock> handed = state.getNodeToBlockMap();
        assertThrows(UnsupportedOperationException.class, handed::clear,
                "a reader must not be able to clear the editor's registry");
    }

    @Test
    void settingTheBlockMapCopiesRatherThanAliasingTheCaller() {
        ProjectState state = stateWithAFile();
        Map<ASTNode, CodeBlock> caller = mapOver(nodes(2));
        state.setNodeToBlockMap(caller);

        caller.clear();

        assertEquals(2, state.getNodeToBlockMap().size(),
                "the caller's later mutation must not reach into the state");
    }

    /**
     * The classpath is replaced wholesale rather than mutated, so a reader holding the previous list keeps a
     * consistent one. This is the shape B10's fix generalised — it was already right here, and only here.
     */
    @Test
    void replacingTheClasspathLeavesAnEarlierReadersListIntact() {
        ProjectState state = stateWithAFile();
        state.setResolvedClasspath(List.of("a.jar", "b.jar"));

        List<String> readEarlier = state.getResolvedClasspath();
        state.setResolvedClasspath(List.of("c.jar"));

        assertEquals(List.of("a.jar", "b.jar"), readEarlier,
                "the earlier read must not mutate under the reader");
        assertEquals(List.of("c.jar"), state.getResolvedClasspath());
    }

    // ---- B10, failure 1: the registry is published complete, never filled in place ----

    /**
     * {@code DebuggingService} iterated {@code state.getNodeToBlockMap().values()} on its own thread while the
     * FX thread re-parsed after every edit and refilled that same {@code HashMap} in place. Start a debug
     * session, type one character, and the session died on a {@code ConcurrentModificationException} written
     * to a stream nobody attaches to, leaving the UI on "Debugging…".
     *
     * <p>What holds now: whatever the editor does next, the map a reader was handed is finished. It is
     * iterated here for three seconds while the editor rebuilds as fast as it can — and it must also still
     * have the contents it had when it was handed over, which is the difference between "did not throw" and
     * "was not modified".
     */
    @Test
    void theRegistryYouWereHandedIsFinishedAndStaysThatWay() throws Exception {
        ProjectState state = stateWithAFile();
        List<ASTNode> keys = nodes(400);
        state.setNodeToBlockMap(mapOver(keys));

        Map<ASTNode, CodeBlock> handedToTheDebugThread = state.getNodeToBlockMap();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch reading = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            reading.countDown();
            try {
                while (!stop.get()) {
                    // DebuggingService's walk, verbatim in shape.
                    int seen = 0;
                    for (CodeBlock ignored : handedToTheDebugThread.values()) seen++;
                    if (seen != 400) throw new AssertionError("the registry changed size mid-walk: " + seen);
                }
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        }, "fake-debug-thread");
        reader.setDaemon(true);
        reader.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS), "reader never started");

        // The FX thread's side: re-parse → rebuild the registry → publish it, over and over. This is
        // CodeEditorService.refreshUI's shape — a fresh map filled entry by entry, then one assignment.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int rebuilds = 0;
        while (System.nanoTime() < deadline && readerFailure.get() == null) {
            state.clearNodeToBlockMap();
            Map<ASTNode, CodeBlock> rebuilt = new HashMap<>();
            for (ASTNode key : keys) rebuilt.put(key, null);
            state.setNodeToBlockMap(rebuilt);
            rebuilds++;
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(readerFailure.get(),
                "a background reader must survive an edit landing: " + readerFailure.get());
        assertTrue(rebuilds > 0, "the editor side never ran");
    }

    /** Each publish is a distinct map, so holding one cannot be holding the next. */
    @Test
    void eachPublishedRegistryIsItsOwnMap() {
        ProjectState state = stateWithAFile();
        List<ASTNode> keys = nodes(2);

        state.setNodeToBlockMap(mapOver(keys));
        Map<ASTNode, CodeBlock> first = state.getNodeToBlockMap();
        state.setNodeToBlockMap(mapOver(keys));

        assertNotSame(first, state.getNodeToBlockMap());
        assertEquals(2, first.size(), "and the first is still what it was");
    }

    // ---- B10, failure 2: the snapshot is one revision ----

    /**
     * The worse of the two failures, because it did not announce itself. There is no way to read several
     * fields of a mutable object as one value, so a background reader could pick up the code from one revision
     * and the classpath from another — and go on to compile the first against the second, or attach
     * breakpoints to lines that had moved.
     *
     * <p>{@link ProjectState#snapshot()} answers it, and the guarantee is <em>where it is taken</em> as much as
     * what it copies: on the thread that also writes, so it cannot fall between the two halves of an edit —
     * which is why {@code runCode} and {@code startDebugging} snapshot before spawning their thread rather
     * than inside it. Modelled here exactly: one thread plays the FX thread, making an edit (two writes) and
     * then starting a "run"; a background thread consumes those snapshots and asserts each describes a single
     * revision. Reading the same two fields through the getters from that thread is what tore.
     */
    @Test
    void aSnapshotTakenOnTheEditorsThreadIsAlwaysOneRevision() throws Exception {
        ProjectState state = stateWithAFile();
        ConcurrentLinkedQueue<ProjectState.Snapshot> published = new ConcurrentLinkedQueue<>();
        AtomicReference<String> torn = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch reading = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            reading.countDown();
            while (!stop.get() && torn.get() == null) {
                ProjectState.Snapshot snapshot = published.poll();
                if (snapshot == null) continue;
                String fromClasspath = snapshot.resolvedClasspath().isEmpty()
                        ? "" : snapshot.resolvedClasspath().getFirst().replace(".jar", "");
                if (!snapshot.code().equals(fromClasspath)) {
                    torn.set("code=" + snapshot.code() + " but classpath=" + fromClasspath);
                }
            }
        }, "fake-runner-thread");
        reader.setDaemon(true);
        reader.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS), "reader never started");

        state.setCurrentCode("rev-A");
        state.setResolvedClasspath(List.of("rev-A.jar"));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean a = false;
        int taken = 0;
        while (System.nanoTime() < deadline && torn.get() == null) {
            String rev = (a = !a) ? "rev-A" : "rev-B";
            // One edit — the two writes that make a revision — and then a run starts.
            state.setCurrentCode(rev);
            state.setResolvedClasspath(List.of(rev + ".jar"));
            published.add(state.snapshot());
            taken++;
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(torn.get(), "a reader saw a half-applied edit: " + torn.get());
        assertTrue(taken > 0, "the editor side never ran");
    }

    /** A snapshot is detached: the edits that land while a run is in flight cannot reach into it. */
    @Test
    void aSnapshotDoesNotChangeWhenTheProjectDoes() {
        ProjectState state = stateWithAFile();
        state.setCurrentCode("before");
        state.setResolvedClasspath(List.of("before.jar"));
        state.setNodeToBlockMap(mapOver(nodes(3)));

        ProjectState.Snapshot snapshot = state.snapshot();

        state.setCurrentCode("after");
        state.setResolvedClasspath(List.of("after.jar"));
        state.setNodeToBlockMap(mapOver(nodes(7)));
        state.addFile(new ProjectFile(Paths.get("Later.java").toAbsolutePath(), "class Later {}"));

        assertEquals("before", snapshot.code());
        assertEquals(List.of("before.jar"), snapshot.resolvedClasspath());
        assertEquals(3, snapshot.nodeToBlockMap().size());
        assertEquals(1, snapshot.files().size(), "a file added later is not in an earlier snapshot");
        assertEquals("before", snapshot.files().getFirst().content());
    }

    /**
     * The file contents are copied out, not referenced. {@code ProjectFile} is mutable and the editor keeps
     * writing to it as the user types — and {@code compileAndWait} writes exactly these strings to disk, so a
     * reference here would mean a compile that saves a half-typed line the user has since changed.
     */
    @Test
    void aSnapshotCopiesFileContentsRatherThanHoldingTheMutableFile() {
        ProjectState state = stateWithAFile();
        ProjectState.Snapshot snapshot = state.snapshot();

        state.getFile(FILE).orElseThrow().setContent("class Subject { void typedSince() {} }");

        assertEquals("class Subject {}", snapshot.files().getFirst().content(),
                "the snapshot must hold the content as it was, not follow the file");
    }

    /** An empty project snapshots without throwing — a run can be requested before anything is open. */
    @Test
    void snapshottingAnEmptyStateIsHarmless() {
        ProjectState.Snapshot snapshot = new ProjectState().snapshot();

        assertEquals("", snapshot.code());
        assertNull(snapshot.compilationUnit());
        assertEquals(List.of(), snapshot.files());
        assertEquals(List.of(), snapshot.resolvedClasspath());
        assertEquals(Map.of(), snapshot.nodeToBlockMap());
        assertNull(snapshot.template());
    }

    /** Sanity: the fixture's node list is distinct, or every assertion above about sizes is vacuous. */
    @Test
    void theFixturesNodesAreDistinct() {
        List<ASTNode> keys = nodes(50);
        assertEquals(50, new ArrayList<>(mapOver(keys).keySet()).size());
    }
}
