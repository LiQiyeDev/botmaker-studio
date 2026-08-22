package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio ui MISSING 1 — {@code UIManager.createScene} builds a usable scene.</b> Gates <b>SU7</b>, the
 * 580-line {@code RemotePilotUi} extraction.
 *
 * <p>{@code ui.app} is 5.9% covered over 4,703 missed lines — more uncovered lines than the whole of shared
 * and session put together, in the one package a user cannot avoid: this is the code that runs on every
 * launch, before anything else does. The TestFX + Monocle harness to reach it has existed for as long as the
 * block-rendering tests have; it was simply never pointed at the shell.
 *
 * <p>The manager is built from real collaborators over a real parsed source tree — the same wiring
 * {@code BotProject.open} does, minus the Maven resolve and the jar index, which are what make that path
 * unusable from a test. What is asserted is that the scene <em>is a scene</em>: the menu bar, the toolbar's
 * buttons, the split panes and the bottom tabs all exist and are reachable from the root. SU7 moves 580
 * lines out of the 1,583-line class that produced it, and this is the statement that the window still comes
 * up afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UIManagerSceneTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.mybot;
            public class MyBot {
                public static void main(String[] args) {
                    int health = 100;
                    System.out.println("running");
                }
            }
            """;

    private UIManager uiManager;
    private Scene scene;
    private CodeExecutionService execution;

    /**
     * The scene is built here — {@code start} runs on the FX thread with the toolkit up, which is exactly
     * what {@code createScene} needs. A {@code @BeforeAll} would run before TestFX has started the toolkit.
     * The class is {@code PER_CLASS} so the (slow) build happens once for all the assertions below.
     */
    @Override
    public void start(Stage stage) {
        if (scene != null) return;
        buildTheScene(stage);
    }

    private void buildTheScene(Stage stage) {
        Path projects = Paths.get(System.getProperty("java.io.tmpdir"), "botmaker-ui-test-projects");
        ProjectConfig config = ProjectConfig.forProject("MyBot", projects);

        EditorFixture fixture = new EditorFixture(SOURCE, config.sourceRoot().resolve("MyBot.java"));
        ProjectState state = fixture.state;
        EventBus bus = new EventBus(false);
        DiagnosticsManager diagnostics = new DiagnosticsManager();
        BlockDragAndDropManager dnd = new BlockDragAndDropManager(bus);
        ProjectAnalyzer analyzer = new ProjectAnalyzer(new TypeSummaryManager(Set.of()), state);
        execution = new CodeExecutionService(diagnostics, config, state, bus);

        StudioContext ctx = new StudioContext(config, state, bus, diagnostics, dnd, analyzer,
                new LibraryService(config, state, new TypeSummaryManager(Set.of()), bus),
                new ActivityService(config, state, bus),
                new ProjectSettingsService(config, state, bus),
                null, // no SDK surface: nothing is resolved here, so the palette gate is inert by design
                fixture.context(),
                execution);
        uiManager = new UIManager(ctx, stage);
        scene = uiManager.createScene();
        // Controls build their children in a skin, which is only created once CSS has been applied and the
        // scene laid out. Without this the traversal below sees a BorderPane and nothing inside it.
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    @AfterAll
    void releaseTheExecutionService() {
        // It registers a JVM shutdown hook in its constructor; close() removes it, so a suite that builds
        // several of these does not accumulate them.
        if (execution != null) execution.close();
    }

    /** Every node under {@code root}, depth first. */
    private static List<Node> allNodes(Parent root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    /**
     * Descends the scene graph, and explicitly into the containers whose contents are <em>not</em> children:
     * a {@code SplitPane}'s items, a {@code TabPane}'s tab content and a {@code ScrollPane}'s content each
     * live behind the control's skin, so an unselected tab or an unrendered pane would otherwise be
     * invisible to a plain child walk.
     */
    private static void collect(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            visit(child, out);
        }
        switch (parent) {
            case SplitPane split -> { for (Node item : split.getItems()) visit(item, out); }
            case TabPane tabs -> { for (javafx.scene.control.Tab tab : tabs.getTabs()) visit(tab.getContent(), out); }
            case javafx.scene.control.ScrollPane scroll -> visit(scroll.getContent(), out);
            default -> { }
        }
    }

    private static void visit(Node node, List<Node> out) {
        if (node == null || out.contains(node)) return;
        out.add(node);
        if (node instanceof Parent p) collect(p, out);
    }

    private <T> List<T> nodesOfType(Class<T> type) {
        return allNodes(scene.getRoot()).stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** The visible text of every labelled control in the scene — buttons, tabs, labels. */
    private List<String> allLabels() {
        return nodesOfType(Labeled.class).stream()
                .map(Labeled::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }

    // --- The scene exists ---

    @Test
    void theSceneIsBuiltWithARootAndItsStylesheet() {
        assertNotNull(scene, "createScene returned null");
        assertNotNull(scene.getRoot());
        assertFalse(scene.getStylesheets().isEmpty(),
                "blocks.css carries every block's state styling — an unstyled scene is an unusable one");
    }

    @Test
    void theMenuBarIsInTheSceneWithItsTopLevelMenus() {
        List<MenuBar> bars = nodesOfType(MenuBar.class);
        assertEquals(1, bars.size(), "exactly one menu bar: " + bars.size());
        assertFalse(bars.getFirst().getMenus().isEmpty(), "a menu bar with no menus is a blank strip");
    }

    /**
     * The three groups {@code createScene} assembles by hand: edit controls left, project actions centred,
     * the run cluster right. Their labels are what a user reads, and the toolbar is the part of the shell
     * most likely to lose a button to a refactor without anyone noticing.
     */
    @Test
    void theToolbarCarriesTheRunClusterAndTheProjectActions() {
        List<String> labels = allLabels();

        for (String expected : List.of("Run", "Debug", "Compile", "Stop", "Launch", "Flow", "Pilot")) {
            assertTrue(labels.stream().anyMatch(l -> l.contains(expected)),
                    "the toolbar lost '" + expected + "'; it has: " + labels);
        }
    }

    @Test
    void theEditorAndThePanelsAreSplitRatherThanStacked() {
        List<SplitPane> splits = nodesOfType(SplitPane.class);
        assertFalse(splits.isEmpty(), "the shell is a split layout — the user resizes these");

        for (SplitPane split : splits) {
            assertFalse(split.getItems().isEmpty(), "an empty split pane renders as a blank panel");
        }
    }

    @Test
    void theBottomTabsExistAndAreNamed() {
        List<TabPane> tabPanes = nodesOfType(TabPane.class);
        assertFalse(tabPanes.isEmpty(), "the output/errors/VCS tabs are the bottom half of the window");

        List<String> tabNames = tabPanes.stream()
                .flatMap(p -> p.getTabs().stream())
                .map(javafx.scene.control.Tab::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        assertFalse(tabNames.isEmpty(), "unnamed tabs cannot be navigated");
    }

    /** A shell with no clickable control is a screenshot, not an application. */
    @Test
    void theSceneCarriesLiveControls() {
        List<Button> buttons = nodesOfType(Button.class);
        assertTrue(buttons.size() >= 5, "only " + buttons.size() + " buttons in the whole shell");
        assertTrue(buttons.stream().anyMatch(b -> b.getOnAction() != null),
                "no button in the shell has an action handler wired");
    }

    /**
     * Building the scene twice must not throw. {@code UIManager} is constructed once per project open, but
     * {@code createScene} is called again on a project switch, and it wires listeners and subscriptions as a
     * side effect — the failure mode SU7's extraction is most likely to introduce.
     */
    @Test
    void theSceneCanBeBuiltAgainWithoutThrowing() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Scene> second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        javafx.application.Platform.runLater(() -> {
            try {
                second.set(uiManager.createScene());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(60, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("the second createScene threw", failure.get());
        assertNotNull(second.get());
    }

    /**
     * {@code dispose()} releases the window, and the observable half of that is the theme listener: it lives in
     * {@code BlockTheme}'s <b>static</b> list, so a shell that never dropped it went on restyling a dead scene
     * graph after every later project switch. After dispose, a theme change must leave this root alone.
     *
     * <p>Runs last by name (JUnit's default method order is deterministic per class) and only reads the scene
     * afterwards, so the other assertions are unaffected either way.
     */
    @Test
    void zDisposeDropsTheThemeListenerAndIsIdempotent() throws Exception {
        BlockTheme.ThemeType original = BlockTheme.getCurrentThemeType();
        BlockTheme.ThemeType other = original == BlockTheme.ThemeType.DARK
                ? BlockTheme.ThemeType.DEFAULT : BlockTheme.ThemeType.DARK;

        onFxThread(() -> {
            uiManager.dispose();
            uiManager.dispose(); // second call must be a no-op, not a failure
        });

        List<String> before = List.copyOf(scene.getRoot().getStyleClass());
        onFxThread(() -> BlockTheme.setTheme(other));
        assertEquals(before, scene.getRoot().getStyleClass(),
                "a disposed shell is still being restyled — its theme listener outlived it");

        onFxThread(() -> BlockTheme.setTheme(original));
    }

    /** Runs {@code body} on the FX thread and rethrows whatever it threw. */
    private static void onFxThread(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the FX thread never ran the body");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
