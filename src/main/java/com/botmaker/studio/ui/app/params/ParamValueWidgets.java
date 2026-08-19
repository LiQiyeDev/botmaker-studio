package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.DurationWire;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.ui.render.components.TemplateGalleryDialog;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.LocalDate;
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
        Node widget;
        Supplier<List<String>> reader;
        List<String> options = VariableWire.effectiveOptions(variable.type().type(), variable.options());
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

        switch (variable.type().type()) {
            case YES_NO -> {
                CheckBox box = new CheckBox();
                box.setSelected(Boolean.parseBoolean(variable.singleValue()));
                widget = box;
                reader = () -> List.of(Boolean.toString(box.isSelected()));
            }
            case WHOLE_NUMBER -> {
                Node field = numberField(variable, true);
                widget = field;
                reader = () -> List.of(numberText(field));
            }
            case DECIMAL_NUMBER -> {
                Node field = numberField(variable, false);
                widget = field;
                reader = () -> List.of(numberText(field));
            }
            case DURATION -> {
                DurationField field = new DurationField(variable.singleValue());
                widget = field;
                reader = () -> List.of(field.wire());
            }
            case TIME_OF_DAY -> {
                TimeField field = new TimeField(variable.singleValue());
                widget = field;
                reader = () -> List.of(field.wire());
            }
            case DATE -> {
                DatePicker picker = new DatePicker(parseDate(variable.singleValue()));
                widget = picker;
                reader = () -> List.of(picker.getValue() == null ? "" : picker.getValue().toString());
            }
            // The SDK enums keep their combo — a hundred key names is a list, not a form. Precision is not
            // among them: it is a record, so it has no constants to fill one with (see VariableWire).
            case KEY, MOUSE_BUTTON, DIRECTION -> {
                ComboBox<String> box = new ComboBox<>();
                box.getItems().setAll(options);
                // Only ever select a declared choice: a value the editor has since removed shows as blank,
                // which is the truth, rather than being put back into the list it was taken out of.
                box.setValue(box.getItems().contains(variable.singleValue()) ? variable.singleValue() : null);
                widget = box;
                reader = () -> List.of(box.getValue() == null ? "" : box.getValue());
            }
            case COLOR -> {
                ColorPicker picker = new ColorPicker(parseColor(variable.singleValue()));
                widget = picker;
                reader = () -> List.of(hex(picker.getValue()));
            }
            case IMAGE_TEMPLATE -> {
                TemplateChip chip = new TemplateChip(variable.singleValue(), config);
                widget = chip;
                reader = () -> List.of(chip.templateName());
            }
            case POINT -> {
                NumberRow row = new NumberRow(variable.singleValue(), "x", "y");
                widget = row;
                reader = () -> List.of(row.wire());
            }
            case SIZE -> {
                NumberRow row = new NumberRow(variable.singleValue(), "width", "height");
                widget = row;
                reader = () -> List.of(row.wire());
            }
            case RECT -> {
                NumberRow row = new NumberRow(variable.singleValue(), "x", "y", "width", "height");
                widget = row;
                reader = () -> List.of(row.wire());
            }
            case CHARACTER -> {
                // One character is the whole value, so the field is one character wide rather than letting
                // somebody type a word that would silently become its first letter.
                TextField field = new TextField(variable.singleValue());
                field.setPrefColumnCount(2);
                widget = field;
                reader = () -> List.of(text(field));
            }
            default -> { // TEXT
                TextField field = new TextField(variable.singleValue());
                widget = field;
                reader = () -> List.of(text(field));
            }
        }
        if (widget instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        widget.setId("param-value-" + variable.name());
        sink.add(new ValueEditor(variable.name(), reader));
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

    // --- the widgets with more than one control in them ---------------------------------------------------

    /**
     * A number as a {@link Spinner} when the variable declares a range, and as a plain field when it does not.
     * A spinner over the whole of {@code int} is a pair of arrows nobody can reach anything with; a spinner
     * over 1–10 is the control that says what the limits are without a sentence explaining them.
     */
    private static Node numberField(ActivityVariable variable, boolean whole) {
        Bounds bounds = variable.bounds();
        if (bounds.isEmpty()) return new TextField(variable.singleValue());
        Spinner<Double> spinner = new Spinner<>();
        double min = number(bounds.min(), whole ? Integer.MIN_VALUE : -Double.MAX_VALUE);
        double max = number(bounds.max(), whole ? Integer.MAX_VALUE : Double.MAX_VALUE);
        double step = number(bounds.step(), whole ? 1 : 0.1);
        double current = Math.max(min, Math.min(max, number(variable.singleValue(), min)));
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, current, step));
        spinner.setEditable(true);
        if (whole) {
            // The factory is a double one so both number types share a widget; whole numbers still have to
            // read as whole numbers, or a bounded int shows "3.0" in its own editor.
            spinner.getValueFactory().setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Double value) {
                    return value == null ? "" : Long.toString(Math.round(value));
                }

                @Override public Double fromString(String text) {
                    return number(text, current);
                }
            });
            spinner.getEditor().setText(Long.toString(Math.round(current)));
        }
        return spinner;
    }

    /** What a number widget currently says, as typed — normalisation happens downstream, not here. */
    private static String numberText(Node widget) {
        if (widget instanceof Spinner<?> spinner) return text(spinner.getEditor());
        return widget instanceof TextField field ? text(field) : "";
    }

    /**
     * A duration as an amount plus a unit, so nobody has to type milliseconds or remember the wire spelling.
     * The unit shown is the largest one the current value divides into exactly, which is what makes 90 000 ms
     * come back as "1 m 30 s"… and, when it does not divide, as plain milliseconds rather than as a rounded
     * number that would silently change the value on the next save.
     */
    private static final class DurationField extends HBox {

        private final TextField amount = new TextField();
        private final ComboBox<Unit> unit = new ComboBox<>();

        /** A unit and how many milliseconds one of it is. */
        private enum Unit {
            MILLIS("milliseconds", 1L), SECONDS("seconds", 1000L),
            MINUTES("minutes", 60_000L), HOURS("hours", 3_600_000L);

            final String label;
            final long millis;

            Unit(String label, long millis) {
                this.label = label;
                this.millis = millis;
            }
        }

        DurationField(String wire) {
            super(6);
            long millis = DurationWire.parse(wire, 0L);
            Unit best = Unit.MILLIS;
            for (Unit u : Unit.values()) {
                if (millis % u.millis == 0) best = u;
            }
            amount.setText(Long.toString(millis / best.millis));
            amount.setPrefWidth(90);
            unit.getItems().setAll(Unit.values());
            unit.setValue(best);
            unit.setButtonCell(unitCell());
            unit.setCellFactory(list -> unitCell());
            HBox.setHgrow(unit, Priority.ALWAYS);
            getChildren().addAll(amount, unit);
        }

        /** The canonical wire form of what is typed; unreadable text reads as nothing, never as a throw. */
        String wire() {
            long count;
            try {
                count = Long.parseLong(text(amount));
            } catch (NumberFormatException e) {
                return "0s";
            }
            Unit chosen = unit.getValue() == null ? Unit.MILLIS : unit.getValue();
            return DurationWire.format(Math.max(0, count) * chosen.millis);
        }

        private static javafx.scene.control.ListCell<Unit> unitCell() {
            return new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(Unit item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.label);
                }
            };
        }
    }

    /**
     * A time of day as two spinners, hour and minute, so it is picked rather than spelled.
     *
     * <p>{@code DATE} has had a {@link DatePicker} all along and this had a bare text field with an
     * {@code HH:mm} prompt — which is a format to get wrong, and the wrong one silently read as midnight.
     * Two spinners cannot be out of format; the only thing left to decide is the value.
     */
    private static final class TimeField extends HBox {

        private final Spinner<Integer> hours = new Spinner<>(0, 23, 0);
        private final Spinner<Integer> minutes = new Spinner<>(0, 59, 0);

        TimeField(String wire) {
            super(4);
            java.time.LocalTime time = parseTime(wire);
            hours.getValueFactory().setValue(time.getHour());
            minutes.getValueFactory().setValue(time.getMinute());
            for (Spinner<Integer> spinner : List.of(hours, minutes)) {
                spinner.setEditable(true);
                spinner.setPrefWidth(80);
                // Wrapping is what makes 23:59 → 00:00 one click rather than a scroll back through the day.
                ((SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory()).setWrapAround(true);
            }
            getChildren().addAll(hours, new Label(":"), minutes);
        }

        String wire() {
            return "%02d:%02d".formatted(value(hours), value(minutes));
        }

        private static int value(Spinner<Integer> spinner) {
            return spinner.getValue() == null ? 0 : spinner.getValue();
        }

        private static java.time.LocalTime parseTime(String wire) {
            if (wire == null || wire.isBlank()) return java.time.LocalTime.MIDNIGHT;
            try {
                return java.time.LocalTime.parse(wire.trim());
            } catch (RuntimeException e) {
                return java.time.LocalTime.MIDNIGHT;
            }
        }
    }

    /**
     * The two, three or four whole numbers a point, a size or a rectangle is: one labelled field each, joined
     * by commas on the wire.
     *
     * <p>Labelled because {@code 0,0,64,32} is four numbers nobody can tell apart, and a region typed into the
     * wrong pair is a bot that looks in the wrong place and says nothing about it.
     */
    private static final class NumberRow extends HBox {

        private final List<TextField> fields = new ArrayList<>();

        NumberRow(String wire, String... labels) {
            super(6);
            String[] parts = (wire == null ? "" : wire).split(",");
            for (int i = 0; i < labels.length; i++) {
                TextField field = new TextField(i < parts.length ? parts[i].trim() : "0");
                field.setPrefColumnCount(4);
                fields.add(field);
                VBox cell = new VBox(2, hint(labels[i]), field);
                getChildren().add(cell);
            }
        }

        String wire() {
            return String.join(",", fields.stream().map(ParamValueWidgets::text).toList());
        }
    }

    /**
     * An image template as a button naming the chosen one, opening the gallery picker — the same picker a
     * template slot in the block editor opens, so a template is chosen the same way in both places.
     */
    private static final class TemplateChip extends HBox {

        private final Button button = new Button();
        private final ProjectConfig config;
        private String name;

        TemplateChip(String initial, ProjectConfig config) {
            super(6);
            this.config = config;
            this.name = initial == null ? "" : initial.trim();
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(e -> pick());
            Button clear = new Button("✕");
            clear.getStyleClass().add("row-icon-button");
            clear.setOnAction(e -> {
                name = "";
                refresh();
            });
            getChildren().addAll(button, clear);
            refresh();
        }

        String templateName() {
            return name;
        }

        private void pick() {
            Window owner = getScene() == null ? null : getScene().getWindow();
            TemplateGalleryDialog.open(owner, config, TemplateGalleryDialog.Options.pickOne("Choose an image"),
                    chosen -> {
                        if (chosen == null || chosen.isEmpty()) return;
                        Path file = chosen.getFirst();
                        name = ImageTemplateLibrary.baseName(file);
                        refresh();
                    });
        }

        private void refresh() {
            button.setText(name.isBlank() ? "Choose an image…" : name);
        }
    }

    // --- small helpers ------------------------------------------------------------------------------------

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-hint-text");
        return label;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    private static double number(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code #RRGGBB} as an FX colour; anything unreadable is white, which is what the wire form says too. */
    private static Color parseColor(String wire) {
        try {
            return Color.web(wire == null || wire.isBlank() ? "#FFFFFF" : wire.trim());
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    /** An FX colour back as the wire form — {@code #RRGGBB}, alpha dropped, which is all java.awt.Color.decode reads. */
    private static String hex(Color color) {
        Color safe = color == null ? Color.WHITE : color;
        return "#%02X%02X%02X".formatted(
                Math.round(safe.getRed() * 255), Math.round(safe.getGreen() * 255), Math.round(safe.getBlue() * 255));
    }

    private static LocalDate parseDate(String wire) {
        if (wire == null || wire.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(wire.trim());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }
}
