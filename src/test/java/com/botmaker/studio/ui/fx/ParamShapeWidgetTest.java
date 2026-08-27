package com.botmaker.studio.ui.fx;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
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
 *
 * <p>The last of that rule was the one list shape that meant two things. "Many of…" and "List of…" are now two
 * shapes, so the widget follows the shape here too and there is no state in which a variable changes control
 * because a choice was added to it.
 */
class ParamShapeWidgetTest extends FxHeadlessTest {

    private static final ValueType TEXT = ValueWire.type("TEXT");
    private static final ValueType WHOLE_NUMBER = ValueWire.type("WHOLE_NUMBER");
    private static final ValueType POINT = ValueWire.type("POINT");
    private static final ValueType DIRECTION = ValueWire.type("DIRECTION");

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
                new ValueChoice(DIRECTION, ValueShape.ANY_OF));
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
        List<String> known = ValueWire.fixedOptions(DIRECTION);
        assertFalse(known.isEmpty(), "the SDK enum is what the pad is built from");

        Node pad = widgetFor(ActivityVariable.create("way", ValueChoice.of(DIRECTION)));
        List<Node> parts = childrenOf(pad);

        assertEquals(1, parts.size(),
                "a second row means constants the grid had no square for and fell through to name buttons");
        assertEquals(known.size(), childrenOf(parts.getFirst()).size(),
                "one square per direction the SDK has");
    }

    /**
     * The two list shapes, side by side on the same type with the same choices declared. Before the split
     * these were one shape and this test could not have been written: the "many of" reading was reachable
     * only with choices and the "open list" reading only without them, so no pair of variables differed by
     * the shape alone.
     */
    @Test
    void theTwoListShapesAreTwoWidgetsOnTheSameTypeAndTheSameChoices() {
        List<String> skills = List.of("mine", "fish", "cook");

        ActivityVariable many = ActivityVariable.create("many",
                new ValueChoice(TEXT, ValueShape.ANY_OF)).withOptions(skills);
        List<Node> ticks = childrenOf(widgetFor(many));
        assertEquals(skills.size(), ticks.size());
        for (Node row : ticks) assertInstanceOf(CheckBox.class, row);

        // A textarea and not a Pane, so it has no children to count: text is written one per line.
        ActivityVariable open = ActivityVariable.create("open",
                ValueChoice.listOf(TEXT)).withOptions(skills);
        assertInstanceOf(TextArea.class, widgetFor(open),
                "an open list is the user's to fill in, whatever the author wrote down");
        assertTrue(open.options().isEmpty(), "and the choices are not even stored on it");
    }

    /** Every other type's open list is a growable column of that type's own editor, empty to begin with. */
    @Test
    void anOpenListOfSomethingOtherThanTextIsRowsOfItsOwnEditor() {
        ActivityVariable spots = ActivityVariable.create("spots", ValueChoice.listOf(POINT));

        List<Node> parts = childrenOf(widgetFor(spots));

        assertFalse(parts.isEmpty(), "the empty state still has to say something and offer Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox),
                "nothing to tick: an open list has no set behind it");
    }

    @Test
    void oneOfShowsItsRadioButtonsBeforeAnyChoiceIsDeclared() {
        ActivityVariable size = ActivityVariable.create("size",
                new ValueChoice(WHOLE_NUMBER, ValueShape.ONE_OF));

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
                        new ValueChoice(TEXT, ValueShape.ONE_OF))
                .withOptions(List.of("fast", "slow"))
                .withValue("slow");

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        interact(() -> ParamValueWidgets.build(picked, null, sink));

        assertEquals(1, sink.size());
        assertEquals(List.of("slow"), sink.getFirst().read().get());
    }
}
