package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.SlotEditor;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.sdk.authoring.WireText;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.HostValueContext;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.ValueWire;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One editor per {@link ValueType}, built from a wire value and read back as a wire value.
 *
 * <p><b>Why this exists separately from the block editor's pickers.</b> {@code ui.render.components} has a
 * picker for most of these types already, but every one of them is built around an {@code ExpressionBlock}:
 * it reads the current value out of a JDT node and commits by rewriting that node. That makes them unusable
 * anywhere there is no AST — the Parameters dialog, the Runner window, the activity Variables screen — which
 * is why those grew a second, weaker set of widgets: a comma-joined text field for a rectangle, a dropdown
 * with nothing in it for a precision, a duration that could not say "4h30m".
 *
 * <p>This class is that second set replaced by a real one, stated at the level all three callers share: a
 * {@link ValueType} plus the text it currently holds, in, and a {@link Node} plus a reader out. Where an
 * editor needs the screen or the project it takes them from {@link Context}, never from a service locator.
 *
 * <p><b>Reading is total and never validates.</b> A half-typed duration, a number past its bound, a template
 * that has since been deleted: every one of them is handed back as typed and pulled into range downstream by
 * {@link ValueWire}. Nothing here can refuse a value, so nothing here can leave a dialog unable to close
 * because of a limit somebody tightened afterwards.
 *
 * <p><b>Dispatch is on the type's {@linkplain ValueType#id() id}, and every arm has a fallback.</b> The
 * vocabulary is an open registry now (plugin-platform phase 10a), so there is no enum left to switch
 * exhaustively over and a type nothing here recognises — including one no plugin registered at all — gets
 * the plain text field rather than nothing.
 *
 * <p><b>A plugin fills that gap, and only that gap</b> (phase 11): every {@code case} below is a host editor,
 * and {@link #fromPlugin} is asked in the {@code default} arm — after all of them, which is the contract's
 * "the host's own editors are consulted first" written as control flow. A plugin's editor is chosen by the
 * <em>Java</em> type, the same predicate that recognises an argument slot in a bot's source, so an editor is
 * written once and appears in both places.
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
    public static Editor editorFor(ValueType type, String wire, Context ctx) {
        String value = wire == null ? "" : wire;
        return switch (type == null ? "" : type.id()) {
            case "YES_NO" -> {
                CheckBox box = new CheckBox();
                box.setSelected(Boolean.parseBoolean(value));
                yield new Editor(box, () -> Boolean.toString(box.isSelected()));
            }
            case "WHOLE_NUMBER" -> number(value, ctx.bounds());
            case "DECIMAL_NUMBER" -> decimal(value, ctx.bounds());
            // DURATION has no arm here any more, and its absence is the point. The four-field control moved
            // to the SDK with the block editor's wait picker on 2026-08-28 (plugin platform, phase 12c), so
            // there is now one duration editor rather than two that had to be kept saying the same thing —
            // and it reaches this window through fromPlugin below, which is where every plugin's editors
            // arrive. Deleting the arm is what lets it: a type this switch answers is a type no plugin is
            // ever offered.
            case "TIME_OF_DAY" -> {
                TimeRow row = new TimeRow(value);
                yield new Editor(row, row::wire);
            }
            case "DATE" -> {
                DatePicker picker = new DatePicker(parseDate(value));
                yield new Editor(picker, () -> picker.getValue() == null ? "" : picker.getValue().toString());
            }
            // PRECISION has no arm here any more, on the same reasoning as DURATION above and with the same
            // thing gained as COLOR below: this window drew the three numbers as a preset dropdown and three
            // bare fields, while a block drew them as a dialog that shows what each of them does — the ΔE
            // swatch strip, the blob drawn to scale, and the readout of what these settings would find in a
            // frozen frame of the game. The SDK's editor is the one with the explanations, and it is now
            // drawn in both places.
            // COLOR has no arm here any more either, on the same reasoning as DURATION above and with one
            // thing gained beyond having a single editor: the row's eyedropper picked off the live screen
            // while the block's picked off a frozen frame of the capture target, so the same value was
            // sampled two different ways and only one of them could report the patch's ΔE spread. The SDK's
            // editor does both — the frozen frame when the project has a capture target, the screen when it
            // does not — which is strictly more than either of these two offered.
            case "DIRECTION" -> {
                DirectionPad pad = new DirectionPad(value);
                yield new Editor(pad, pad::wire);
            }
            case "MOUSE_BUTTON" -> {
                MouseDiagram diagram = new MouseDiagram(value);
                yield new Editor(diagram, diagram::wire);
            }
            case "KEY" -> keySearch(ValueWire.fixedOptions(type), value);
            // IMAGE_TEMPLATE has no arm here any more, on the same reasoning as COLOR and PRECISION above: a
            // named picture is ImageTemplate's concept, so its editor is the SDK's, and it arrives through
            // fromPlugin below. The chip this replaced opened a different dialog from the one a block's slot
            // opened, with its own idea of what a deleted picture looks like.
            case "POINT" -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.POINT, ctx.project());
                yield new Editor(row, row::wire);
            }
            case "SIZE" -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.SIZE, ctx.project());
                yield new Editor(row, row::wire);
            }
            case "RECT" -> {
                GeometryRow row = new GeometryRow(value, GeometryRow.Kind.RECT, ctx.project());
                yield new Editor(row, row::wire);
            }
            case "CHARACTER" -> {
                // One character is the whole value, so the field is one character wide rather than letting
                // somebody type a word that would silently become its first letter.
                TextField field = new TextField(value);
                field.setPrefColumnCount(2);
                yield new Editor(field, () -> text(field));
            }
            default -> {
                Editor contributed = fromPlugin(type, value, ctx);
                if (contributed != null) yield contributed;
                // TEXT, any registered type with no editor of its own, and — read-only — a type nothing
                // registered. The last is why the field can be disabled at all: the host cannot offer a
                // meaningful editor for a value it cannot read, and letting somebody type into it would
                // destroy the stored text of a variable whose plugin is merely absent today.
                TextField field = new TextField(value);
                if (type != null && !type.known()) {
                    field.setEditable(false);
                    field.setTooltip(new Tooltip("No plugin installed knows the type \"" + type.id()
                                                 + "\". The stored value is kept as it is."));
                }
                yield new Editor(field, () -> text(field));
            }
        };
    }

    /**
     * The first plugin-contributed editor that claims {@code type}, or null when none does.
     *
     * <p><b>It is consulted last, and that placement is the contract's rule made real.</b>
     * {@link com.botmaker.plugin.api.SlotEditor} documents that the host's own editors come first; every arm
     * above this one is a host editor, so "the {@code default} arm" and "after everything built in" are the
     * same statement. A plugin therefore cannot take the duration field away from the SDK's own
     * {@code DURATION} — it can only supply an editor for a type nothing here has one for, which is every type
     * a plugin is entitled to introduce.
     *
     * <p><b>One editor serves this window and a slot in the source.</b> The predicate is written against a
     * {@link com.botmaker.plugin.api.TypeRef} — the Java type — so the same {@code matches} that recognises a
     * {@code com.acme.Channel} argument in a bot's source recognises a variable of that type here.
     * {@link HostValueContext#typeRef} is the translation, and it is the whole of the bridge.
     *
     * <p>The context holds the value: an editor writes through {@code set(…)} and the returned {@code read}
     * asks the context rather than the widget, which is what lets a plugin build any node it likes without
     * telling the host how to read it back.
     */
    private static Editor fromPlugin(ValueType type, String wire, Context ctx) {
        List<SlotEditor> editors = PluginHost.slotEditors();
        if (editors.isEmpty()) return null;
        HostValueContext context = HostValueContext.of(type, List.of(wire == null ? "" : wire),
                HostServices.forProject(ctx.project()), null);
        for (SlotEditor editor : editors) {
            try {
                if (!editor.matches(context)) continue;
                Node node = editor.create(context);
                if (node != null) return new Editor(node, context::single);
            } catch (RuntimeException | LinkageError e) {
                // A plugin's editor is third-party code drawn inside our dialog: one that throws must cost the
                // user that row's widget, never the window. The next editor is offered the value, and the
                // built-in text field is still behind them all.
                System.err.println("Plugin slot editor failed for type " + (type == null ? "?" : type.id())
                                   + ": " + e);
            }
        }
        return null;
    }

    /**
     * The picture a plugin draws beside one declared choice of its own type, or {@code null}.
     *
     * <p>The third place this window shows a value, and the last one the host answered for a plugin's type.
     * {@link SlotEditor#preview} is asked with a context that is deliberately inert — {@code set} goes
     * nowhere, because a declared choice is a value being <em>listed</em>, not one being edited — and its
     * default is {@code null}, so a type whose editor does not implement it lists as plain text exactly as
     * before.
     */
    private static Node previewFromPlugin(ValueType type, String wire, Context ctx) {
        List<SlotEditor> editors = PluginHost.slotEditors();
        if (editors.isEmpty()) return null;
        HostValueContext context = HostValueContext.of(type, List.of(wire == null ? "" : wire),
                HostServices.forProject(ctx.project()), null);
        for (SlotEditor editor : editors) {
            try {
                if (!editor.matches(context)) continue;
                Node node = editor.preview(context);
                if (node != null) return node;
            } catch (RuntimeException | LinkageError e) {
                // Same rule as fromPlugin: a plugin's node is third-party code drawn inside our dialog, and
                // one that throws costs this option its picture, never the window.
                System.err.println("Plugin preview failed for type " + (type == null ? "?" : type.id())
                                   + ": " + e);
            }
        }
        return null;
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
    static Node optionGraphic(ValueType type, String wire, Context ctx) {
        String value = wire == null ? "" : wire.trim();
        if (value.isEmpty()) return null;
        return switch (type == null ? "" : type.id()) {
            // IMAGE_TEMPLATE has no arm here either. It was the third and last place the host answered for
            // this type, and the one the contract had no hook for — SlotEditor.preview is that hook, and the
            // SDK's TemplateEditors draws the tile now. A name that no longer resolves still keeps the plain
            // label, because the plugin's preview answers null for it, exactly as this arm did.
            case "COLOR" -> {
                Region swatch = new Region();
                swatch.setPrefSize(14, 14);
                swatch.setMinSize(14, 14);
                swatch.setBackground(new Background(new BackgroundFill(parseColor(value), null, null)));
                swatch.getStyleClass().add("option-color-swatch");
                yield swatch;
            }
            case "DURATION" -> {
                Label spelled = new Label(WireText.spellDuration(WireText.duration(value).toMillis()));
                spelled.getStyleClass().add("dialog-hint-text");
                yield spelled;
            }
            case "DIRECTION" -> {
                String arrow = DirectionPad.CELLS.stream()
                        .filter(cell -> cell.name().equals(value))
                        .map(DirectionPad.Cell::arrow)
                        .findFirst().orElse(null);
                yield arrow == null ? null : new Label(arrow);
            }
            // Every type the host does not answer itself, which since IMAGE_TEMPLATE left is every plugin's.
            default -> previewFromPlugin(type, value, ctx);
        };
    }

    // templateFile went with TemplateChip and the IMAGE_TEMPLATE arms. One thing it knew is worth carrying,
    // because it was a real bug once: the lookup has to go through the images directory, not through the
    // project-relative string that gets *stored*. Handing that string to Path.of resolves it against
    // Studio's own working directory, finds nothing, and draws every template as a bare name with no
    // picture. The SDK's TemplateEditors resolves through TemplateLibrary.fileForName for the same reason.

    // --- numbers --------------------------------------------------------------------------------------------

    /**
     * A <em>whole</em> number as a {@link Spinner}, with whichever of the two bounds was declared and the
     * type's own limit standing in for the other.
     *
     * <p>Both ends being independent is the fix: "at most 10, no minimum" is a sentence a person says, and it
     * used to produce a bare text field because the old branch asked for a complete range before it would
     * show a spinner at all. There is no step to declare: one is what a whole number steps by, and it is the
     * truth rather than a guess — which is precisely why a decimal is {@link #decimal a field instead}.
     *
     */
    private static Editor number(String wire, Bounds bounds) {
        double min = number(bounds.min(), Integer.MIN_VALUE);
        double max = number(bounds.max(), Integer.MAX_VALUE);
        if (max < min) max = min;
        double current = Math.max(min, Math.min(max, number(wire, Math.max(min, Math.min(max, 0)))));

        Spinner<Double> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(min, max, current, 1));
        spinner.setEditable(true);
        // The factory is a double one because the range arithmetic above is; a whole number still has to
        // read as a whole number, or a bounded int shows "3.0" in its own editor.
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
        if (!bounds.isEmpty()) spinner.setTooltip(new Tooltip(rangeText(bounds)));
        return new Editor(spinner, () -> text(spinner.getEditor()));
    }

    /**
     * A decimal as a plain field — deliberately <em>not</em> a spinner.
     *
     * <p>A decimal has no natural step. A tenth is right for a threshold, wrong for a scale factor and absurd
     * for a delay in seconds, and arrows on the control promise there is one. Worse, the spinner's editor
     * committed through a locale-aware converter, so on a French system the {@code 1.5} that was typed came
     * back as {@code 1} — the reported "decimals are refused". This field is the wire format and nothing else:
     * it accepts an optional sign, digits, and one separator, and reads back with the separator normalised to
     * a dot, because {@code 1,5} is what a French keyboard produces and {@code 1.5} is what the generated
     * source must say.
     *
     * <p>The declared range is a tooltip, not a clamp, for the reason the class javadoc gives: reading is
     * total, and {@link ValueWire} pulls the value into range downstream where a limit can be tightened
     * afterwards without sealing a dialog shut.
     */
    private static Editor decimal(String wire, Bounds bounds) {
        TextField field = new TextField(wire == null ? "" : wire.trim());
        field.setPromptText("0.0");
        // A filter rather than a validator: the character that cannot be part of a number never arrives, so
        // there is no state in which the field holds something it has to refuse on the way out.
        field.setTextFormatter(new TextFormatter<>(change ->
                isDecimalSoFar(change.getControlNewText()) ? change : null));
        if (!bounds.isEmpty()) field.setTooltip(new Tooltip(rangeText(bounds)));
        return new Editor(field, () -> decimalWire(text(field)));
    }

    /** Everything a decimal can look like <em>while being typed</em>, including the empty and half-typed forms. */
    private static final Pattern DECIMAL_SO_FAR = Pattern.compile("[+-]?\\d*([.,]\\d*)?");

    /** Whether {@code text} could still become a decimal — a lone {@code -}, a trailing {@code .}, and empty all can. */
    static boolean isDecimalSoFar(String text) {
        return DECIMAL_SO_FAR.matcher(text == null ? "" : text).matches();
    }

    /**
     * What a decimal field stores: the separator as a dot, whatever the keyboard produced. A French keyboard's
     * numeric pad types {@code ,} and the generated source has to say {@code 1.5}.
     */
    static String decimalWire(String typed) {
        return typed == null ? "" : typed.trim().replace(',', '.');
    }

    // --- keys -----------------------------------------------------------------------------------------------

    /**
     * The one enum that keeps a dropdown, because a hundred key names is a list rather than a form — and the
     * dropdown you can type into, which is what it always claimed to be. The old comment said the box was
     * editable so a name could be typed; the code never called {@code setEditable}, so the only way to reach
     * {@code VK_SEMICOLON} was to scroll past ninety of its neighbours.
     *
     * <p>The list narrows on a {@link FilteredList} rather than by refilling the items, because refilling
     * makes the ComboBox re-derive its editor text from the selection and eats the letters as they are typed.
     * The predicate always keeps the current selection visible for the same reason: an item filtered out from
     * under the selection clears it, and a cleared selection blanks the field.
     *
     * <p>Reading back prefers what was <em>typed</em> when it names a key exactly (case-insensitively, so
     * "esc" finds {@code ESCAPE}); anything else falls back to the last item actually chosen, and a name that
     * matches nothing reads as blank rather than as itself — {@link ValueWire} is the authority on what a
     * key may be, and handing it half a name to normalise is how a typo became a stored value.
     */
    private static Editor keySearch(List<String> names, String value) {
        ObservableList<String> all = FXCollections.observableArrayList(names);
        FilteredList<String> shown = new FilteredList<>(all, key -> true);

        ComboBox<String> box = new ComboBox<>();
        box.setItems(shown);
        box.setEditable(true);
        box.setVisibleRowCount(12);
        box.setPromptText("Type to search…");
        if (names.contains(value)) box.setValue(value);

        box.getEditor().textProperty().addListener((o, was, is) -> {
            String needle = is == null ? "" : is.trim().toUpperCase(Locale.ROOT);
            String chosen = box.getValue();
            shown.setPredicate(key -> needle.isEmpty()
                    || key.toUpperCase(Locale.ROOT).contains(needle)
                    || key.equals(chosen));
            // Only while the user is actually narrowing: showing the popup on the programmatic text change
            // that follows a pick would reopen the list the pick just closed.
            if (!needle.isEmpty() && !needle.equalsIgnoreCase(chosen) && !box.isShowing()) box.show();
        });

        return new Editor(box, () -> keyWire(names, text(box.getEditor()), box.getValue()));
    }

    /**
     * What a key box stores: the constant {@code typed} names, else the one last picked, else nothing.
     *
     * <p>Nothing rather than the text itself, because a half-typed name is not a key and {@link ValueWire}
     * is the authority on what may be one — handing it {@code "esca"} to normalise is how a typo became a
     * stored value.
     */
    static String keyWire(List<String> names, String typed, String chosen) {
        for (String key : names) {
            if (key.equalsIgnoreCase(typed)) return key;
        }
        return chosen == null ? "" : chosen;
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

    // PrecisionRow is gone, with the arm above. It showed the three numbers as a preset dropdown and three
    // fields, which is the shape a record's components suggest and not the shape the question has: each of
    // the three fails silently on its own, and a number typed into a box says nothing about what it will let
    // through. The SDK's editor is a dialog that shows each of them instead, and it is now what this window
    // draws too.

    // --- colour ---------------------------------------------------------------------------------------------

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
            List<String> known = ValueWire.fixedOptions(ValueWire.type("DIRECTION"));

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
            List<String> known = ValueWire.fixedOptions(ValueWire.type("MOUSE_BUTTON"));
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

    // --- image template: gone, and worth saying where -----------------------------------------------------
    //
    // TemplateChip drew a picture and a name here while a block's slot drew its own, through a different
    // dialog. Both are the SDK's TemplateEditors now, and the argument the chip was built on survives intact
    // in it: the thumbnail is the whole reason this is not a dropdown, because a template's name is something
    // the person who imported it chose, often in a hurry and often for a batch of twenty, and the picture is
    // what says whether this is the right one.

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
