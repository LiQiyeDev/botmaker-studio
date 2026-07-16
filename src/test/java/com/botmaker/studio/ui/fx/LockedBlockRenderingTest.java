package com.botmaker.studio.ui.fx;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A read-only block renders with <b>no way to interact with it</b> — not a disabled control, not a greyed one:
 * none at all.
 *
 * <p>Read-only used to be a 0.8 opacity and a promise, while the buttons, dropdowns and text fields underneath
 * stayed live and wired. The write layer now refuses those edits ({@code CodeEditorLockTest}), but a control
 * that visibly does nothing when clicked is its own bug — so this asserts the affordances are absent rather
 * than merely inert.
 *
 * <p>Assertions are on node <em>types</em> in the subtree rather than named lookups, so a block added later
 * that forgets the rule fails here without anyone remembering to extend the test.
 */
class LockedBlockRenderingTest extends FxHeadlessTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    /** A generated file: locked class, locked helper, but run()'s body is the user's. */
    private static final String GAME_LOOP = """
            package com.mybot;
            public class GameLoop {
                public static void run() {
                    BotMaker.print("mine");
                }
                public static void helper() {
                    BotMaker.print("scaffolding");
                }
            }
            """;

    /** An activity stub: isEnabled() is generated wiring, run() is the user's. */
    private static final String ACTIVITY = """
            package com.mybot.activities;
            public class Mining extends Activity {
                @Override public boolean isEnabled() { return Activities.Mining; }
                @Override public void run() {
                    int x = 1;
                }
            }
            """;

    @Override
    public void start(Stage stage) {
        // Nodes are built directly; ApplicationTest only needs to have started the FX runtime.
    }

    private record Rendered(AbstractCodeBlock root, CodeEditorService context, ProjectState state) {}

    private Rendered render(Path file, String source) {
        ProjectState state = new ProjectState();
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(RUNTIME_CLASSPATH);
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);

        EventBus bus = new EventBus(false);
        BlockConverter converter = new BlockConverter(CONFIG, state);
        BlockDragAndDropManager dnd = new BlockDragAndDropManager(bus);
        BlockConverter.ConvertResult result = converter.convert(
                source, state.getMutableNodeToBlockMap(), dnd,
                com.botmaker.studio.project.LockResolver.forActiveFile(CONFIG, state).suppressesInteraction(),
                false);
        state.setCompilationUnit(result.cu());
        assertNotNull(result.root(), "converter should produce a root block");

        CodeEditorService context = new CodeEditorService(
                CONFIG, state, bus, converter, dnd, null,
                new com.botmaker.studio.suggestions.ProjectAnalyzer(null, state), null);
        return new Rendered(result.root(), context, state);
    }

    /** The body block for {@code methodName}, found by walking the block tree. */
    private static BodyBlock bodyOf(CodeBlock root, String methodName) {
        for (CodeBlock b : flatten(root)) {
            if (b instanceof com.botmaker.studio.blocks.func.MethodDeclarationBlock m
                    && m.getAstNode() instanceof org.eclipse.jdt.core.dom.MethodDeclaration decl
                    && decl.getName().getIdentifier().equals(methodName)) {
                for (CodeBlock child : ((BlockWithChildren) m).getChildren()) {
                    if (child instanceof BodyBlock body) return body;
                }
            }
        }
        throw new AssertionError("no body for " + methodName);
    }

    private static List<CodeBlock> flatten(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren p) {
            for (CodeBlock c : p.getChildren()) out.addAll(flatten(c));
        }
        return out;
    }

    private static List<Node> descendants(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) out.addAll(descendants(child));
        }
        return out;
    }

    private static <T> List<T> controlsOfType(Node node, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Node n : descendants(node)) {
            if (type.isInstance(n)) out.add(type.cast(n));
        }
        return out;
    }

    /** Every control a user could act on. An editable TextField counts; a Label never does. */
    private static List<String> interactiveControls(Node node) {
        List<String> found = new ArrayList<>();
        for (Node n : descendants(node)) {
            if (n instanceof Button b) found.add("Button(" + b.getText() + ")");
            else if (n instanceof ComboBox<?> c) found.add("ComboBox(" + c.getValue() + ")");
            else if (n instanceof MenuButton m) found.add("MenuButton(" + m.getText() + ")");
            else if (n instanceof TextField t && t.isEditable()) found.add("TextField(" + t.getText() + ")");
        }
        return found;
    }

    @Test
    void aGeneratedWiringMethodOffersNoControlsAtAll() {
        Rendered r = render(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);
        BodyBlock isEnabled = bodyOf(r.root(), "isEnabled");
        assertTrue(isEnabled.isReadOnly(), "precondition: isEnabled is MethodLock.FULL");

        Node[] node = new Node[1];
        interact(() -> node[0] = isEnabled.getUINode(r.context()));

        assertEquals(List.of(), interactiveControls(node[0]),
                "a fully generated method must offer nothing to click");
    }

    @Test
    void theUsersRunBodyKeepsItsControls() {
        // The mirror image, and the more important half: locking this is the bug that started all of it.
        Rendered r = render(CONFIG.mainSourceFile().getParent().resolve("GameLoop.java"), GAME_LOOP);
        BodyBlock run = bodyOf(r.root(), "run");
        assertFalse(run.isReadOnly(), "GameLoop.run's body is the user's, generated file or not");

        Node[] node = new Node[1];
        interact(() -> node[0] = run.getUINode(r.context()));

        assertFalse(interactiveControls(node[0]).isEmpty(),
                "the game loop body is where the user works — it must stay interactive");
    }

    @Test
    void aGeneratedFilesOtherMethodsAreInert() {
        Rendered r = render(CONFIG.mainSourceFile().getParent().resolve("GameLoop.java"), GAME_LOOP);
        BodyBlock helper = bodyOf(r.root(), "helper");
        assertTrue(helper.isReadOnly(), "MethodLock.NONE defers to the file, and the file is generated");

        Node[] node = new Node[1];
        interact(() -> node[0] = helper.getUINode(r.context()));

        assertEquals(List.of(), interactiveControls(node[0]));
    }

    @Test
    void aLockedCallOffersNoDropdownToChangeTheMethod() {
        // The reported bug: the method-call dropdown edited read-only code and the edit stuck.
        Rendered r = render(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);
        BodyBlock isEnabled = bodyOf(r.root(), "isEnabled");

        Node[] node = new Node[1];
        interact(() -> node[0] = isEnabled.getUINode(r.context()));

        assertEquals(List.of(), controlsOfType(node[0], ComboBox.class),
                "no scope/method selector on a locked call");
        assertEquals(List.of(), controlsOfType(node[0], MenuButton.class),
                "no signature/overload picker on a locked call");
    }

    @Test
    void aLockedEmptyBodyDoesNotInviteAClick() {
        String emptyRun = """
                package com.mybot;
                public class GameLoop {
                    public static void helper() {
                    }
                }
                """;
        Rendered r = render(CONFIG.mainSourceFile().getParent().resolve("GameLoop.java"), emptyRun);
        BodyBlock helper = bodyOf(r.root(), "helper");
        assertTrue(helper.isReadOnly());

        Node[] node = new Node[1];
        interact(() -> node[0] = helper.getUINode(r.context()));

        for (Node n : descendants(node[0])) {
            if (n instanceof javafx.scene.control.Label label) {
                assertNotEquals("Click to add a block", label.getText(),
                        "a locked body must not offer an insertion it will refuse");
            }
        }
    }
}
