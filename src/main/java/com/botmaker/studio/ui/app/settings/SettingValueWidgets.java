package com.botmaker.studio.ui.app.settings;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.settings.DurationWire;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.ui.render.components.TemplateGalleryDialog;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the value-entry widget for one {@link Setting}, seeded from its current value, and hands back a
 * reader turning the widget's live state into the type's wire form.
 *
 * <p>The successor to {@code ui/app/params/ParamValueWidgets}, and the difference is what a reader answers.
 * That one produced a Jackson {@code JsonNode}, because the value's destination was a JSON file a running bot
 * parsed. This one produces {@code List<String>} — the wire form {@link SettingType} describes — because the
 * destination is a generated Java literal, and text is the form both halves of that round trip agree on.
 *
 * <p><b>Reading is total and never validates.</b> A half-typed duration, a number past its bound, a template
 * that has since been deleted: every one of them is handed on as typed and pulled into range by
 * {@link Setting#withValues}, which normalises. Nothing here can refuse a value, so nothing here can leave the
 * editor unable to close a dialog because of a limit somebody tightened afterwards.
 *
 * <p>Shared by the Settings dialog and the Runner window, so a type is entered the same way wherever it is met.
 */
public final class SettingValueWidgets {

    /** How wide a value column gets, so a row of them reads as a column rather than as ragged text. */
    private static final double VALUE_WIDTH = 260;

    private SettingValueWidgets() {}

    /** A setting's name plus a reader turning its widget's UI state back into the wire form. */
    public record ValueEditor(String name, Supplier<List<String>> read) {}

    /**
     * The widget for {@code setting}, seeded from its current value, registering its reader in {@code sink}.
     *
     * @param config the project, needed by the one type whose picker reads from disk ({@link SettingType#TEMPLATE})
     */
    public static Node build(Setting setting, ProjectConfig config, List<ValueEditor> sink) {
        Node widget;
        Supplier<List<String>> reader;
        switch (setting.type()) {
            case BOOL, ENABLE -> {
                CheckBox box = new CheckBox();
                box.setSelected(Boolean.parseBoolean(setting.singleValue()));
                widget = box;
                reader = () -> List.of(Boolean.toString(box.isSelected()));
            }
            case INT -> {
                Node field = numberField(setting, true);
                widget = field;
                reader = () -> List.of(numberText(field));
            }
            case DOUBLE -> {
                Node field = numberField(setting, false);
                widget = field;
                reader = () -> List.of(numberText(field));
            }
            case DURATION -> {
                DurationField field = new DurationField(setting.singleValue());
                widget = field;
                reader = () -> List.of(field.wire());
            }
            case TIME -> {
                TextField field = new TextField(setting.singleValue());
                field.setPromptText("HH:mm");
                widget = field;
                reader = () -> List.of(text(field));
            }
            case DATE -> {
                DatePicker picker = new DatePicker(parseDate(setting.singleValue()));
                widget = picker;
                reader = () -> List.of(picker.getValue() == null ? "" : picker.getValue().toString());
            }
            case CHOICE, KEY, MOUSE_BUTTON -> {
                ComboBox<String> box = new ComboBox<>();
                box.getItems().setAll(setting.effectiveOptions());
                // Only ever select a declared choice: a value the editor has since removed shows as blank,
                // which is the truth, rather than being put back into the list it was taken out of.
                box.setValue(box.getItems().contains(setting.singleValue()) ? setting.singleValue() : null);
                widget = box;
                reader = () -> List.of(box.getValue() == null ? "" : box.getValue());
            }
            case MULTI_CHOICE -> {
                List<CheckBox> boxes = new ArrayList<>();
                VBox column = new VBox(2);
                for (String option : setting.effectiveOptions()) {
                    CheckBox box = new CheckBox(option);
                    box.setSelected(setting.value().contains(option));
                    boxes.add(box);
                    column.getChildren().add(box);
                }
                if (boxes.isEmpty()) column.getChildren().add(hint("No choices declared yet."));
                widget = column;
                reader = () -> boxes.stream().filter(CheckBox::isSelected).map(CheckBox::getText).toList();
            }
            case TEMPLATE -> {
                TemplateChip chip = new TemplateChip(setting.singleValue(), config);
                widget = chip;
                reader = () -> List.of(chip.templateName());
            }
            default -> { // TEXT
                TextField field = new TextField(setting.singleValue());
                widget = field;
                reader = () -> List.of(text(field));
            }
        }
        if (widget instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        widget.setId("setting-value-" + setting.name());
        sink.add(new ValueEditor(setting.name(), reader));
        return widget;
    }

    /** The same widget, pinned to one width — what a list of settings wants, and a form does not. */
    public static Node buildFixedWidth(Setting setting, ProjectConfig config, List<ValueEditor> sink) {
        Node widget = build(setting, config, sink);
        if (widget instanceof javafx.scene.layout.Region region) region.setPrefWidth(VALUE_WIDTH);
        return widget;
    }

    /** One value as a person would read it — a multi-choice as its joined members, not as an empty cell. */
    public static String display(Setting setting) {
        if (setting.type().isMultiValued()) return String.join(", ", setting.value());
        return setting.singleValue();
    }

    // --- the widgets with more than one control in them ---------------------------------------------------

    /**
     * A number as a {@link Spinner} when the setting declares a range, and as a plain field when it does not.
     * A spinner over the whole of {@code int} is a pair of arrows nobody can reach anything with; a spinner
     * over 1–10 is the control that says what the limits are without a sentence explaining them.
     */
    private static Node numberField(Setting setting, boolean whole) {
        Setting.Bounds bounds = setting.bounds();
        if (bounds.isEmpty()) {
            TextField field = new TextField(setting.singleValue());
            return field;
        }
        Spinner<Double> spinner = new Spinner<>();
        double min = number(bounds.min(), whole ? Integer.MIN_VALUE : -Double.MAX_VALUE);
        double max = number(bounds.max(), whole ? Integer.MAX_VALUE : Double.MAX_VALUE);
        double step = number(bounds.step(), whole ? 1 : 0.1);
        double current = Math.max(min, Math.min(max, number(setting.singleValue(), min)));
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

    private static LocalDate parseDate(String wire) {
        if (wire == null || wire.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(wire.trim());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }
}
