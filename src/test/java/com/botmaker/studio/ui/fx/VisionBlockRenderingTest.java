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
 * <b>Studio blocks/parser MISSING 3 — {@code blocks.vision} renders.</b> 79 lines at <b>0.0%</b>.
 *
 * <p>{@code LambdaCallBlock} is the SDK-call block for the {@code ImageFinder} vision helpers — the
 * most-used block in a real bot, and the only statement block that both carries a droppable body and rewrites
 * itself from a dropdown. Nothing executed a line of it.
 *
 * <p>The two properties asserted here are the ones a refactor can break invisibly, because the block still
 * <em>appears</em>: the method dropdown must offer every variant and be set to the one in the source (a
 * dropdown showing the wrong current value rewrites the call the moment it is touched), and the lambda body
 * must render as a real {@link BodyBlock} child (if it doesn't, the user's action statements are invisible and
 * the next edit can write them out — the {@code UnknownExpressionBlock} lesson).
 */
class VisionBlockRenderingTest extends FxHeadlessTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    /** The nine forms the dropdown offers: if/while/until × single, ANY of a group, ALL of a group. */
    private static final List<String> VARIANTS = List.of(
            "ifFind", "ifFindAny", "ifFindAll",
            "whileFind", "whileFindAny", "whileFindAll",
            "untilFind", "untilFindAny", "untilFindAll");

    private static String botUsing(String method) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        ImageTemplate button = new ImageTemplate(\"button.png\");\n"
                + "        ImageFinder." + method + "(button, m -> {\n"
                + "            BotMaker.print(\"found\");\n"
                + "        });\n"
                + "    }\n"
                + "}\n";
    }

    @Override
    public void start(Stage stage) {
        // Nodes are built directly; ApplicationTest only needs to have started the FX runtime.
    }

    private record Rendered(CodeBlock block, CodeEditorService context) {}

    /** Converts {@link #botUsing} and returns the {@code LambdaCallBlock} in it, plus the service it renders with. */
    private static Rendered visionBlock(String method) {
        return visionBlock(method, botUsing(method));
    }

    /** As {@link #visionBlock(String)}, for a caller that needs a body shape {@link #botUsing} doesn't produce. */
    private static Rendered visionBlock(String method, String source) {
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
                CONFIG, state, bus, converter, dnd, null, new ProjectAnalyzer(null, state), null);

        for (CodeBlock b : flatten(root)) {
            if (b.getClass().getSimpleName().equals("LambdaCallBlock")) return new Rendered(b, context);
        }
        throw new AssertionError("ImageFinder." + method + "(img, m -> {…}) produced no LambdaCallBlock");
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

    // ---- It renders at all ----

    @Test
    void theVisionCallRendersWithItsSdkChrome() {
        Rendered r = visionBlock("whileFind");
        Node ui = r.block().getUINode(r.context());

        assertNotNull(ui, "the block produced no UI node");
        List<String> labels = labelTexts(ui);
        assertTrue(labels.contains("(") && labels.contains(")"),
                "the argument slot must read as a call: " + labels);

        // The facade is a dropdown, not a label: it was a Label, which made this block a one-way door — a call
        // that became a vision loop could never be pointed at another SDK class again.
        List<ComboBox> facades = nodesOfType(ui, ComboBox.class).stream()
                .filter(cb -> cb.getItems().contains("Mouse")).toList();
        assertEquals(1, facades.size(), "exactly one facade dropdown");
        assertEquals("ImageFinder", facades.get(0).getValue(),
                "the facade the call is actually on must be the one selected");
    }

    // ---- The dropdown ----

    @Test
    void theMethodDropdownOffersEveryVariantAndShowsTheOneInTheSource() {
        Rendered r = visionBlock("untilFindAny");
        Node ui = r.block().getUINode(r.context());

        List<ComboBox> selectors = nodesOfType(ui, ComboBox.class).stream()
                .filter(cb -> cb.getItems().contains("ifFind")).toList();
        assertEquals(1, selectors.size(), "exactly one method dropdown");

        ComboBox<?> selector = selectors.get(0);
        assertEquals(VARIANTS, List.copyOf(selector.getItems()),
                "the dropdown must offer all nine forms, in order");
        assertEquals("untilFindAny", selector.getValue(),
                "a dropdown showing the wrong current method rewrites the call as soon as it is touched");
    }

    /** The {@code if…} forms return a boolean and say so; the {@code while…}/{@code until…} forms are void. */
    @Test
    void onlyTheIfVariantsCarryAReturnBadge() {
        assertTrue(labelTexts(render("ifFind")).contains("→ boolean"), "ifFind returns a boolean");
        assertTrue(!labelTexts(render("whileFind")).contains("→ boolean"), "whileFind is void");
        assertTrue(!labelTexts(render("untilFindAll")).contains("→ boolean"), "untilFindAll is void");
    }

    private static Node render(String method) {
        Rendered r = visionBlock(method);
        return r.block().getUINode(r.context());
    }

    // ---- The lambda parameter ----

    /**
     * The lambda parameter is <b>not</b> drawn. It was briefly an editable chip ({@code m →}) on the reasoning
     * that an unnamed value was unreachable — but it is reachable (the body's expression menu offers it), the
     * name is the SDK's rather than the user's, and an editable control for it invited a rename nobody wants
     * to make. What the body receives is said in words on the method dropdown instead.
     */
    @Test
    void theLambdaParameterIsNotDrawn() {
        Node header = header(render("whileFindAny"));

        List<String> chips = nodesOfType(header, TextField.class).stream().map(TextField::getText).toList();
        assertTrue(chips.isEmpty(), () -> "no chip should name the lambda parameter, found " + chips);
        assertTrue(!labelTexts(header).contains("→"), "and no lambda arrow stands in for it either");

        String tooltip = nodesOfType(header, ComboBox.class).stream()
                .filter(cb -> cb.getItems().contains("ifFind"))
                .map(cb -> cb.getTooltip() == null ? "" : cb.getTooltip().getText())
                .findFirst().orElse("");
        assertTrue(tooltip.contains("Matches"),
                () -> "the dropdown must still say what the body is handed: " + tooltip);
    }

    /** {@code untilFind…} loops while nothing is found, so there is no value to name at all. */
    @Test
    void aRunnableBodyCarriesNoParameterChip() {
        String source = "package com.mybot;\n"
                + "public class Subject {\n"
                + "    public void run() {\n"
                + "        ImageTemplate button = new ImageTemplate(\"button.png\");\n"
                + "        ImageFinder.untilFind(button, () -> {\n"
                + "            BotMaker.print(\"waiting\");\n"
                + "        });\n"
                + "    }\n"
                + "}\n";
        Rendered r = visionBlock("untilFind", source);
        Node header = header(r.block().getUINode(r.context()));

        assertTrue(nodesOfType(header, TextField.class).isEmpty(),
                "a Runnable body has no value to name, so no chip: "
                        + nodesOfType(header, TextField.class).stream().map(TextField::getText).toList());
    }

    /**
     * The block's header row — the call itself. The search is scoped to it because the <em>body</em> renders
     * its own text fields (a string literal is one), which would otherwise be mistaken for the parameter chip.
     */
    private static Node header(Node blockUi) {
        return ((Parent) blockUi).getChildrenUnmodifiable().get(0);
    }

    // ---- The body ----

    /**
     * The lambda body is the whole reason this block exists rather than the generic SDK-call block. It must be
     * a real {@link BodyBlock} child — that is what makes it a drop target — and the statements the user wrote
     * inside it must be in the tree.
     */
    @Test
    void theLambdaBodyIsADroppableChildCarryingItsStatements() {
        Rendered r = visionBlock("whileFind");

        BodyBlock body = null;
        for (CodeBlock child : ((BlockWithChildren) r.block()).getChildren()) {
            if (child instanceof BodyBlock b) body = b;
        }
        assertNotNull(body, "the lambda body must be a BodyBlock child, or nothing can be dropped into it");
        assertEquals(1, body.getStatements().size(),
                "the print statement written inside the lambda must be in the tree, not just in the file");

        assertNotNull(r.block().getUINode(r.context()), "and the whole thing still renders");
    }
}
