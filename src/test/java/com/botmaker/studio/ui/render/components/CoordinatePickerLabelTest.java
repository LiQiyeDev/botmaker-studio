package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio ui MISSING 6 — {@link PointPicker} / {@link RectPicker} label + parse round-trip.</b> Gates
 * <b>SU8</b>, the {@code CoordinatePicker} extraction.
 *
 * <p>The two classes are the same class twice: same menu, same two items, same {@code currentValues} shape,
 * differing in an arity and a format string. What SU8 needs before merging them is a statement of the
 * behaviour that must not change — which is what the "both pickers" assertions below are. Each one drives
 * both classes through the same scenario, so a merged implementation either satisfies both or fails here.
 *
 * <p>The round-trip is source → block → button text: the number a user sees on the collapsed picker is read
 * back out of the {@code new Point(…)} / {@code new Rect(…)} the last pick wrote. Get it wrong and the
 * control shows one coordinate while the bot runs another.
 */
class CoordinatePickerLabelTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene under test; the FX toolkit just has to be up before a MenuButton is constructed.
    }

    /** Source with a single call whose one argument is {@code expression}. */
    private static String botWithArgument(String expression) {
        return """
                package com.example;
                public class Subject {
                    void helper(Object o) {}
                    void run() {
                        helper(%s);
                    }
                }
                """.formatted(expression);
    }

    /**
     * The {@link ExpressionBlock} for that argument. Taken off the real {@code MethodInvocationBlock} the
     * converter built — argument blocks are the invocation's own, not tree children — rather than
     * constructed by hand, because the pickers read the AST node off it and it has to be the node the parser
     * produced.
     */
    private static ExpressionBlock argumentBlock(EditorFixture fixture) {
        List<CodeBlock> all = new ArrayList<>();
        collect(fixture.root, all);
        for (CodeBlock b : all) {
            if (b instanceof MethodInvocationBlock call && !call.getArgumentBlocks().isEmpty()) {
                return call.getArgumentBlocks().getFirst();
            }
        }
        throw new AssertionError("no call with an argument in the fixture");
    }

    private static void collect(CodeBlock from, List<CodeBlock> out) {
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) collect(child, out);
        }
    }

    /** The collapsed picker's text for {@code expression}, through the real picker factory. */
    private static String labelFor(BiFunction<com.botmaker.studio.services.CodeEditorService,
            com.botmaker.studio.core.ValueSlot, Node> picker, String expression) {
        EditorFixture fixture = new EditorFixture(botWithArgument(expression));
        Node node = picker.apply(fixture.context(),
                com.botmaker.studio.core.ValueSlot.of(argumentBlock(fixture)));

        assertNotNull(node);
        assertTrue(node instanceof MenuButton, "the picker is a MenuButton: " + node.getClass());
        return ((MenuButton) node).getText();
    }

    private static String pointLabel(String expression) {
        return labelFor(PointPicker::create, expression);
    }

    private static String rectLabel(String expression) {
        return labelFor(RectPicker::create, expression);
    }

    // --- The round-trip ---

    @Test
    void aPointConstructorIsReadBackAsItsCoordinates() {
        assertEquals("10, 20", pointLabel("new Point(10, 20)"));
    }

    @Test
    void aRectConstructorIsReadBackAsItsOriginAndSize() {
        assertEquals("10, 20  640×480", rectLabel("new Rect(10, 20, 640, 480)"));
    }

    @Test
    void negativeCoordinatesSurviveTheRoundTrip() {
        assertEquals("-1920, -50", pointLabel("new Point(-1920, -50)"),
                "a left-hand or upper monitor has negative screen coordinates");
        assertEquals("-1920, 0  100×100", rectLabel("new Rect(-1920, 0, 100, 100)"));
    }

    /** A half-written constructor is what a freshly inserted block looks like before the user picks. */
    @Test
    void bothPickersDefaultAMissingArgumentToZeroRatherThanFailing() {
        assertEquals("10, 0", pointLabel("new Point(10)"));
        assertEquals("0, 0", pointLabel("new Point()"));
        assertEquals("10, 20  0×0", rectLabel("new Rect(10, 20)"));
        assertEquals("0, 0  0×0", rectLabel("new Rect()"));
    }

    /** Extra arguments are ignored rather than shifting the read — the first N positions are the contract. */
    @Test
    void bothPickersReadOnlyTheArgumentsTheirTypeHas() {
        assertEquals("1, 2", pointLabel("new Point(1, 2, 3)"));
        assertEquals("1, 2  3×4", rectLabel("new Rect(1, 2, 3, 4, 5)"));
    }

    // --- Anything that is not a constructor ---

    /**
     * A slot filled with a variable, a call or a computed expression is shown verbatim: the picker is not
     * the only way to fill one, and rewriting a user's {@code target.center()} into "0, 0" would be a lie
     * about what the bot does.
     */
    @Test
    void bothPickersShowANonConstructorExpressionVerbatim() {
        assertEquals("origin", pointLabel("origin"));
        assertEquals("bounds", rectLabel("bounds"));
    }

    /**
     * Neither picker checks the <em>type</em> being constructed — it reads whatever
     * {@code ClassInstanceCreation} it is handed, positionally. Pinned because SU8's merge must not quietly
     * "fix" it: which picker a slot gets is {@code ArgumentEditors}' decision, made from the parameter's
     * declared type, so the type check lives one layer up and adding a second one here would be dead code.
     */
    @Test
    void neitherPickerChecksTheTypeItIsReading() {
        assertEquals("1, 2", pointLabel("new Rect(1, 2, 3, 4)"),
                "the picker trusts ArgumentEditors to have matched the type; it reads positionally");
    }

    // --- Parsing ---

    /**
     * The values come through {@code NumberFieldsDialog.parseInt}, which is deliberately lenient: it is fed
     * both the user's typed text and an AST node's source, and a suffix or stray text must not throw in the
     * middle of rendering a block.
     */
    @Test
    void theSharedIntParseIsLenientRatherThanThrowing() {
        assertEquals(42, NumberFieldsDialog.parseInt("42"));
        assertEquals(42, NumberFieldsDialog.parseInt("  42  "));
        assertEquals(-7, NumberFieldsDialog.parseInt("-7"));
        assertEquals(100, NumberFieldsDialog.parseInt("100L"), "a long literal in source keeps its value");
        assertEquals(0, NumberFieldsDialog.parseInt(""));
        assertEquals(0, NumberFieldsDialog.parseInt("-"));
        assertEquals(0, NumberFieldsDialog.parseInt("abc"));
        assertEquals(0, NumberFieldsDialog.parseInt(null));
    }
}
