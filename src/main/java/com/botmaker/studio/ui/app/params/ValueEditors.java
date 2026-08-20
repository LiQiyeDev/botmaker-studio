package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.DurationWire;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.render.components.DurationFields;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import com.botmaker.studio.ui.render.components.TemplateGalleryDialog;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One editor per {@link BotType}, built from a wire value and read back as a wire value.
 *
 * <p><b>Why this exists separately from the block editor's pickers.</b> {@code ui.render.components} has a
 * picker for most of these types already, but every one of them is built around an {@code ExpressionBlock}:
 * it reads the current value out of a JDT node and commits by rewriting that node. That makes them unusable
 * anywhere there is no AST — the Parameters dialog, the Runner window, the activity Variables screen — which
 * is why those grew a second, weaker set of widgets: a comma-joined text field for a rectangle, a dropdown
 * with nothing in it for a precision, a duration that could not say "4h30m".
 *
 * <p>This class is that second set replaced by a real one, stated at the level all three callers share: a
 * {@link BotType} plus the text it currently holds, in, and a {@link Node} plus a reader out. Where an editor
 * needs the screen or the project it takes them from {@link Context}, never from a service locator.
 *
 * <p><b>Reading is total and never validates.</b> A half-typed duration, a number past its bound, a template
 * that has since been deleted: every one of them is handed back as typed and pulled into range downstream by
 * {@link VariableWire}. Nothing here can refuse a value, so nothing here can leave a dialog unable to close
 * because of a limit somebody tightened afterwards.
 */
public final class ValueEditors {

    private ValueEditors() {}

    /** A built editor: the control to show, and what it currently says in wire form. */
    public record Editor(Node node, Supplier<String> read) {}

    /**
     * What an editor may need beyond the value itself: the project (for the template gallery) and the
     * variable's declared range (for the number editors). Both are optional — an editor that needs one it
     * wasn't given falls back to the unguided form rather than failing.
     */
    public record Context(ProjectConfig project, Bounds bounds) {

        public Context {
            if (bounds == null) bounds = Bounds.NONE;
        }

        public static Context of(ProjectConfig project) {
            return new Context(project, Bounds.NONE);
        }

        public static Context none() {
            return new Context(null, Bounds.NONE);
        }

        public Context withBounds(Bounds newBounds) {
            return new Context(project, newBounds);
        }
    }

    /**
     * The editor for one value of {@code type}, seeded from {@code wire}.
     *
     * <p>Chosen by the type alone, which is what makes retyping a variable safe to handle by rebuilding the
     * row wholesale: the caller throws the old editor away rather than trying to reinterpret what was in it,
     * which is how a date once came back holding text typed for a number.
     */
    public static Editor editorFor(BotType type, String wire, Context ctx) {
        String value = wire == null ? "" : wire;
        return switch (type) {
            case YES_NO -> {
                CheckBox box = new CheckBox();
                box.setSelected(Boolean.parseBoolean(value));
                yield new Editor(box, () -> Boolean.toString(box.isSelected()));
            }
            case WHOLE_NUMBER -> number(value, ctx.bounds(), true);
            case DECIMAL_NUMBER -> number(value, ctx.bounds(), false);
            case DURATION -> {
                // The shared four-field control (ui.render.components.DurationFields) — the same one the
                // block editor's wait picker opens, so a duration means the same thing on both sides.
                DurationFields fields = new DurationFields(DurationWire.parse(value, 0L));
                yield new Editor(fields, () -> DurationWire.format(fields.totalMillis()));
            }
            case TIME_OF_DAY -> {
                TimeRow row = new TimeRow(value);
                yield new Editor(row, row::wire);
            }
            case DATE -> {
                DatePicker picker = new DatePicker(parseDate(value));
                yield new Editor(picker, () -> picker.getValue() == null ? "" : picker.getValue().toString());
            }
            case PRECISION -> {
                PrecisionRow row = new PrecisionRow(value);
                yield new Editor(row, row::wire);
            }
            case COLOR -> {
                ColorRow row = new ColorRow(value, ctx.project());
                yield new Editor(row, row::wire);
            }
            case DIRECTION -> {
                DirectionPad pad = new DirectionPad(value);
                yield new Editor(pad, pad::wire);
            }
            case MOUSE_BUTTON -> {
                MouseDiagram diagram = new MouseDiagram(value);
                yield new Editor(diagram, diagram::wire);
            }
            case KEY -> {
                // A hundred key names is a list, not a form: the one enum that keeps a dropdown, and it is
                // editable so the name can be typed rather than scrolled to.
                ComboBox<String> box = new ComboBox<>();
                box.getItems().setAll(VariableWire.effectiveOptions(BotType.KEY, List.of()));
                box.setValue(box.getItems().contains(value) ? value : null);
                yield new Editor(box, () -> box.getValue() == null ? "" : box.getValue());
            }
            case IMAGE_TEMPLATE -> {
                TemplateChip chip = new TemplateChip(value, ctx.project());
                yield new Editor(chip, chip::wire);
            }
            case POINT -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.POINT, ctx.project());
                yield new Editor(row, row::wire);
            }
            case SIZE -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.SIZE, ctx.project());
                yield new Editor(row, row::wire);
            }
            case RECT -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.RECT, ctx.project());
                yield new Editor(row, row::wire);
            }
            case CHARACTER -> {
                // One character is the whole value, so the field is one character wide rather than letting
                // somebody type a word that would silently become its first letter.
                TextField field = new TextField(value);
                field.setPrefColumnCount(2);
                yield new Editor(field, () -> text(field));
            }
            default -> { // TEXT, and any type with no editor of its own
                TextField field = new TextField(value);
                yield new Editor(field, () -> text(field));
            }
        };
    }

    // --- one declared option --------------------------------------------------------------------------------

    /**
     * How one <em>declared choice</em> of {@code type} should be drawn beside its radio button or tick box, or
     * {@code null} when the wire text is already the whole value and the control's own label says it.
     *
     * <p>The set of types this answers for is the set where the stored string is a <em>reference</em> rather
     * than the value: a template name is not a picture, {@code #3A7F2B} is not a colour, {@code 4h30m} is four
     * numbers a person has to decode. Offering the author a gallery to pick a choice from (see
     * {@code ParametersDialog.buildOptionsEditor}) and then listing what they picked as raw text would put the
     * decoding back on the person the choices exist for.
     */
    static Node optionGraphic(BotType type, String wire, Context ctx) {
        String value = wire == null ? "" : wire.trim();
        if (value.isEmpty()) return null;
        return switch (type) {
            case IMAGE_TEMPLATE -> {
                Path file = templateFile(ctx.project(), value);
                // A name that no longer resolves keeps the plain label: "this template was deleted" is the
                // honest reading, and it is the same one TemplateChip gives.
                yield file == null ? null : TemplateGallery.plainTile(file, 48);
            }
            case COLOR -> {
                Region swatch = new Region();
                swatch.setPrefSize(14, 14);
                swatch.setMinSize(14, 14);
                swatch.setBackground(new Background(new BackgroundFill(parseColor(value), null, null)));
                swatch.getStyleClass().add("option-color-swatch");
                yield swatch;
            }
            case DURATION -> {
                Label spelled = new Label(DurationWire.format(DurationWire.parse(value, 0L)));
                spelled.getStyleClass().add("dialog-hint-text");
                yield spelled;
            }
            case DIRECTION -> {
                String arrow = DirectionPad.CELLS.stream()
                        .filter(cell -> cell.name().equals(value))
                        .map(DirectionPad.Cell::arrow)
                        .findFirst().orElse(null);
                yield arrow == null ? null : new Label(arrow);
            }
            default -> null;
        };
    }

    /** The file behind a template name, or null when the project has no such template any more. */
    private static Path templateFile(ProjectConfig project, String name) {
        if (project == null || name.isBlank()) return null;
        try {
            String path = ImageTemplateLibrary.pathForName(project, name);
            if (path == null || path.isBlank()) return null;
            Path file = Path.of(path);
            return Files.isRegularFile(file) ? file : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- numbers --------------------------------------------------------------------------------------------

    /**
     * A number as a {@link Spinner}, always — with whichever of the two bounds was declared, and the type's
     * own limit standing in for the other.
     *
     * <p>Both ends being independent is the fix: "at most 10, no minimum" is a sentence a person says, and it
     * used to produce a bare text field because the old branch asked for a complete range before it would
     * show a spinner at all. There is no step to declare — a whole number steps by one, and a decimal steps
     * by a tenth, which is fine as a nudge and was never the right thing to persist per variable.
     */
    private static Editor number(String wire, Bounds bounds, boolean whole) {
        double min = number(bounds.min(), whole ? Integer.MIN_VALUE : -Double.MAX_VALUE);
        double max = number(bounds.max(), whole ? Integer.MAX_VALUE : Double.MAX_VALUE);
        if (max < min) max = min;
        double current = Math.max(min, Math.min(max, number(wire, Math.max(min, Math.min(max, 0)))));

        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, current, whole ? 1 : 0.1));
        spinner.setEditable(true);
        if (whole) {
            // The factory is a double one so both number types share a widget; whole numbers still have to
            // read as whole numbers, or a bounded int shows "3.0" in its own editor.
            double seed = current;
            spinner.getValueFactory().setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Double v) {
                    return v == null ? "" : Long.toString(Math.round(v));
                }

                @Override public Double fromString(String t) {
                    return number(t, seed);
                }
            });
            spinner.getEditor().setText(Long.toString(Math.round(current)));
        } else {
            spinner.getEditor().setText(trimZero(current));
        }
        if (!bounds.isEmpty()) spinner.setTooltip(new Tooltip(rangeText(bounds)));
        return new Editor(spinner, () -> text(spinner.getEditor()));
    }

    /** "at least 1", "at most 10", "1 to 10" — the declared range as the sentence it is. */
    static String rangeText(Bounds bounds) {
        if (bounds.min() != null && bounds.max() != null) return bounds.min() + " to " + bounds.max();
        if (bounds.min() != null) return "at least " + bounds.min();
        if (bounds.max() != null) return "at most " + bounds.max();
        return "";
    }

    // --- time of day ----------------------------------------------------------------------------------------

    /**
     * A time of day as hour, minute and second, with the value spelled out beside it.
     *
     * <p>Seconds are here because a daily reset at 23:59:59 is a real thing to want and the two-spinner
     * version silently rounded it to the minute. All three wrap, so 23 → 00 is one click rather than a scroll
     * back through the day.
     */
    private static final class TimeRow extends HBox {

        private final Spinner<Integer> hours = wrapping(23);
        private final Spinner<Integer> minutes = wrapping(59);
        private final Spinner<Integer> seconds = wrapping(59);
        private final Label preview = new Label();

        TimeRow(String wire) {
            super(4);
            setAlignment(Pos.CENTER_LEFT);
            LocalTime time = parseTime(wire);
            hours.getValueFactory().setValue(time.getHour());
            minutes.getValueFactory().setValue(time.getMinute());
            seconds.getValueFactory().setValue(time.getSecond());
            preview.getStyleClass().add("dialog-hint-text");
            for (Spinner<Integer> spinner : List.of(hours, minutes, seconds)) {
                spinner.valueProperty().addListener((obs, was, now) -> refresh());
            }
            getChildren().addAll(hours, new Label(":"), minutes, new Label(":"), seconds, preview);
            refresh();
        }

        String wire() {
            return "%02d:%02d:%02d".formatted(value(hours), value(minutes), value(seconds));
        }

        private void refresh() {
            int h = value(hours);
            String suffix = h < 12 ? "am" : "pm";
            int twelve = h % 12 == 0 ? 12 : h % 12;
            preview.setText("(%d:%02d %s)".formatted(twelve, value(minutes), suffix));
        }

        private static Spinner<Integer> wrapping(int max) {
            Spinner<Integer> spinner = new Spinner<>(0, max, 0);
            spinner.setEditable(true);
            spinner.setPrefWidth(78);
            ((SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory()).setWrapAround(true);
            return spinner;
        }

        private static int value(Spinner<Integer> spinner) {
            return spinner.getValue() == null ? 0 : spinner.getValue();
        }

        private static LocalTime parseTime(String wire) {
            if (wire == null || wire.isBlank()) return LocalTime.MIDNIGHT;
            try {
                return LocalTime.parse(wire.trim());
            } catch (RuntimeException e) {
                return LocalTime.MIDNIGHT;
            }
        }
    }

    // --- precision ------------------------------------------------------------------------------------------

    /**
     * The three numbers a {@code Precision} is — colour tolerance, smallest blob, fewest blobs — as a preset
     * dropdown over the tolerance plus two fields.
     *
     * <p>This slot used to render as an empty dropdown, and the reason is worth recording: the generic enum
     * branch asked {@code SdkType.PRECISION.enumConstantNames()}, and {@code Precision} is a <em>record</em>,
     * so it has no constants and the list came back empty. A record's fields are what it needs edited, and
     * they are what this shows.
     *
     * <p>ΔE is named by tolerance rather than by number because "12" means nothing without knowing the scale;
     * the exact value is still typeable for anyone who does.
     */
    private static final class PrecisionRow extends HBox {

        /** The tolerance presets, as ΔE in the CIE76 sense the SDK matcher uses. */
        private enum Tolerance {
            EXACT("Exact", 0), STRICT("Strict", 5), NORMAL("Normal", 12), LOOSE("Loose", 25);

            final String label;
            final double deltaE;

            Tolerance(String label, double deltaE) {
                this.label = label;
                this.deltaE = deltaE;
            }
        }

        private final ComboBox<Tolerance> preset = new ComboBox<>();
        private final TextField deltaE = new TextField();
        private final TextField minArea = new TextField();
        private final TextField minCount = new TextField();

        PrecisionRow(String wire) {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            String[] parts = (wire == null ? "" : wire).split(",");
            deltaE.setText(part(parts, 0, "12.0"));
            minArea.setText(part(parts, 1, "4"));
            minCount.setText(part(parts, 2, "0"));
            for (TextField field : List.of(deltaE, minArea, minCount)) field.setPrefColumnCount(5);

            preset.getItems().setAll(Tolerance.values());
            preset.setButtonCell(presetCell());
            preset.setCellFactory(list -> presetCell());
            preset.valueProperty().addListener((obs, was, now) -> {
                if (now != null) deltaE.setText(trimZero(now.deltaE));
            });
            syncPreset();
            deltaE.textProperty().addListener((obs, was, now) -> syncPreset());

            getChildren().addAll(preset, labelled("ΔE", deltaE), labelled("min area", minArea),
                    labelled("min blobs", minCount));
        }

        String wire() {
            return number(text(deltaE), 12.0) + "," + (long) number(text(minArea), 4)
                    + "," + (long) number(text(minCount), 0);
        }

        /** Shows the preset the typed ΔE happens to be, and nothing when it is a value of its own. */
        private void syncPreset() {
            double current = number(text(deltaE), -1);
            Tolerance match = null;
            for (Tolerance t : Tolerance.values()) {
                if (Math.abs(t.deltaE - current) < 0.001) match = t;
            }
            preset.setValue(match);
        }

        private static String part(String[] parts, int index, String fallback) {
            return index < parts.length && !parts[index].isBlank() ? parts[index].trim() : fallback;
        }

        private static javafx.scene.control.ListCell<Tolerance> presetCell() {
            return new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(Tolerance item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "Custom" : item.label);
                }
            };
        }
    }

    // --- colour ---------------------------------------------------------------------------------------------

    /**
     * A colour as a swatch plus an eyedropper onto the screen.
     *
     * <p>The palette alone is not enough for the case this is nearly always used for: game art is shaded,
     * compressed and anti-aliased, so the red of a health bar is never {@code #FF0000} and no amount of
     * staring at a colour wheel will produce it. The eyedropper opens the same magnified overlay the point
     * picker uses — the lens is what makes a one-pixel target hittable — and reports the pixel under the
     * click.
     */
    private static final class ColorRow extends HBox {

        private final ColorPicker picker = new ColorPicker();

        ColorRow(String wire, ProjectConfig project) {
            super(4);
            setAlignment(Pos.CENTER_LEFT);
            picker.setValue(parseColor(wire));
            HBox.setHgrow(picker, Priority.ALWAYS);
            picker.setMaxWidth(Double.MAX_VALUE);

            Button eyedropper = new Button("⌖");
            eyedropper.getStyleClass().add("color-eyedropper");
            eyedropper.setTooltip(new Tooltip("Pick a colour off the screen, magnified"));
            eyedropper.setOnAction(e -> ScreenCaptureService.forProjectFiles(project)
                    .pickColor(window(this), pick -> {
                        java.awt.Color c = pick.color();
                        Platform.runLater(() ->
                                picker.setValue(Color.rgb(c.getRed(), c.getGreen(), c.getBlue())));
                    }));
            getChildren().addAll(picker, eyedropper);
        }

        String wire() {
            return hex(picker.getValue());
        }
    }

    // --- direction ------------------------------------------------------------------------------------------

    /**
     * The eight directions as a 3×3 arrow pad with a blank centre.
     *
     * <p>A dropdown of eight names is a list to read; a pad of eight arrows is a shape to point at, and the
     * shape is what the value means. It is also the one enum editor where the layout carries the semantics —
     * up-left is up and to the left — so nothing has to be read at all.
     *
     * <p>The pad is laid out from the enum's own constants, so a direction added to the SDK appears here
     * without a second list to update; anything it does not recognise the position of goes in the centre.
     */
    private static final class DirectionPad extends VBox {

        /** Where each constant sits on the pad, by column and row. Names are the SDK's. */
        record Cell(String name, String arrow, int column, int row) {}

        /**
         * Both spellings a direction has been given, at the same squares.
         *
         * <p>The SDK's {@code Direction} is the compass — {@code NORTH}, {@code SOUTH}, {@code EAST},
         * {@code WEST} — and this table had only the screen spelling, so <em>every</em> constant missed the pad
         * and came out in the row of named buttons underneath meant for the odd one out. A pad that positions
         * none of its values is a dropdown with extra steps, which is what shipped.
         */
        static final List<Cell> CELLS = List.of(
                new Cell("UP_LEFT", "↖", 0, 0), new Cell("UP", "↑", 1, 0), new Cell("UP_RIGHT", "↗", 2, 0),
                new Cell("LEFT", "←", 0, 1), new Cell("RIGHT", "→", 2, 1),
                new Cell("DOWN_LEFT", "↙", 0, 2), new Cell("DOWN", "↓", 1, 2), new Cell("DOWN_RIGHT", "↘", 2, 2),
                new Cell("NORTH", "↑", 1, 0), new Cell("SOUTH", "↓", 1, 2),
                new Cell("WEST", "←", 0, 1), new Cell("EAST", "→", 2, 1));

        private final ToggleGroup group = new ToggleGroup();

        DirectionPad(String current) {
            super(4);
            getStyleClass().add("direction-pad");
            List<String> known = VariableWire.effectiveOptions(BotType.DIRECTION, List.of());

            GridPane pad = new GridPane();
            pad.setHgap(2);
            pad.setVgap(2);
            // One button per square. The two spellings share squares, and an SDK that ever had both would
            // otherwise stack two buttons on one cell — the second painting over the first.
            Set<String> taken = new java.util.HashSet<>();
            for (Cell cell : CELLS) {
                if (!known.contains(cell.name())) continue;
                if (!taken.add(cell.column() + "," + cell.row())) continue;
                pad.add(button(cell.name(), cell.arrow(), current), cell.column(), cell.row());
            }
            getChildren().add(pad);

            // A direction the SDK has that the pad has no square for still has to be reachable, so it goes in
            // a row underneath as a named button rather than quietly disappearing from the editor.
            HBox spare = new HBox(2);
            for (String name : known) {
                if (CELLS.stream().anyMatch(c -> c.name().equals(name))) continue;
                spare.getChildren().add(button(name, name, current));
            }
            if (!spare.getChildren().isEmpty()) getChildren().add(spare);
        }

        String wire() {
            Toggle chosen = group.getSelectedToggle();
            return chosen == null ? "" : (String) chosen.getUserData();
        }

        private ToggleButton button(String name, String glyph, String current) {
            ToggleButton button = new ToggleButton(glyph);
            button.setToggleGroup(group);
            button.setUserData(name);
            button.setSelected(name.equals(current));
            button.setTooltip(new Tooltip(name));
            button.getStyleClass().add("direction-pad-key");
            button.setPrefSize(30, 30);
            return button;
        }
    }

    // --- mouse button ---------------------------------------------------------------------------------------

    /**
     * The mouse buttons as a labelled diagram: the two main buttons and the wheel across the top, the side
     * buttons under them.
     *
     * <p><b>What this names is what the button does, never where it sits.</b> A mouse with two thumb buttons,
     * a left-handed mouse, a mouse the vendor's driver has remapped — in all of them the OS reports the
     * button the user configured, so a bot that says Back keeps working on a mouse whose back button is
     * somewhere else, and Studio never has to ask which layout the machine running the bot has.
     */
    private static final class MouseDiagram extends VBox {

        private static final List<String[]> TOP = List.of(
                new String[]{"LEFT", "Left"}, new String[]{"MIDDLE", "Wheel"}, new String[]{"RIGHT", "Right"});
        private static final List<String[]> SIDE = List.of(
                new String[]{"BACK", "Back"}, new String[]{"FORWARD", "Forward"});

        private final ToggleGroup group = new ToggleGroup();

        MouseDiagram(String current) {
            super(4);
            getStyleClass().add("mouse-diagram");
            List<String> known = VariableWire.effectiveOptions(BotType.MOUSE_BUTTON, List.of());
            HBox top = new HBox(2);
            HBox side = new HBox(2);
            for (String[] entry : TOP) {
                if (known.contains(entry[0])) top.getChildren().add(button(entry[0], entry[1], current));
            }
            for (String[] entry : SIDE) {
                if (known.contains(entry[0])) side.getChildren().add(button(entry[0], entry[1], current));
            }
            getChildren().add(top);
            if (!side.getChildren().isEmpty()) {
                Label hint = new Label("side buttons");
                hint.getStyleClass().add("dialog-hint-text");
                getChildren().addAll(side, hint);
            }
        }

        String wire() {
            Toggle chosen = group.getSelectedToggle();
            return chosen == null ? "" : (String) chosen.getUserData();
        }

        private ToggleButton button(String name, String label, String current) {
            ToggleButton button = new ToggleButton(label);
            button.setToggleGroup(group);
            button.setUserData(name);
            button.setSelected(name.equals(current));
            button.getStyleClass().add("mouse-diagram-key");
            return button;
        }
    }

    // --- geometry -------------------------------------------------------------------------------------------

    /**
     * The two or four whole numbers a point, a size or a rectangle is: one labelled field each, plus the
     * button that takes them off the screen instead.
     *
     * <p>Labelled because {@code 0,0,64,32} is four numbers nobody can tell apart, and a region typed into the
     * wrong pair is a bot that looks in the wrong place and says nothing about it. The screen pick is the
     * point of the whole control though — nobody knows the coordinates of anything, they know where it is.
     */
    private static final class GeometryRow extends HBox {

        enum Kind {
            POINT("Pick on screen…", "x", "y"),
            SIZE("Measure on screen…", "width", "height"),
            RECT("Select on screen…", "x", "y", "width", "height");

            final String pickLabel;
            final String[] labels;

            Kind(String pickLabel, String... labels) {
                this.pickLabel = pickLabel;
                this.labels = labels;
            }
        }

        private final List<TextField> fields = new ArrayList<>();
        private final ProjectConfig project;

        GeometryRow(String wire, Kind kind, ProjectConfig project) {
            super(6);
            this.project = project;
            setAlignment(Pos.BOTTOM_LEFT);
            String[] parts = (wire == null ? "" : wire).split(",");
            for (int i = 0; i < kind.labels.length; i++) {
                TextField field = new TextField(i < parts.length ? parts[i].trim() : "0");
                field.setPrefColumnCount(4);
                fields.add(field);
                getChildren().add(labelled(kind.labels[i], field));
            }
            Button pick = new Button(kind.pickLabel);
            pick.setOnAction(e -> pick(kind));
            getChildren().add(pick);
        }

        String wire() {
            return String.join(",", fields.stream().map(ValueEditors::text).toList());
        }

        /**
         * A point comes from the magnified point overlay; a size and a rectangle both come from the
         * rubber-band region selection — a size is that selection with the origin thrown away, which is
         * exactly how a person measures something on screen.
         */
        private void pick(Kind kind) {
            ScreenCaptureService capture = ScreenCaptureService.forProjectFiles(project);
            Window owner = window(this);
            if (kind == Kind.POINT) {
                capture.pickPoint(owner, p -> set(p[0], p[1]));
            } else if (kind == Kind.SIZE) {
                capture.selectRegion(owner, r -> set(r[2], r[3]));
            } else {
                capture.selectRegion(owner, r -> set(r[0], r[1], r[2], r[3]));
            }
        }

        private void set(int... values) {
            Platform.runLater(() -> {
                for (int i = 0; i < values.length && i < fields.size(); i++) {
                    fields.get(i).setText(Integer.toString(values[i]));
                }
            });
        }
    }

    // --- image template -------------------------------------------------------------------------------------

    /**
     * An image template as its picture and its name, opening the same gallery a template slot in the block
     * editor opens.
     *
     * <p>The thumbnail is the whole reason this is not a dropdown. A template's name is something the person
     * who imported it chose, often in a hurry and often for a batch of twenty; the picture is what says
     * whether this is the right one, and having to open the gallery to find out is the check nobody performs.
     */
    private static final class TemplateChip extends HBox {

        private static final double THUMB = 34;

        private final Button button = new Button();
        private final ImageView thumb = new ImageView();
        private final ProjectConfig project;
        private String name;

        TemplateChip(String initial, ProjectConfig project) {
            super(6);
            setAlignment(Pos.CENTER_LEFT);
            this.project = project;
            this.name = initial == null ? "" : initial.trim();

            thumb.setFitWidth(THUMB);
            thumb.setFitHeight(THUMB);
            thumb.setPreserveRatio(true);
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(e -> pick());

            Button clear = new Button("✕");
            clear.getStyleClass().add("row-icon-button");
            clear.setOnAction(e -> {
                name = "";
                refresh();
            });
            getChildren().addAll(thumb, button, clear);
            refresh();
        }

        String wire() {
            return name;
        }

        private void pick() {
            if (project == null) return;
            TemplateGalleryDialog.open(window(this), project,
                    TemplateGalleryDialog.Options.pickOne("Choose an image"), chosen -> {
                        if (chosen == null || chosen.isEmpty()) return;
                        name = ImageTemplateLibrary.baseName(chosen.getFirst());
                        refresh();
                    });
        }

        private void refresh() {
            button.setText(name.isBlank() ? "Choose an image…" : name);
            thumb.setImage(preview());
            thumb.setVisible(thumb.getImage() != null);
            thumb.setManaged(thumb.getImage() != null);
        }

        /**
         * The template's picture, or nothing at all — a name that no longer resolves shows as a name with no
         * thumbnail, which is the honest reading of "this template was deleted".
         */
        private Image preview() {
            if (project == null || name.isBlank()) return null;
            try {
                String path = ImageTemplateLibrary.pathForName(project, name);
                if (path == null || path.isBlank()) return null;
                Path file = Path.of(path);
                if (!Files.isRegularFile(file)) return null;
                return new Image(file.toUri().toString(), THUMB * 2, THUMB * 2, true, true);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // --- small helpers --------------------------------------------------------------------------------------

    /** A field under its name, so a row of four numbers is four named numbers. */
    private static VBox labelled(String label, Control field) {
        Label caption = new Label(label);
        caption.getStyleClass().add("dialog-hint-text");
        return new VBox(2, caption, field);
    }

    private static Window window(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    static double number(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code 12.0} as "12" and {@code 12.5} as "12.5" — a whole value should not read as a decimal. */
    private static String trimZero(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    /** {@code #RRGGBB} as an FX colour; anything unreadable is white, which is what the wire form says too. */
    static Color parseColor(String wire) {
        try {
            return Color.web(wire == null || wire.isBlank() ? "#FFFFFF" : wire.trim());
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    /** An FX colour back as the wire form — {@code #RRGGBB}, alpha dropped, which is all Color.decode reads. */
    static String hex(Color color) {
        Color safe = color == null ? Color.WHITE : color;
        return "#%02X%02X%02X".formatted(Math.round(safe.getRed() * 255),
                Math.round(safe.getGreen() * 255), Math.round(safe.getBlue() * 255));
    }

    static LocalDate parseDate(String wire) {
        if (wire == null || wire.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(wire.trim());
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }

    /** Lets an editor fill the width it is given, which is what a form column wants and a toolbar does not. */
    public static void stretch(Node node) {
        if (node instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        else if (node instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }
}
