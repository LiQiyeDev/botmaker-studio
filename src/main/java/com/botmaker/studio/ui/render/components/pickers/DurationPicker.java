package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.helpers.SdkNodes;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.util.Locale;

/**
 * Editor for a {@code java.time.Duration} argument — a value, a unit, and the "random range" toggle that is
 * the reason a wait is typed at all.
 *
 * <p>A wait is the one argument in the SDK whose <em>unit</em> is invisible in the number: {@code 2} is two
 * seconds or two milliseconds depending on which method it was typed into, a thousandfold difference that
 * reads identically. So the control shows the unit next to the number rather than leaving it in the method
 * name, and lets the unit be changed without retyping the value.
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

    /** The units {@code java.time.Duration}'s factories offer, in the order the dropdown lists them. */
    enum Unit {
        MS("ms", "ofMillis", 1), SECONDS("s", "ofSeconds", 1_000), MINUTES("min", "ofMinutes", 60_000);

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
            if (unit == Unit.MINUTES && ms % 60_000 == 0) return factory(Unit.MINUTES, ms / 60_000);
            if (unit != Unit.MS && ms % 1_000 == 0) return factory(Unit.SECONDS, ms / 1_000);
            return factory(Unit.MS, ms);
        }

        long millis() {
            return Math.round(amount * unit.millis);
        }

        String label() {
            return number(amount) + " " + unit.label;
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

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("duration-picker");
        button.setText(label(arg));
        button.setOnAction(e -> {
            Window owner = button.getScene() == null ? null : button.getScene().getWindow();
            Expression slot = expr(arg);
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
    private static String label(ExpressionBlock arg) {
        Value value = parse(expr(arg));
        if (value != null) return value.label();
        String raw = expr(arg).toString();
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
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Duration");
        dialog.setHeaderText(null);

        TextField from = new TextField(number(current.from().amount()));
        from.setPrefColumnCount(6);
        TextField to = new TextField(number(current.to().amount()));
        to.setPrefColumnCount(6);
        Label dash = new Label("to");
        ComboBox<Unit> fromUnit = unitBox(current.from().unit());
        ComboBox<Unit> toUnit = unitBox(current.to().unit());
        CheckBox range = new CheckBox("Random range");
        range.setSelected(current.range());
        range.setVisible(rangeable);
        range.setManaged(rangeable);
        range.setTooltip(new javafx.scene.control.Tooltip(
                "Wait a different amount within the range each time — a bot that always waits exactly the "
                        + "same is the easiest kind to spot. Writes Wait.between(…)."));

        // Bound rather than merely disabled: the second field is meaningless for a fixed duration, and a
        // greyed-out number still reads as part of the value.
        for (Node hidden : new Node[]{dash, to, toUnit}) {
            hidden.visibleProperty().bind(range.selectedProperty());
            hidden.managedProperty().bind(range.selectedProperty());
        }

        HBox row = new HBox(6, from, fromUnit, dash, to, toUnit);
        row.setAlignment(Pos.CENTER_LEFT);

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

        Value a = new Value(unitOf(fromUnit, current.from().unit()), value(from.getText(), current.from().amount()));
        if (!range.isSelected()) return Span.fixed(a);
        Value b = new Value(unitOf(toUnit, current.to().unit()), value(to.getText(), current.to().amount()));
        // Each end keeps the unit it was typed in — 800ms to 2s is a perfectly readable range now that the
        // two ends are separate arguments — but an inverted one is shown back the way round it reads.
        if (b.millis() < a.millis()) {
            Value swap = a;
            a = b;
            b = swap;
        }
        return b.millis() > a.millis() ? new Span(a, b, true) : Span.fixed(a);
    }

    private static ComboBox<Unit> unitBox(Unit selected) {
        ComboBox<Unit> box = new ComboBox<>();
        box.getItems().addAll(Unit.values());
        box.setValue(selected);
        return box;
    }

    private static Unit unitOf(ComboBox<Unit> box, Unit fallback) {
        return box.getValue() == null ? fallback : box.getValue();
    }

    private static double value(String raw, double fallback) {
        Double parsed = parseNumber(raw);
        return parsed == null || parsed < 0 ? fallback : parsed;
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

    /** {@code 2} for a whole number, {@code 1.5} otherwise — never {@code 2.0} in generated source. */
    private static String number(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v)
                ? Long.toString((long) v)
                : String.format(Locale.ROOT, "%s", v);
    }

    private static Expression expr(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }

    /** The {@link SpecialTypePicker} entry: any {@code Duration}-typed slot. */
    public static SpecialTypePicker asSpecialType() {
        return SpecialTypePicker.of(ctx -> ctx.isType("Duration"),
                ctx -> create(ctx.context(), ctx.arg()));
    }
}
