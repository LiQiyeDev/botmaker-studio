package com.botmaker.studio.runtime;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 2 — {@link CodeExecutionService} end to end.</b> 477 lines at <b>0.0%</b>, and
 * it is the feature: everything else in Studio exists so that this can compile a bot and run it.
 *
 * <p>It was untested because it looks untestable — it writes to disk, shells out to {@code javac}, spawns a
 * second JVM, pumps two streams and marshals every line onto the FX thread. None of that needs a project
 * <em>template</em>, a real SDK or a display, which is what makes the whole loop reachable from a
 * {@code @TempDir}: a bot is a Java class with a {@code main}, and the service compiles it with the same JDK
 * running this test.
 *
 * <p>What is asserted is the contract the UI depends on and nothing about how it is implemented: the
 * program's output reaches the bus, the started/stopped pair always brackets a run, {@code isRunning} is
 * honest, and a run that cannot compile stops before spawning anything. The last one matters most — it is the
 * difference between "your code has an error" and a bot process running yesterday's classes.
 */
class CodeExecutionServiceTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene; the service only needs Platform.runLater to have somewhere to run.
    }

    /** Everything a run needs, plus the bus traffic it produced. */
    private record Harness(CodeExecutionService service, EventBus bus, ProjectState state,
                           ConcurrentLinkedQueue<String> output, CountDownLatch stopped,
                           CountDownLatch started) {}

    private static final String PRINTS_AND_EXITS = """
            package com.endtoend;
            public class EndToEnd {
                public static void main(String[] args) {
                    System.out.println("hello from the bot");
                }
            }
            """;

    private static final String DOES_NOT_COMPILE = """
            package com.endtoend;
            public class EndToEnd {
                public static void main(String[] args) {
                    this line is not Java;
                }
            }
            """;

    private static Harness harnessFor(Path projectsRoot, String source) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("EndToEnd", projectsRoot);
        Files.createDirectories(config.mainSourceFile().getParent());
        Files.createDirectories(config.resourcesRoot());

        ProjectState state = new ProjectState();
        state.addFile(new ProjectFile(config.mainSourceFile(), source));
        state.setActiveFile(config.mainSourceFile());
        state.setSourcePath(config.sourceRoot());

        EventBus bus = new EventBus();
        ConcurrentLinkedQueue<String> output = new ConcurrentLinkedQueue<>();
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        bus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class, e -> output.add(e.text()));
        bus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> stopped.countDown());
        bus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> started.countDown());

        return new Harness(
                new CodeExecutionService(new DiagnosticsManager(), config, state, bus), bus, state,
                output, stopped, started);
    }

    private static String drain(ConcurrentLinkedQueue<String> output) {
        return String.join("", output);
    }

    // ---- The loop that is the product ----

    /**
     * Edit → compile → run → the program's stdout in the console → the process is gone. Every step is
     * something a user does in one click, and none of them had ever run in a test.
     */
    @Test
    void aBotIsCompiledRunAndItsOutputReachesTheConsole(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, PRINTS_AND_EXITS);

        h.service().runCode(PRINTS_AND_EXITS);

        assertTrue(h.stopped().await(120, TimeUnit.SECONDS), "the run never finished");
        assertTrue(drain(h.output()).contains("hello from the bot"),
                "the bot's stdout must reach the bus, or the console shows nothing: " + drain(h.output()));
        assertFalse(h.service().isRunning(), "isRunning must be false once the process is gone");
        assertTrue(h.service().runningBotPid().isEmpty(), "and there must be no pid left behind");

        h.service().close();
    }

    /**
     * The edited source is written to disk as part of the run — the editor keeps every change in memory and
     * never writes as you type, so this loop is the <em>only</em> path from the block tree to a file. If it
     * regressed, a run would compile the previous save and the user's last edit would silently not happen.
     */
    @Test
    void theRunIsWhatWritesTheEditedSourceToDisk(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, PRINTS_AND_EXITS);
        ProjectConfig config = ProjectConfig.forProject("EndToEnd", root);
        assertFalse(Files.exists(config.mainSourceFile()), "nothing on disk before the run");

        assertTrue(h.service().compileAndWait(PRINTS_AND_EXITS, config.compiledOutputPath()),
                "the fixture must compile");

        assertEquals(PRINTS_AND_EXITS, Files.readString(config.mainSourceFile()),
                "the in-memory source is what reaches disk");
        assertTrue(Files.exists(config.compiledOutputPath().resolve("com/endtoend/EndToEnd.class")),
                "and it is what got compiled");

        h.service().close();
    }

    // ---- The failure the user must be told about ----

    /**
     * A bot that does not compile must not be run. The old classes are still sitting in {@code target/}, so
     * "compile failed but launch anyway" runs yesterday's bot against today's screen — the worst possible
     * outcome, because it looks like it worked.
     *
     * <p>The <em>stopped</em> event still fires — it is published from the {@code finally}, and the UI needs
     * it to re-enable the toolbar. What must not fire is <em>started</em>: that is the one that means a JVM
     * was spawned.
     */
    @Test
    void abotThatDoesNotCompileIsNeverLaunched(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, DOES_NOT_COMPILE);
        ProjectConfig config = ProjectConfig.forProject("EndToEnd", root);

        assertFalse(h.service().compileAndWait(DOES_NOT_COMPILE, config.compiledOutputPath()),
                "javac must be believed");

        h.service().runCode(DOES_NOT_COMPILE);

        assertTrue(h.stopped().await(120, TimeUnit.SECONDS), "the aborted run must still release the UI");
        assertEquals(1, h.started().getCount(), "a build failure must not spawn a bot");
        assertFalse(h.service().isRunning());
        assertTrue(h.service().runningBotPid().isEmpty());

        h.service().close();
    }

    /** The compiler's complaint is the user's only clue; it goes to the console, not to a swallowed stream. */
    @Test
    void theCompilersErrorsAreShownInTheConsole(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, DOES_NOT_COMPILE);

        h.service().runCode(DOES_NOT_COMPILE);
        assertTrue(h.stopped().await(120, TimeUnit.SECONDS), "the aborted run never finished");

        String console = drain(h.output());
        assertTrue(console.contains("EndToEnd.java") || console.contains("error"),
                "javac's output must be relayed: " + console);

        h.service().close();
    }

    // ---- Stopping ----

    @Test
    void stoppingANonRunningProgramIsHarmless(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, PRINTS_AND_EXITS);

        h.service().stopRunningProgram(); // the Stop button before anything started

        assertFalse(h.service().isRunning());
        h.service().close();
    }

    /**
     * {@code close()} is the ordinary teardown and must unregister the shutdown hook it installed. Closing
     * twice — which happens when a project is closed and then the app exits — must not throw, and the hook
     * must be gone: a leaked one keeps a reference to a dead project alive for the life of the JVM.
     */
    @Test
    void closingTwiceIsSafe(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, PRINTS_AND_EXITS);

        h.service().close();
        h.service().close();

        assertFalse(h.service().isRunning());
    }

    /** Input is only meaningful while a process is alive; before one exists it is dropped, not an NPE. */
    @Test
    void sendingInputWithNothingRunningIsDropped(@TempDir Path root) throws Exception {
        Harness h = harnessFor(root, PRINTS_AND_EXITS);

        h.service().sendInput("42");

        assertEquals(List.of(), List.copyOf(h.output()),
                "nothing is running, so nothing should be echoed to the console either");
        h.service().close();
    }
}
