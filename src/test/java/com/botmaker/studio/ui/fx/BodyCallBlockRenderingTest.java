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
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code blocks.flow.BodyCallBlock} renders — a call whose last argument is a {@code { … }} lambda, drawn with
 * a slot per leading argument and the body as a droppable child.
 *
 * <p>This was {@code VisionBlockRenderingTest} over {@code blocks.vision.LambdaCallBlock}, and the test it
 * replaces is worth knowing about, because most of it asserted things that were <em>defects</em>. It checked
 * that a facade dropdown offered {@code Mouse}, that a method dropdown offered exactly nine names
 * ({@code ifFind}…{@code untilFindAll}) in order, that {@code ifFind} carried a {@code → boolean} badge and
 * that a tooltip said the word {@code Matches} — every one of them the editor holding one library's vocabulary
 * on its behalf, and none of them reachable by a second plugin's method that takes a body. The block and all
 * three controls were deleted on 2026-09-01.
 *
 * <p>What survives is the property that was always the real one, and it is stated twice here on purpose —
 * once on an SDK call and once on a call to nobody in particular. The body must be a real {@link BodyBlock}
 * child: if it is not, the user's statements are invisible and the next edit can write them out (the
 * {@code UnknownExpressionBlock} lesson).
 */
class BodyCallBlockRenderingTest extends FxHeadlessTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    /** An SDK vision loop — the shape the deleted block was written for. */
    private static String visionBot() {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                        ImageTemplate button = new ImageTemplate("button.png");
                        ImageFinder.whileFind(button, m -> {
                            System.out.println("found");
                        });
                    }
                }
                """;
    }

    /** The same shape on a receiver no plugin catalogues, which the deleted block could not draw at all. */
    private static String plainBot() {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                        Anything.each("a", "b", item -> {
                            System.out.println(item);
                        });
                    }
                }
                """;
    }

    @Override
    public void start(Stage stage) {
        // Nodes are built directly; ApplicationTest only needs to have started the FX runtime.
    }

    private record Rendered(CodeBlock block, CodeEditorService context) {}

    /** Converts {@code source} and returns the {@code BodyCallBlock} in it, plus the service it renders with. */
    private static Rendered bodyCall(String source) {
        Path file = Paths.get("Subject.java").toAbsolutePath();

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
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, dnd, false, false);
        state.setCompilationUnit(result.cu());

        AbstractCodeBlock root = result.root();
        assertNotNull(root, "converter should produce a root block");

        CodeEditorService context = new CodeEditorService(
                CONFIG, state, bus, converter, dnd, null, new ProjectAnalyzer(null, state), null, null);

        for (CodeBlock b : flatten(root)) {
            if (b.getClass().getSimpleName().equals("BodyCallBlock")) return new Rendered(b, context);
        }
        throw new AssertionError("a call with a trailing body lambda produced no BodyCallBlock");
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

    private static <T> List<T> nodesOfType(Node root, Class<T> type) {
        return descendants(root).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<String> labelTexts(Node root) {
        return nodesOfType(root, Label.class).stream().map(Label::getText).filter(t -> t != null).toList();
    }

    /**
     * The block's header row — the call itself. The search is scoped to it because the <em>body</em> renders
     * its own text fields (a string literal is one).
     */
    private static Node header(Node blockUi) {
        return ((Parent) blockUi).getChildrenUnmodifiable().get(0);
    }

    // ---- It renders at all ----

    @Test
    void theCallRendersAsItsOwnReceiverAndMethod() {
        Rendered r = bodyCall(visionBot());
        Node ui = r.block().getUINode(r.context());

        assertNotNull(ui, "the block produced no UI node");
        List<String> labels = labelTexts(ui);
        assertTrue(labels.contains("(") && labels.contains(")"),
                "the argument slot must read as a call: " + labels);
        assertTrue(labels.contains("ImageFinder"), "the receiver is drawn as written: " + labels);
        assertTrue(labels.contains("whileFind"), "and so is the method: " + labels);
    }

    /**
     * Nothing here is a dropdown any more, and that is the change rather than a side effect of it. The two the
     * old block carried rewrote the call: one repointed it at another SDK facade and threw the body away, the
     * other switched it between nine vision variants and converted the leading argument with it. Neither could
     * be written without naming the SDK, and neither had a meaning for a call on anyone else's type.
     */
    @Test
    void theHeaderOffersNoDropdownThatRewritesTheCall() {
        Node header = header(bodyCall(visionBot()).block().getUINode(bodyCall(visionBot()).context()));

        assertTrue(nodesOfType(header, ComboBox.class).isEmpty(),
                "changing which method a call is on is the member menus' job, not this block's");
    }

    /** The lambda parameter is not drawn: it is the library's name, and the body's expression menu offers it. */
    @Test
    void theLambdaParameterIsNotDrawn() {
        Rendered r = bodyCall(visionBot());
        Node header = header(r.block().getUINode(r.context()));

        List<String> chips = nodesOfType(header, TextField.class).stream().map(TextField::getText).toList();
        assertTrue(chips.isEmpty(), () -> "no chip should name the lambda parameter, found " + chips);
        assertTrue(!labelTexts(header).contains("→"), "and no lambda arrow stands in for it either");
    }

    // ---- The body ----

    @Test
    void theLambdaBodyIsADroppableChildCarryingItsStatements() {
        Rendered r = bodyCall(visionBot());

        assertEquals(1, bodyOf(r).getStatements().size(),
                "the print written inside the lambda must be in the tree, not just in the file");
        assertNotNull(r.block().getUINode(r.context()), "and the whole thing still renders");
    }

    /**
     * The same call on a receiver nothing catalogues, with <b>two</b> leading arguments. Both halves are
     * things the deleted block could not do: it required the receiver to be a class {@code isLibraryClass}
     * recognised, and it parsed exactly one leading argument because the nine methods it knew all took one.
     */
    @Test
    void aCallOnAnUnknownReceiverRendersTheSameWay() {
        Rendered r = bodyCall(plainBot());
        Node ui = r.block().getUINode(r.context());

        List<String> labels = labelTexts(ui);
        assertTrue(labels.contains("Anything") && labels.contains("each"), labels.toString());
        assertEquals(2, ((BlockWithChildren) r.block()).getChildren().size() - 1,
                "both leading arguments are slots, not just the first");
        assertEquals(1, bodyOf(r).getStatements().size());
        assertNotNull(ui);
    }

    private static BodyBlock bodyOf(Rendered r) {
        BodyBlock body = null;
        for (CodeBlock child : ((BlockWithChildren) r.block()).getChildren()) {
            if (child instanceof BodyBlock b) body = b;
        }
        assertNotNull(body, "the lambda body must be a BodyBlock child, or nothing can be dropped into it");
        return body;
    }
}
