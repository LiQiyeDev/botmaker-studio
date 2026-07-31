package com.botmaker.studio.services;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 1 — {@link ProjectState} under concurrent access.</b> Gates <b>SV5</b>
 * (<b>B10</b>). Without this the fix is unverifiable by construction: the whole claim is about what happens
 * when two threads touch the object, and no test had ever started a second one.
 *
 * <p>{@code ProjectState} is mutated only from the FX thread but read from at least three others —
 * {@code DebuggingService}'s debug thread, {@code CodeExecutionService}'s {@code CodeRunner}, and
 * {@code BotProject}'s warm-up. It is a plain POJO: no field {@code volatile}, no method {@code synchronized},
 * no concurrent collection. The two failures below are the two B10 names, in the order of how bad they are.
 *
 * <p>The green tests first, because they are what the fix must <em>not</em> break: the accessors already hand
 * out unmodifiable views and defensive copies, so a background reader cannot corrupt the state — it can only
 * be corrupted <em>by</em> it.
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
     * consistent one. This is the shape B10's fix generalises — it is already right here, and only here.
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

    // ---- B10, failure 1: the CME ----

    /**
     * {@code DebuggingService:155} iterates {@code state.getNodeToBlockMap().values()} on its own thread while
     * the FX thread re-parses after every edit and calls {@code clearNodeToBlockMap()} — an <em>in-place</em>
     * structural modification of the very map being walked. Start a debug session, type one character, and
     * the session dies on a stack trace written to a stream nobody attaches to, leaving the UI on
     * "Debugging…".
     *
     * <p>Reproduced here by doing exactly that, in a loop, so the interleaving is hit rather than hoped for.
     */
    @Test
    @Disabled("B10 is unfixed: verified red on this commit — iterating getNodeToBlockMap() while the FX "
            + "thread calls clearNodeToBlockMap() throws ConcurrentModificationException, because the getter "
            + "hands out a live view of the same HashMap. Delete this line in Phase 4 with SV5's fix.")
    void walkingTheBlockMapWhileTheEditorRebuildsItDoesNotThrow() throws Exception {
        ProjectState state = stateWithAFile();
        List<ASTNode> keys = nodes(400);
        state.setNodeToBlockMap(mapOver(keys));

        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch reading = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            reading.countDown();
            try {
                while (!stop.get()) {
                    // DebuggingService's walk, verbatim in shape.
                    for (CodeBlock ignored : state.getNodeToBlockMap().values()) { /* map, don't mutate */ }
                }
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        }, "fake-debug-thread");
        reader.setDaemon(true);
        reader.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS), "reader never started");

        // The FX thread's side: re-parse → rebuild the registry, over and over.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && readerFailure.get() == null) {
            state.clearNodeToBlockMap();
            for (ASTNode key : keys) state.getMutableNodeToBlockMap().put(key, null);
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(readerFailure.get(),
                "a background reader must survive an edit landing: " + readerFailure.get());
    }

    // ---- B10, failure 2: the torn read ----

    /**
     * The worse of the two, because it does not announce itself. There is no barrier on any of these fields
     * and no way to read several of them as one value, so a background reader can pick up the code from one
     * revision and the classpath from another — and go on to compile the first against the second, or attach
     * breakpoints to lines that moved.
     *
     * <p>The writer here does what an edit does: publish a new revision as a <em>pair</em> of setter calls.
     * The reader asks for both and checks they describe the same revision. B10's fix is a snapshot taken on
     * the FX thread, which makes that question answerable; today it is not.
     */
    @Test
    @Disabled("B10 is unfixed: verified red on this commit — a reader observes the code from one revision "
            + "and the classpath from the next, because ProjectState offers no way to read them as one "
            + "value. Delete this line in Phase 4 with SV5's fix.")
    void aBackgroundReaderNeverSeesTwoDifferentRevisionsAtOnce() throws Exception {
        ProjectState state = stateWithAFile();
        state.setCurrentCode("rev-A");
        state.setResolvedClasspath(List.of("rev-A.jar"));

        AtomicReference<String> torn = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch reading = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            reading.countDown();
            while (!stop.get() && torn.get() == null) {
                String code = state.getCurrentCode();
                List<String> classpath = state.getResolvedClasspath();
                String fromClasspath = classpath.isEmpty() ? "" : classpath.get(0).replace(".jar", "");
                if (!code.equals(fromClasspath)) {
                    torn.set("code=" + code + " but classpath=" + fromClasspath);
                }
            }
        }, "fake-runner-thread");
        reader.setDaemon(true);
        reader.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS), "reader never started");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean a = false;
        while (System.nanoTime() < deadline && torn.get() == null) {
            String rev = (a = !a) ? "rev-A" : "rev-B";
            state.setCurrentCode(rev);
            state.setResolvedClasspath(List.of(rev + ".jar"));
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertNull(torn.get(), "a reader saw a half-applied edit: " + torn.get());
    }
}
