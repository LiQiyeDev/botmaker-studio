package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
 * {@link ActivityVariable#withValue}, which normalises through {@link ValueWire}. Nothing here can refuse
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

    /**
     * A variable's handle plus a reader turning its widget's UI state back into the wire form.
     *
     * <p>The handle is the <b>pair</b> {@code (group, name)}: a name identifies a variable only inside its
     * own plugin's section, so a reader holding just the name could write one plugin's value into another
     * plugin's variable that happens to share it.
     */
    public record ValueEditor(String group, String name, Supplier<List<String>> read) {

        static ValueEditor of(ActivityVariable variable, Supplier<List<String>> read) {
            return new ValueEditor(variable.group(), variable.name(), read);
        }

        /** True when this reader was built from {@code variable} — the pair, never identity. */
        public boolean describes(ActivityVariable variable) {
            return variable.name().equals(name) && variable.isIn(group);
        }
    }

    /**
     * The widget for {@code variable}, seeded from its current value, registering its reader in {@code sink}.
     *
     * @param config the project, needed by the one type whose picker reads from disk
     *               ({@code IMAGE_TEMPLATE})
     */
    public static Node build(ActivityVariable variable, ProjectConfig config, List<ValueEditor> sink) {
        ValueType base = variable.type().type();
        ValueEditors.Context ctx = new ValueEditors.Context(config, variable.bounds());

        // The set a set-shaped variable offers: the author's declared choices, or — for a type whose values
        // are already a closed set — the type's own constants. It used to be the author's list alone, so
        // "any of Direction" with nothing written down offered nothing to tick and fell through to a textarea
        // asking for raw names, one per line.
        List<String> declared = variable.type().hasOptions()
                ? ValueWire.effectiveOptions(base, variable.options())
                : List.of();

        // The shape decides the widget before the type does, because the shape is the question being asked —
        // and it decides it *unconditionally*. Dispatching on "are there any options yet" instead is what made
        // a freshly created "one of…" variable render as the plain single-value editor: the shape was set, the
        // choices were not yet, and the control silently answered a different question than the one asked.
        Node widget = switch (variable.type().shape()) {
            // Tick boxes unconditionally: "many of" is a set the author wrote, and a set they have not
            // written yet is an empty set, not a different question. The "are there any choices" branch that
            // used to stand here is what made one shape render as two widgets — it is now the OPEN_LIST
            // shape's own case, chosen by the user rather than inferred from data they cannot see.
            case ANY_OF -> checkList(variable, declared, base, ctx, sink);
            case OPEN_LIST -> openList(variable, base, ctx, sink);
            // Radio buttons, not a dropdown: the choices are the editor's own and there are a handful of
            // them, so showing all of them costs one line each and saves a click to find out what they are.
            case ONE_OF -> radioRow(variable, declared, base, ctx, sink);
            // One value of one type, which is exactly what ValueEditors answers — the same editors the
            // activity Variables screen and the block editor get, so a duration is entered the same way
            // wherever it is met.
            // ONE, and — the reason there is a default at all — any shape a later contract adds. ValueShape
            // is a contract enum and documented as growable, so an exhaustive switch here would throw a
            // MatchException against a newer host rather than falling back to the single-value editor.
            default -> single(variable, base, ctx, sink);
        };
        widget.setId("param-value-" + variable.name());
        return widget;
    }

    private static Node single(ActivityVariable variable, ValueType base, ValueEditors.Context ctx,
                               List<ValueEditor> sink) {
        ValueEditors.Editor editor = ValueEditors.editorFor(base, variable.singleValue(), ctx);
        Node widget = editor.node();
        ValueEditors.stretch(widget);
        sink.add(ValueEditor.of(variable, () -> List.of(editor.read().get())));
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
    private static Node checkList(ActivityVariable variable, List<String> options, ValueType base,
                                  ValueEditors.Context ctx, List<ValueEditor> sink) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(2);
        for (String option : options) {
            CheckBox box = new CheckBox(option);
            box.setUserData(option);
            box.setGraphic(ValueEditors.optionGraphic(base, option, ctx));
            box.setSelected(variable.value().contains(option));
            boxes.add(box);
            column.getChildren().add(box);
        }
        if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(variable, () -> boxes.stream()
                .filter(CheckBox::isSelected).map(box -> (String) box.getUserData()).toList()));
        return column;
    }

    /**
     * One radio button per declared choice, at most one of them on.
     *
     * <p>Nothing selected is a legal state and the honest one: a stored value the editor has since removed
     * from the list shows as no selection rather than as the first choice, which would be this widget
     * choosing a setting on the user's behalf.
     */
    private static Node radioRow(ActivityVariable variable, List<String> options, ValueType base,
                                 ValueEditors.Context ctx, List<ValueEditor> sink) {
        ToggleGroup group = new ToggleGroup();
        VBox column = new VBox(2);
        String current = variable.singleValue();
        for (String option : options) {
            RadioButton button = new RadioButton(option);
            button.setToggleGroup(group);
            button.setUserData(option);
            button.setGraphic(ValueEditors.optionGraphic(base, option, ctx));
            button.setSelected(option.equals(current));
            column.getChildren().add(button);
        }
        if (options.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
        sink.add(ValueEditor.of(variable, () -> {
            Toggle chosen = group.getSelectedToggle();
            return List.of(chosen == null ? "" : (String) chosen.getUserData());
        }));
        return column;
    }

    /**
     * {@link com.botmaker.plugin.api.value.ValueShape#OPEN_LIST}: the user writes the members themselves,
     * out of no set at all.
     *
     * <p>Text is one item per line — a newline is not a character anybody types into a value by accident,
     * where a comma is, and twenty strings are faster typed than clicked. Every other type gets a growable
     * column of that type's own editor instead: a list of durations typed as text is four numbers per line to
     * decode, and a list of templates typed as text is names remembered rather than pictures chosen.
     */
    private static Node openList(ActivityVariable variable, ValueType base, ValueEditors.Context ctx,
                                 List<ValueEditor> sink) {
        // By id, never by identity: a ValueType's identity *is* its persisted id, and two plugin
        // classloaders each holding their own copy of a class would make `==` mean nothing.
        if (ValueCatalog.TEXT_ID.equals(base.id())) {
            TextArea area = new TextArea(String.join("\n", variable.value()));
            area.setPrefRowCount(Math.max(3, Math.min(8, variable.value().size() + 1)));
            area.setPromptText("One per line");
            sink.add(ValueEditor.of(variable, () -> area.getText() == null ? List.of()
                    : area.getText().lines().map(String::trim).filter(line -> !line.isEmpty()).toList()));
            return area;
        }

        List<ValueEditors.Editor> editors = new ArrayList<>();
        VBox column = new VBox(4);
        Button add = new Button("Add");
        Runnable[] rebuild = new Runnable[1];

        // The rows are rebuilt from the editors' own current text rather than from the variable: this widget
        // outlives several adds and removes before anything is flushed back, so the variable it was built from
        // is stale from the first click.
        rebuild[0] = () -> {
            column.getChildren().clear();
            for (int i = 0; i < editors.size(); i++) {
                ValueEditors.Editor editor = editors.get(i);
                int at = i;
                Button remove = new Button("✕");
                remove.getStyleClass().add("row-icon-button");
                remove.setOnAction(e -> {
                    editors.remove(at);
                    rebuild[0].run();
                });
                HBox row = new HBox(6, editor.node(), remove);
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(editor.node(), Priority.ALWAYS);
                column.getChildren().add(row);
            }
            if (editors.isEmpty()) column.getChildren().add(hint("Nothing in this list yet."));
            column.getChildren().add(add);
        };

        for (String item : variable.value()) editors.add(ValueEditors.editorFor(base, item, ctx));
        add.setOnAction(e -> {
            editors.add(ValueEditors.editorFor(base, null, ctx));
            rebuild[0].run();
        });
        rebuild[0].run();

        sink.add(ValueEditor.of(variable, () -> editors.stream()
                .map(editor -> editor.read().get())
                .filter(value -> value != null && !value.isBlank())
                .toList()));
        return column;
    }

    // --- small helpers ------------------------------------------------------------------------------------

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        return label;
    }
}
