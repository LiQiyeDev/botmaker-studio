package com.botmaker.studio.ui.fx;

import com.botmaker.studio.TestSupport;
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

    /** A generated file, locked wholesale: run() is the generated flow walk, helper defers to the file. */
    private static final String FLOW_DRIVER = """
            package com.mybot;
            public class FlowDriver {
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
        return render(file, source, false);
    }

    /**
     * Renders {@code source} at {@code file}. {@code readerMode} is what makes a block locked now: since
     * 2026-08-29 nothing in a project is generated, so the two remaining locks are a bot opened for reading
     * (this flag) and bundled library source (the path).
     */
    private Rendered render(Path file, String source, boolean readerMode) {
        ProjectState state = new ProjectState();
        state.setReaderMode(readerMode);
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(RUNTIME_CLASSPATH);
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);

        EventBus bus = new EventBus(false);
        BlockConverter converter = new BlockConverter(CONFIG, state);
        BlockDragAndDropManager dnd = new BlockDragAndDropManager(bus);
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, dnd,
                com.botmaker.studio.project.LockResolver.forActiveFile(CONFIG, state).suppressesInteraction(),
                false);
        state.setCompilationUnit(result.cu());
        assertNotNull(result.root(), "converter should produce a root block");

        CodeEditorService context = new CodeEditorService(
                CONFIG, state, bus, converter, dnd, null,
                new com.botmaker.studio.suggestions.ProjectAnalyzer(null, state), null, null);
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

    /**
     * {@link #interactiveControls}, minus the method headers' collapse toggles — the one control a locked
     * method legitimately keeps (it aids reading and edits nothing). Used for whole-file assertions.
     */
    private static List<String> editingControls(Node node) {
        List<String> found = new ArrayList<>();
        for (Node n : descendants(node)) {
            if (n instanceof Button b && !b.getStyleClass().contains("collapse-button")) {
                found.add("Button(" + b.getText() + ")");
            } else if (n instanceof ComboBox<?> c) found.add("ComboBox(" + c.getValue() + ")");
            else if (n instanceof MenuButton m) found.add("MenuButton(" + m.getText() + ")");
            else if (n instanceof TextField t && t.isEditable()) found.add("TextField(" + t.getText() + ")");
        }
        return found;
    }

    /**
     * Every member of every file is drawn. This used to assert the opposite for {@code isEnabled()}: it was
     * written from the flow dialog's enable checkbox, so there was no version of it the author edited here and
     * it was kept out of the tree entirely. Nothing writes a member into a user's class any more, so a member
     * hidden from its owner would be a member nobody could reach.
     */
    @Test
    void everyMemberIsBuilt() {
        Rendered r = render(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);

        assertTrue(hasMethod(r.root(), "isEnabled"), "a member of the user's own class is drawn");
        assertTrue(hasMethod(r.root(), "run"), "so is the one beside it");
    }

    /** True when the block tree contains a method declaration called {@code methodName}. */
    private static boolean hasMethod(CodeBlock root, String methodName) {
        for (CodeBlock b : flatten(root)) {
            if (b instanceof com.botmaker.studio.blocks.func.MethodDeclarationBlock m
                    && m.getAstNode() instanceof org.eclipse.jdt.core.dom.MethodDeclaration decl
                    && decl.getName().getIdentifier().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theUsersActivityRunBodyKeepsItsControls() {
        // The mirror image, and the more important half: locking this is the bug that started all of it.
        Rendered r = render(CONFIG.activitiesPackageDir().resolve("Mining.java"), ACTIVITY);
        BodyBlock run = bodyOf(r.root(), "run");
        assertFalse(run.isReadOnly(), "an activity's run() body is exactly what the user came to write");

        Node[] node = new Node[1];
        interact(() -> node[0] = run.getUINode(r.context()));

        assertFalse(interactiveControls(node[0]).isEmpty(),
                "the activity body is where the user works — it must stay interactive");
    }

    @Test
    void aBodyOpenedForReadingOffersNoControlsAtAll() {
        // The reported bug: a locked body's calls kept live class/method selectors and the ⚙ overload button.
        Rendered r = render(CONFIG.flowDriverSourceFile(), FLOW_DRIVER, true);
        BodyBlock run = bodyOf(r.root(), "run");
        assertTrue(run.isReadOnly(), "a bot open for reading is locked throughout");

        Node[] node = new Node[1];
        interact(() -> node[0] = run.getUINode(r.context()));

        assertEquals(List.of(), interactiveControls(node[0]),
                "no selector, overload picker, add or delete when reading");
    }

    @Test
    void theSameFileIsFullyInteractiveWhenItIsYours() {
        // The mirror image, and the half that regressed: the file is ordinary user code unless something says
        // otherwise, and nothing says otherwise about a project you made.
        Rendered r = render(CONFIG.flowDriverSourceFile(), FLOW_DRIVER);
        BodyBlock run = bodyOf(r.root(), "run");
        assertFalse(run.isReadOnly(), "nothing in a project of your own is generated or locked");

        Node[] node = new Node[1];
        interact(() -> node[0] = run.getUINode(r.context()));

        assertFalse(interactiveControls(node[0]).isEmpty(), "your own code stays interactive");
    }

    @Test
    void aLockedCallOffersNoDropdownToChangeTheMethod() {
        // The reported bug: the method-call dropdown edited read-only code and the edit stuck.
        Rendered r = render(CONFIG.flowDriverSourceFile(), FLOW_DRIVER, true);
        BodyBlock locked = bodyOf(r.root(), "run");

        Node[] node = new Node[1];
        interact(() -> node[0] = locked.getUINode(r.context()));

        assertEquals(List.of(), controlsOfType(node[0], ComboBox.class),
                "no scope/method selector on a locked call");
        assertEquals(List.of(), controlsOfType(node[0], MenuButton.class),
                "no signature/overload picker on a locked call");
    }

    @Test
    void aReadOnlyFieldRendersWithoutCrashingAndOffersNoControls() {
        // Regression: a read-only field's delete button is null (createDeleteButton returns null when locked),
        // and DeclareClassVariableBlock styled it unconditionally — NPE aborted the whole render pass, so a
        // generated file with a field (e.g. ActivityRegistry) showed no blocks at all. The "Set Value" button
        // was also unguarded; both must be absent on a locked field.
        String withField = """
                package com.mybot;
                public class FlowDriver {
                    private int ticks;
                    public static void run() {
                        BotMaker.print("mine");
                    }
                }
                """;
        Rendered r = render(CONFIG.flowDriverSourceFile(), withField, true);

        AbstractCodeBlock field = null;
        for (CodeBlock b : flatten(r.root())) {
            if (b instanceof com.botmaker.studio.blocks.var.DeclareClassVariableBlock d) { field = d; break; }
        }
        assertNotNull(field, "precondition: the field parsed to a DeclareClassVariableBlock");
        assertTrue(field.isReadOnly(), "precondition: a field in a bot open for reading is read-only");

        AbstractCodeBlock finalField = field;
        Node[] node = new Node[1];
        assertDoesNotThrow(() -> interact(() -> node[0] = finalField.getUINode(r.context())),
                "rendering a read-only field must not crash");
        assertNotNull(node[0]);
        assertEquals(List.of(), interactiveControls(node[0]),
                "a locked field offers no delete / Set Value control");
    }

    // `theRealGeneratedFlowDriverRendersInertEndToEnd` stood here: it fetched the exact source the SDK's
    // generator wrote for FlowDriver.java and asserted the whole file rendered with no editable control in
    // it. Deleted on 2026-08-29 with its subject — there is no fully generated file left to render. The
    // three files a game bot is created with are seeds the user owns and edits, and the flow they used to
    // read from FlowDriver is read from activities.json at run time.
    //
    // What it covered is not lost: the synthetic whole-file cases below hold the same rules, and it is the
    // rules rather than one particular generated file that the render pass has to honour. Reinstating it
    // against a seed would assert the opposite of the truth — a seed is meant to be editable.

    @Test
    void aLockedPrivateConstructorAndANullLiteralOfferNothing() {
        // Both leaks the whole-file pass above caught the moment it started rendering FlowDriver instead of the
        // retired GameLoop. ConstructorBlock built its own header and never asked whether the file was locked,
        // so every generated utility class (FlowDriver, ActivityRegistry) shipped a live delete and
        // add-parameter button on its private constructor. And a null literal rendered as the red
        // "Select Expression..." slot — right for an unfilled argument, wrong for `String node = null;`, which
        // is the flow's own "nowhere to go" and not a hole to fill.
        String source = """
                package com.mybot;
                public final class FlowDriver {
                    public static String step() {
                        String node = null;
                        return node;
                    }
                    private FlowDriver() {}
                }
                """;
        Rendered r = render(CONFIG.flowDriverSourceFile(), source, true);
        Node[] node = new Node[1];
        interact(() -> node[0] = r.root().getUINode(r.context()));

        assertEquals(List.of(), editingControls(node[0]),
                "a locked constructor and a locked null must both render inert");
    }

    @Test
    void everyLockedBlockShapeRendersInertWithoutCrashing() {
        // One locked method exercising the block shapes that have each crashed or leaked at least once:
        // if-without-else (add-else button), else (its delete), switch cases (case delete / move / add-case),
        // math + comparison operators (selectors), a foreach (name field), a List.of (element controls).
        String source = """
                package com.mybot;
                import java.util.List;
                public class FlowDriver {
                    public static void run() {}
                    public static void helper(int n) {
                        int x = n + 1;
                        if (x > 2) {
                            x = 3;
                        }
                        if (n < 0) {
                            x = 4;
                        } else {
                            x = 5;
                        }
                        switch (n) {
                            case 1:
                                x = 6;
                                break;
                            default:
                                break;
                        }
                        List<String> items = List.of("a", "b");
                        for (String s : items) {
                            System.out.println(s);
                        }
                    }
                }
                """;
        Rendered r = render(CONFIG.flowDriverSourceFile(), source, true);
        BodyBlock helper = bodyOf(r.root(), "helper");
        assertTrue(helper.isReadOnly(), "precondition: everything in the file is locked");

        Node[] node = new Node[1];
        assertDoesNotThrow(() -> interact(() -> node[0] = helper.getUINode(r.context())));
        assertEquals(List.of(), interactiveControls(node[0]),
                "no block shape may keep a control when locked");
    }

    @Test
    void aLockedEmptyBodyDoesNotInviteAClick() {
        String emptyRun = """
                package com.mybot;
                public class FlowDriver {
                    public static void helper() {
                    }
                }
                """;
        Rendered r = render(CONFIG.flowDriverSourceFile(), emptyRun, true);
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
