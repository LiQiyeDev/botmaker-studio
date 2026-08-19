package com.botmaker.studio.ui.fx;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which control a project variable gets, and on what the answer depends: the <em>shape</em>, and nothing else.
 *
 * <p>It used to depend on whether any choices had been declared yet. A "one of…" variable with none showed the
 * plain single-value editor and an "any of…" one showed a textarea asking for raw values one per line — so the
 * two shapes were invisible until after the author had filled the set in, which is exactly when they were
 * least needed. Worse, a closed-set type like Direction never gets a declared set at all (its values are the
 * SDK's), so "any of Direction" was permanently a textarea of names typed from memory.
 */
class ParamShapeWidgetTest extends FxHeadlessTest {

    private Node widgetFor(ActivityVariable variable) {
        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(variable, null, sink));
        return built[0];
    }

    private List<Node> childrenOf(Node node) {
        return node instanceof Pane pane ? List.copyOf(pane.getChildren()) : List.of();
    }

    @Test
    void anyOfAClosedSetTicksTheTypesOwnValuesWithNothingDeclared() {
        ActivityVariable directions = ActivityVariable.create("ways",
                BotType.Choice.listOf(BotType.DIRECTION));
        assertTrue(directions.options().isEmpty(), "nobody declares the directions; the SDK has them");

        List<Node> rows = childrenOf(widgetFor(directions));

        assertFalse(rows.isEmpty(), "a list of directions is tick boxes, not an empty textarea");
        for (Node row : rows) assertInstanceOf(CheckBox.class, row);
        assertTrue(rows.stream().anyMatch(row -> "NORTH".equals(((CheckBox) row).getUserData())));
    }

    /**
     * Every one of the SDK's directions has a square on the pad. The table listed only the screen spelling
     * ({@code UP}, {@code DOWN}) while the SDK ships the compass, so all four constants missed the grid and
     * fell into the row of named buttons kept for the odd one out — a pad that positioned nothing.
     */
    @Test
    void everyDirectionTheSdkHasHasASquareOnThePad() {
        List<String> known = com.botmaker.studio.project.activity.VariableWire
                .fixedOptions(BotType.DIRECTION);
        assertFalse(known.isEmpty(), "the SDK enum is what the pad is built from");

        Node pad = widgetFor(ActivityVariable.create("way", BotType.Choice.of(BotType.DIRECTION)));
        List<Node> parts = childrenOf(pad);

        assertEquals(1, parts.size(),
                "a second row means constants the grid had no square for and fell through to name buttons");
        assertEquals(known.size(), childrenOf(parts.getFirst()).size(),
                "one square per direction the SDK has");
    }

    @Test
    void oneOfShowsItsRadioButtonsBeforeAnyChoiceIsDeclared() {
        ActivityVariable size = ActivityVariable.create("size",
                new BotType.Choice(BotType.WHOLE_NUMBER, BotType.Shape.ONE_OF));

        // With nothing declared it says so, rather than quietly rendering the free-value spinner.
        assertEquals(1, childrenOf(widgetFor(size)).size());

        ActivityVariable declared = size.withOptions(List.of("1", "2", "3"));
        List<Node> rows = childrenOf(widgetFor(declared));

        assertEquals(3, rows.size());
        for (Node row : rows) assertInstanceOf(RadioButton.class, row);
        assertEquals("2", ((RadioButton) rows.get(1)).getUserData());
    }

    /**
     * The reader hands back the option's own wire text, never the button's label. They part company the moment
     * an option carries a graphic — a template's thumbnail, a colour swatch — and a value read off a label
     * would then be whatever the label happened to say.
     */
    @Test
    void whatIsReadBackIsTheStoredValueAndNotTheLabel() {
        ActivityVariable picked = ActivityVariable.create("mode",
                        new BotType.Choice(BotType.TEXT, BotType.Shape.ONE_OF))
                .withOptions(List.of("fast", "slow"))
                .withValue("slow");

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        interact(() -> ParamValueWidgets.build(picked, null, sink));

        assertEquals(1, sink.size());
        assertEquals(List.of("slow"), sink.getFirst().read().get());
    }
}
