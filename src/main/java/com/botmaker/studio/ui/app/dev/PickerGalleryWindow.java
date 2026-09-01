package com.botmaker.studio.ui.app.dev;

import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.activity.ValueWire;
import com.botmaker.sdk.authoring.TemplateLibrary;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every value editor in the app, on one screen, with what each one currently reads back.
 *
 * <p><b>Why a screen and not a test.</b> The failures these editors actually have are not the ones an
 * assertion catches: a spinner that refuses a typed {@code 1.5}, a combo whose comment says it is editable
 * when it is not, a thumbnail that never appears because a relative path was resolved against the working
 * directory. Every one of them is a thing you see. What was missing was a place to see all of them at once,
 * without declaring a variable of each type in a real project first.
 *
 * <p><b>The readout is the point.</b> Each row shows the control on the left and, on the right, the wire text
 * it reads back <em>right now</em> — polled, so it follows the control as it is touched — and beneath it the
 * value {@link ActivityVariable} would store, which is the same text after
 * {@link ValueWire#normalize normalisation}. A picker that looks right and hands back {@code ""}, or one
 * whose value survives the widget and is thrown away by the normaliser, is invisible without those two lines
 * side by side.
 *
 * <p>Rows are built through {@link ParamValueWidgets#build}, not by calling
 * {@link com.botmaker.studio.ui.app.params.ValueEditors#editorFor} directly, so what is on screen is exactly
 * what the Parameters dialog and the Runner window put there — shape widgets included. A row whose
 * construction throws says so in place of its control rather than taking the window down with it: finding
 * that is why you opened this.
 *
 * <p><b>Dev builds only.</b> Gated by {@link com.botmaker.studio.config.AppVersion#isDevBuild()} where it is
 * offered (Help ▸ Picker Gallery), the same switch that decides whether locally-installed SDK snapshots are
 * listed. It is Java source, but it is a screen, not a test class.
 */
public final class PickerGalleryWindow {

    /** How often the readouts re-read their controls. Fast enough to feel live, idle enough to ignore. */
    private static final Duration PULSE = Duration.millis(200);

    private final Window owner;
    private final ProjectConfig project;

    private final TextField filter = new TextField();
    private final CheckBox shapesToo = new CheckBox("Show the list and choice shapes too");
    private final GridPane grid = new GridPane();
    private final Label summary = new Label();
    private final List<Row> rows = new ArrayList<>();

    private Timeline pulse;

    /** @param project the open project, so the template and colour editors have something to resolve; may be null */
    public PickerGalleryWindow(Window owner, ProjectConfig project) {
        this.owner = owner;
        this.project = project;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Picker Gallery (dev)");

        filter.setPromptText("Filter by type — \"date\", \"number\", \"template\"…");
        filter.textProperty().addListener((obs, was, now) -> rebuild());
        shapesToo.setSelected(true);
        shapesToo.selectedProperty().addListener((obs, was, now) -> rebuild());

        grid.setHgap(14);
        grid.setVgap(10);
        grid.setPadding(new Insets(4, 4, 12, 4));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(150);
        ColumnConstraints shape = new ColumnConstraints();
        shape.setMinWidth(90);
        ColumnConstraints control = new ColumnConstraints();
        control.setMinWidth(280);
        control.setHgrow(Priority.SOMETIMES);
        ColumnConstraints readout = new ColumnConstraints();
        readout.setMinWidth(220);
        readout.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, shape, control, readout);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);

        summary.getStyleClass().add("dialog-hint-text");

        VBox root = new VBox(10, header(), new Separator(), scroll, summary);
        root.setPadding(new Insets(16));
        VBox.setVgrow(scroll, Priority.ALWAYS);

        rebuild();

        // Nothing here listens to the controls: they are twenty different widget types with twenty different
        // "changed" signals, and a poll asks all of them the one question this screen cares about.
        pulse = new Timeline(new KeyFrame(PULSE, e -> rows.forEach(Row::refresh)));
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        stage.setOnHidden(e -> pulse.stop());

        stage.setScene(ThemedWindows.scene(root, 1080, 760));
        stage.show();
    }

    private Node header() {
        Label title = new Label("Every editor, and what it hands back");
        title.getStyleClass().add("dialog-heading");
        Label note = new Label(project == null
                ? "No project open — the template and colour editors have nothing to resolve against."
                : "Resolving templates and colours against " + project.projectName() + ".");
        note.getStyleClass().add("dialog-hint-text");
        HBox controls = new HBox(12, filter, shapesToo);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filter, Priority.ALWAYS);
        return new VBox(6, title, note, controls);
    }

    // --- the rows -------------------------------------------------------------------------------------------

    /**
     * One row per (type, shape) the plugins bound right now can express.
     *
     * <p>The set comes from {@link ValueWire#registered()} rather than from a constant list: a stored
     * variable's type is whatever a plugin registered, so a screen enumerating a fixed set would stop showing
     * the editors it is for the moment a second plugin adds one.
     */
    private void rebuild() {
        rows.clear();
        grid.getChildren().clear();

        String needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<String> templates = templateNames();
        int line = 0;
        for (ValueType type : ValueWire.registered()) {
            if (!needle.isEmpty() && !type.label().toLowerCase(Locale.ROOT).contains(needle)
                    && !type.sourceName().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            for (ValueShape shape : ValueShape.values()) {
                if (shape != ValueShape.ONE && !shapesToo.isSelected()) continue;
                ActivityVariable variable = sample(type, shape, templates);
                if (variable == null) continue;   // the shape is not a sentence for this type
                line = addRow(variable, type, shape, line);
            }
        }
        summary.setText(rows.size() + " editors shown. The right-hand column is polled every "
                + (int) PULSE.toMillis() + "ms: touch a control and watch it move.");
    }

    private int addRow(ActivityVariable variable, ValueType type, ValueShape shape, int line) {
        Label name = new Label(type.label());
        Label shapeName = new Label(shape.label());
        shapeName.getStyleClass().add("dialog-hint-text");

        List<ParamValueWidgets.ValueEditor> readers = new ArrayList<>();
        Node widget;
        try {
            widget = ParamValueWidgets.build(variable, project, readers);
        } catch (RuntimeException | Error e) {
            // The whole reason for the screen: an editor that cannot even be built is a finding, not a crash.
            widget = broken("built: " + e);
            readers.clear();
        }

        Label raw = new Label();
        raw.getStyleClass().add("picker-wire-readout");
        raw.setWrapText(true);
        Label stored = new Label();
        stored.getStyleClass().addAll("picker-wire-readout", "dialog-hint-text");
        stored.setWrapText(true);
        VBox readout = new VBox(2, raw, stored);

        grid.addRow(line, name, shapeName, widget, readout);
        // A tick list is eight rows tall and its label belongs beside the first of them, not halfway down it.
        for (Node cell : List.of(name, shapeName, widget, readout)) GridPane.setValignment(cell, VPos.TOP);
        rows.add(new Row(variable, readers, raw, stored));
        return line + 1;
    }

    private static Node broken(String message) {
        Label label = new Label("✕ " + message);
        label.setWrapText(true);
        label.getStyleClass().add("dialog-error-text");
        return label;
    }

    /** One row: the variable it was built from, its widget's readers, and the two lines they write to. */
    private record Row(ActivityVariable variable, List<ParamValueWidgets.ValueEditor> readers,
                       Label raw, Label stored) {

        void refresh() {
            if (readers.isEmpty()) {
                write("—", "");
                return;
            }
            List<String> read;
            try {
                read = readers.getFirst().read().get();
            } catch (RuntimeException | Error e) {
                write("✕ read: " + e, "");
                return;
            }
            String kept;
            try {
                kept = show(variable.withValue(read).value());
            } catch (RuntimeException | Error e) {
                kept = "✕ stored: " + e;
            }
            write(show(read), "stored as " + kept);
        }

        private void write(String rawText, String storedText) {
            if (!rawText.equals(raw.getText())) raw.setText(rawText);
            if (!storedText.equals(stored.getText())) stored.setText(storedText);
        }

        /** Quoted, always — an editor reading back a single empty string is the failure this screen is for. */
        private static String show(List<String> wire) {
            return wire.stream().map(item -> "\"" + item + "\"").collect(java.util.stream.Collectors.joining(", "));
        }
    }

    // --- the sample variable each row is built from ----------------------------------------------------------

    /**
     * A variable of {@code type} in {@code shape}, or null when that pairing is not a thing anyone can
     * declare — "one of a set of colours" is, "one of a set of directions" is not, since the type already
     * shows every value it has.
     */
    static ActivityVariable sample(ValueType type, ValueShape shape, List<String> templates) {
        // ValueChoice corrects an impossible pairing rather than refusing it — it is built from files, and a
        // file that says something impossible must still open. Here the correction is the tell: the row it
        // would produce is another row's, already shown.
        ValueChoice choice = new ValueChoice(type, shape);
        if (choice.shape() != shape) return null;
        List<String> options = shape.hasOptions() ? options(type, templates) : List.of();
        return new ActivityVariable(identifier(type, shape), choice, ValueWire.defaultWire(choice),
                "", "", ParamVisibility.PUBLIC, options, Bounds.NONE,
                com.botmaker.plugin.api.ParameterGroup.DEFAULT_ID);
    }

    /**
     * A valid Java identifier, because {@link ActivityVariable}'s name is a generated field's — nothing here
     * generates code, and a sample that could not have been declared for real is a poor sample.
     */
    private static String identifier(ValueType type, ValueShape shape) {
        // An id is a plugin's free string, not necessarily a Java name, so anything that cannot appear in one
        // is folded to an underscore before the camel-casing. SCREAMING_SNAKE ids pass through untouched.
        String id = type.id().replaceAll("[^A-Za-z0-9_]", "_");
        if (id.isEmpty() || Character.isDigit(id.charAt(0))) id = "v" + id;
        return camel(id) + camel(shape.name()).substring(0, 1).toUpperCase(Locale.ROOT)
                + camel(shape.name()).substring(1);
    }

    /** {@code IMAGE_TEMPLATE} → {@code imageTemplate}. */
    private static String camel(String constant) {
        String[] parts = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
        }
        return out.toString();
    }

    /**
     * Two or three declared choices of {@code type}, for the shapes that need a set to draw.
     *
     * <p>Written out rather than derived: the values have to be <em>different from each other</em> and
     * different from the default, or a row of three radio buttons all reading {@code "0,0"} tests nothing.
     * The closed-set types are absent because they answer with their own constants
     * ({@link ValueWire#fixedOptions}) and never read this.
     *
     * <p><b>Keyed by {@linkplain ValueType#id() id}, not by the type object</b> — the vocabulary is open, so
     * there is no set to switch over exhaustively, and a plugin's type simply falls through to no samples.
     * These ids are the SDK's, and a screen this one can only be a screen about the plugins it has.
     *
     * @param templates the project's own template names, the one set that cannot be written down here
     */
    static List<String> options(ValueType type, List<String> templates) {
        return switch (type.id()) {
            case "TEXT" -> List.of("first", "second", "third");
            case "WHOLE_NUMBER" -> List.of("1", "2", "3");
            case "DECIMAL_NUMBER" -> List.of("0.5", "1.5", "2.5");
            case "CHARACTER" -> List.of("a", "b", "c");
            case "COLOR" -> List.of("#FF0000", "#00A000", "#3050FF");
            case "DATE" -> List.of("2026-01-01", "2026-06-15");
            case "TIME_OF_DAY" -> List.of("08:00", "12:30", "23:59");
            case "DURATION" -> List.of("30s", "5m", "1h30m");
            case "POINT" -> List.of("0,0", "100,250");
            case "SIZE" -> List.of("64,64", "1920,1080");
            case "RECT" -> List.of("0,0,100,100", "10,10,50,50");
            case "PRECISION" -> List.of("12.0,4,0", "6.0,2,0");
            case "IMAGE_TEMPLATE" -> templates;
            default -> List.of();
        };
    }

    /** The project's own templates, so the chips resolve to real pictures; empty when nothing is open. */
    private List<String> templateNames() {
        if (project == null) return List.of();
        return TemplateLibrary.list(project.resourcesRoot()).stream()
                .limit(3)
                .map(TemplateLibrary::baseName)
                .toList();
    }
}
