package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.helpers.SdkNodes;
import com.botmaker.studio.project.activity.DurationWire;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.DurationFields;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

/**
 * Editor for a {@code java.time.Duration} argument — a length, and the "random range" toggle that is the
 * reason a wait is typed at all.
 *
 * <p>A wait is the one argument in the SDK whose <em>unit</em> is invisible in the number: {@code 2} is two
 * seconds or two milliseconds depending on which method it was typed into, a thousandfold difference that
 * reads identically. So the length is entered through the shared {@link DurationFields} — one box per unit,
 * hours to milliseconds — rather than leaving the unit in the method name. That control is shared with the
 * parameters editor on purpose: the single amount + unit dropdown this used to have could only ever say a
 * multiple of one unit, so four and a half minutes had to be typed as 270 seconds.
 *
 * <p><b>The range is a different call, not a different value.</b> The SDK used to ship its own
 * {@code Duration} record that could itself be a range; it is gone, and the humanized wait is now
 * {@code Wait.between(min, max)}. So ticking "Random range" here rewrites the enclosing <em>statement</em> —
 * {@code Wait.time(x)} becomes {@code Wait.between(x, y)} and back — rather than nesting an expression inside
 * the slot. Each end of a {@code between} then has its own button showing its own length (the range is
 * already legible from the call), while opening either one edits both ends together.
 *
 * <p>Outside a {@code Wait} call — any other slot that happens to take a {@code Duration} — there is no
 * statement to restructure, so the toggle is hidden and only the value is editable.
 *
 * <p>Commits the shortest form that says what was chosen: {@code Duration.ofSeconds(2)} rather than
 * {@code Duration.ofMillis(2000)} when the user typed seconds. {@code java.time.Duration}'s factories take
 * whole numbers, so a fraction is committed in the next unit down ({@code 1.5} seconds →
 * {@code Duration.ofMillis(1500)}) rather than silently truncated to {@code ofSeconds(1)}. Reading is the
 * inverse: the unit shown is the one the source names, so opening and closing the dialog without changing
 * anything is a no-op on the code.
 */
public final class DurationPicker {

    private DurationPicker() {}

    private static final String FQN = "java.time.Duration";

    /** The units {@code java.time.Duration}'s factories offer, smallest first. */
    enum Unit {
        MS("ms", "ofMillis", 1), SECONDS("s", "ofSeconds", 1_000), MINUTES("min", "ofMinutes", 60_000),
        HOURS("h", "ofHours", 3_600_000);

        private final String label;
        private final String factory;
        private final long millis;

        Unit(String label, String factory, long millis) {
            this.label = label;
            this.factory = factory;
            this.millis = millis;
        }

        @Override public String toString() {
            return label;
        }

        static Unit ofFactory(String name) {
            for (Unit u : values()) {
                if (u.factory.equals(name)) return u;
            }
            return null;
        }
    }

    /** One length: how much, in which unit. */
    record Value(Unit unit, double amount) {

        /**
         * The source for this length, in the largest unit that still expresses it exactly. The factories are
         * {@code long}-taking, so 1.5 seconds has to be committed as {@code ofMillis(1500)} — choosing the
         * unit by what divides evenly is what keeps a whole-number value in the unit it was typed in while
         * never rounding a fractional one away.
         */
        String code() {
            long ms = millis();
            // Largest unit that both divides evenly and is no coarser than the one this length was entered
            // in: 4h30m (typed as hours + minutes) commits ofMinutes(270), while a source that already said
            // ofSeconds(120) is left saying seconds rather than being rewritten to ofMinutes(2) on open.
            for (Unit candidate : new Unit[]{Unit.HOURS, Unit.MINUTES, Unit.SECONDS}) {
                if (unit.millis >= candidate.millis && ms % candidate.millis == 0) {
                    return factory(candidate, ms / candidate.millis);
                }
            }
            return factory(Unit.MS, ms);
        }

        /** The same length read off a millisecond total, in the coarsest unit that still says it exactly. */
        static Value ofMillis(long ms) {
            for (Unit candidate : new Unit[]{Unit.HOURS, Unit.MINUTES, Unit.SECONDS}) {
                if (ms != 0 && ms % candidate.millis == 0) return new Value(candidate, ms / candidate.millis);
            }
            return new Value(Unit.MS, ms);
        }

        long millis() {
            return Math.round(amount * unit.millis);
        }

        /**
         * The canonical spelling of the whole length ({@code 4h30m}), not the number and unit it happens to
         * be stored in: {@code Duration.ofMinutes(270)} on a block reads as four and a half hours, which is
         * what it is. The <em>source</em> keeps the unit it was written in — that is {@link #code()}'s job,
         * and the two are deliberately separate now that a length can span several units.
         */
        String label() {
            return DurationWire.format(millis());
        }

        private static String factory(Unit unit, long amount) {
            return "Duration." + unit.factory + "(" + amount + ")";
        }
    }

    /** What the enclosing wait currently says: one length, or two when it is a {@code Wait.between}. */
    record Span(Value from, Value to, boolean range) {

        static Span fixed(Value value) {
            return new Span(value, value, false);
        }
    }

    /** The default a slot with nothing recognisable in it opens on. */
    private static final Value DEFAULT = new Value(Unit.SECONDS, 1);

    public static Node create(CodeEditorService context, ValueSlot arg) {
        Button button = new Button();
        button.getStyleClass().add("duration-picker");
        button.setText(label(arg));
        button.setOnAction(e -> {
            Window owner = button.getScene() == null ? null : button.getScene().getWindow();
            Expression slot = arg.node();
            MethodInvocation call = editableWaitCall(slot);
            Span edited = showDialog(owner, currentSpan(slot, call), call != null);
            if (edited == null) return;
            if (call == null) {
                context.getCodeEditor().replaceWithRawExpression(slot, edited.from().code(), FQN);
            } else {
                context.getCodeEditor().replaceWithRawExpression(call, callCode(edited), FQN);
            }
        });
        return button;
    }

    /** {@code Wait.time(x)} or {@code Wait.between(a, b)} — whichever this whole statement becomes. */
    static String callCode(Span span) {
        return span.range()
                ? "Wait.between(" + span.from().code() + ", " + span.to().code() + ")"
                : "Wait.time(" + span.from().code() + ")";
    }

    /** The current value, or the raw source when the slot holds something else (a variable, a constant). */
    private static String label(ValueSlot arg) {
        Value value = parse(arg.node());
        if (value != null) return value.label();
        String raw = arg.source();
        return raw.isBlank() ? "Choose duration…" : raw;
    }

    /**
     * The enclosing {@code Wait.time}/{@code Wait.between} call, when this control may rewrite it — meaning
     * every one of its arguments is a length this control can show. A {@code between} with a variable for one
     * end is left alone: rewriting the call would discard that end, and preserving it is worth more than the
     * toggle.
     */
    static MethodInvocation editableWaitCall(Expression slot) {
        if (!(slot.getParent() instanceof MethodInvocation call)) return null;
        if (!SdkNodes.isCallOn(call, SdkType.WAIT)) return null;
        if (!call.arguments().contains(slot)) return null;
        String name = call.getName().getIdentifier();
        int arity = call.arguments().size();
        if (!(("time".equals(name) && arity == 1) || ("between".equals(name) && arity == 2))) return null;
        for (Object argument : call.arguments()) {
            if (parse((Expression) argument) == null) return null;
        }
        return call;
    }

    /** The span the dialog opens on: both ends of a {@code between}, or the one length of everything else. */
    private static Span currentSpan(Expression slot, MethodInvocation call) {
        if (call != null && call.arguments().size() == 2) {
            Value from = parse((Expression) call.arguments().get(0));
            Value to = parse((Expression) call.arguments().get(1));
            return new Span(from, to, true);
        }
        Value value = parse(slot);
        return Span.fixed(value == null ? DEFAULT : value);
    }

    /**
     * Reads {@code Duration.ofMillis(n)} / {@code ofSeconds(n)} / {@code ofMinutes(n)}. Null for anything
     * else — a variable, an arithmetic expression, {@code Duration.ZERO} — which leaves the slot to the
     * generic pill rather than rewriting something this control cannot show.
     */
    static Value parse(Expression node) {
        if (!(node instanceof MethodInvocation call)) return null;
        Unit unit = Unit.ofFactory(call.getName().getIdentifier());
        if (unit == null || call.arguments().size() != 1) return null;
        Double amount = parseNumber(call.arguments().get(0).toString());
        return amount == null ? null : new Value(unit, amount);
    }

    /** Value + unit + range, in one small modal. Returns null when cancelled. */
    private static Span showDialog(Window owner, Span current, boolean rangeable) {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Duration");
        dialog.setHeaderText(null);

        DurationFields from = new DurationFields(current.from().millis());
        DurationFields to = new DurationFields(current.to().millis());
        Label dash = new Label("to");
        CheckBox range = new CheckBox("Random range");
        range.setSelected(current.range());
        range.setVisible(rangeable);
        range.setManaged(rangeable);
        range.setTooltip(new javafx.scene.control.Tooltip(
                "Wait a different amount within the range each time — a bot that always waits exactly the "
                        + "same is the easiest kind to spot. Writes Wait.between(…)."));

        // Bound rather than merely disabled: the second field is meaningless for a fixed duration, and a
        // greyed-out number still reads as part of the value.
        // Four fields per end, so the two ends stack rather than sitting side by side in a row eight boxes
        // wide. Bound rather than merely disabled: the second end is meaningless for a fixed duration, and a
        // greyed-out number still reads as part of the value.
        HBox upper = new HBox(6, dash, to);
        upper.setAlignment(Pos.CENTER_LEFT);
        upper.visibleProperty().bind(range.selectedProperty());
        upper.managedProperty().bind(range.selectedProperty());

        VBox row = new VBox(6, from, upper);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Wait"), 0, 0);
        grid.add(row, 1, 0);
        grid.add(range, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;

        Value a = read(from, current.from());
        if (!range.isSelected()) return Span.fixed(a);
        Value b = read(to, current.to());
        // Each end keeps the unit it was typed in — 800ms to 2s is a perfectly readable range now that the
        // two ends are separate arguments — but an inverted one is shown back the way round it reads.
        if (b.millis() < a.millis()) {
            Value swap = a;
            a = b;
            b = swap;
        }
        return b.millis() > a.millis() ? new Span(a, b, true) : Span.fixed(a);
    }

    /**
     * What one end of the dialog now says. An untouched end is handed back <em>as it was read</em> rather
     * than rebuilt from its millisecond total, so opening the dialog on {@code Duration.ofSeconds(120)} and
     * pressing OK doesn't quietly rewrite the source to {@code ofMinutes(2)}.
     */
    private static Value read(DurationFields fields, Value original) {
        long millis = fields.totalMillis();
        return millis == original.millis() ? original : Value.ofMillis(millis);
    }

    /** Lenient number parse over a literal's source text ({@code 1_500}, {@code 2L}, {@code 1.5}). */
    private static Double parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("_", "").replaceAll("[lLdDfF]$", "");
        try {
            return s.isEmpty() ? null : Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    /** The {@link SpecialTypePicker} entry: any {@code Duration}-typed slot. */
    public static SpecialTypePicker asSpecialType() {
        return SpecialTypePicker.of(ctx -> ctx.isType("Duration"),
                ctx -> create(ctx.context(), ctx.arg()));
    }
}
