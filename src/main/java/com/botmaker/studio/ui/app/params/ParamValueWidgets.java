package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableWire;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link ActivityVariable}, seeded from its current value, and hands
 * back a reader turning the widget's live state into the type's wire form.
 *
 * <p><b>Reading is total and never validates.</b> A half-typed duration, a number past its bound, a template
 * that has since been deleted: every one of them is handed on as typed and pulled into range by
 * {@link ActivityVariable#withValue}, which normalises through {@link VariableWire}. Nothing here can refuse
 * a value, so nothing here can leave the editor unable to close a dialog because of a limit somebody
 * tightened afterwards.
 *
 * <p><b>One widget per type, chosen by the type alone.</b> That is what makes retyping a variable safe to
 * handle by rebuilding the row wholesale: the dialog throws the old widget away rather than trying to
 * reinterpret what was in it, which is how a date once came back holding text typed for a number.
 *
 * <p>Shared by the Parameters dialog and the Runner window, so a type is entered the same way wherever it is
 * met.
 */
public final class ParamValueWidgets {

    /** How wide a value column gets, so a row of them reads as a column rather than as ragged text. */
    private static final double VALUE_WIDTH = 260;

    private ParamValueWidgets() {}

    /** A variable's name plus a reader turning its widget's UI state back into the wire form. */
    public record ValueEditor(String name, Supplier<List<String>> read) {}

    /**
     * The widget for {@code variable}, seeded from its current value, registering its reader in {@code sink}.
     *
     * @param config the project, needed by the one type whose picker reads from disk
     *               ({@link BotType#IMAGE_TEMPLATE})
     */
    public static Node build(ActivityVariable variable, ProjectConfig config, List<ValueEditor> sink) {
        // What a set-shaped variable offers is the author's own list and never the type's own constants: an
        // enum with no declared subset would otherwise put every one of its hundred names on screen as a
        // radio button, which is a list pretending to be a form.
        List<String> declared = variable.type().hasOptions() ? variable.options() : List.of();

        // The shape decides the widget before the type does, because the shape is the question being asked.
        // "Any of…" is tick boxes over the declared set; "one of…" is radio buttons over it. Only "one value"
        // reaches the per-type editors below.
        if (variable.type().isList()) {
            Node column = declared.isEmpty() ? freeList(variable, sink) : checkList(variable, declared, sink);
            column.setId("param-value-" + variable.name());
            return column;
        }
        if (!declared.isEmpty()) {
            // Radio buttons, not a dropdown: the choices are the editor's own and there are a handful of
            // them, so showing all of them costs one line each and saves a click to find out what they are.
            RadioRow row = new RadioRow(declared, variable.singleValue());
            row.setId("param-value-" + variable.name());
            sink.add(new ValueEditor(variable.name(), row::wire));
            return row;
        }

        // Everything else is one value of one type, which is exactly what ValueEditors answers — the same
        // editors the activity Variables screen and the block editor get, so a duration is entered the same
        // way wherever it is met.
        ValueEditors.Editor editor = ValueEditors.editorFor(variable.type().type(), variable.singleValue(),
                new ValueEditors.Context(config, variable.bounds()));
        Node widget = editor.node();
        ValueEditors.stretch(widget);
        widget.setId("param-value-" + variable.name());
        sink.add(new ValueEditor(variable.name(), () -> List.of(editor.read().get())));
        return widget;
    }

    /** The same widget, pinned to one width — what a list of variables wants, and a form does not. */
    public static Node buildFixedWidth(ActivityVariable variable, ProjectConfig config, List<ValueEditor> sink) {
        Node widget = build(variable, config, sink);
        if (widget instanceof javafx.scene.layout.Region region) region.setPrefWidth(VALUE_WIDTH);
        return widget;
    }

    /** One value as a person would read it — a list as its joined members, not as an empty cell. */
    public static String display(ActivityVariable variable) {
        if (variable.type().isList()) return String.join(", ", variable.value());
        return variable.singleValue();
    }

    /** Declared choices, ticked. */
    private static Node checkList(ActivityVariable variable, List<String> options, List<ValueEditor> sink) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(2);
        for (String option : options) {
            CheckBox box = new CheckBox(option);
            box.setSelected(variable.value().contains(option));
            boxes.add(box);
            column.getChildren().add(box);
        }
        if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(new ValueEditor(variable.name(),
                () -> boxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList()));
        return column;
    }

    /**
     * A list with no declared choices: one item per line.
     *
     * <p>A line rather than a comma, because a comma is a character an item is allowed to contain and a
     * newline is not one anybody types into a value by accident. Blank lines are dropped on read, so trailing
     * whitespace does not become an empty item.
     */
    private static Node freeList(ActivityVariable variable, List<ValueEditor> sink) {
        TextArea area = new TextArea(String.join("\n", variable.value()));
        area.setPrefRowCount(Math.max(3, Math.min(8, variable.value().size() + 1)));
        area.setPromptText("One per line");
        sink.add(new ValueEditor(variable.name(), () -> area.getText() == null ? List.of()
                : area.getText().lines().map(String::trim).filter(line -> !line.isEmpty()).toList()));
        return area;
    }

    /**
     * One radio button per declared choice, at most one of them on.
     *
     * <p>Nothing selected is a legal state and the honest one: a stored value the editor has since removed
     * from the list shows as no selection rather than as the first choice, which would be this widget
     * choosing a setting on the user's behalf.
     */
    private static final class RadioRow extends VBox {

        private final ToggleGroup group = new ToggleGroup();

        RadioRow(List<String> options, String current) {
            super(2);
            for (String option : options) {
                RadioButton button = new RadioButton(option);
                button.setToggleGroup(group);
                button.setUserData(option);
                button.setSelected(option.equals(current));
                getChildren().add(button);
            }
            if (options.isEmpty()) getChildren().add(hint("No choices declared yet."));
        }

        List<String> wire() {
            Toggle chosen = group.getSelectedToggle();
            return List.of(chosen == null ? "" : (String) chosen.getUserData());
        }
    }

    // --- small helpers ------------------------------------------------------------------------------------

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        return label;
    }
}
