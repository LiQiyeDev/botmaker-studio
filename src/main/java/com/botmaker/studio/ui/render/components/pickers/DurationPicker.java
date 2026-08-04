package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
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
 * Editor for a {@code Duration} argument — a value, a unit, and the "random range" toggle that is the reason
 * the SDK types this slot at all.
 *
 * <p>A wait is the one argument in the SDK whose <em>unit</em> is invisible in the number: {@code 2} is two
 * seconds or two milliseconds depending on which method it was typed into, a thousandfold difference that
 * reads identically. So the control shows the unit next to the number rather than leaving it in the method
 * name, and lets the unit be changed without retyping the value.
 *
 * <p>The range toggle is here because humanized delays are the normal case for a bot, not an advanced one: a
 * bot that waits exactly 1000ms between every action is trivially identifiable as a bot. Turning it on emits
 * {@code Duration.between(…)}, which the SDK re-rolls on every use.
 *
 * <p>Commits the shortest form that says what was chosen — {@code Duration.seconds(1.5)} rather than
 * {@code Duration.ms(1500)} when the user typed seconds, and an integer literal when the value has no
 * fraction ({@code Duration.seconds(2)}, not {@code 2.0}). Reading is the inverse: the unit shown is the one
 * the source names, so opening and closing the dialog without changing anything is a no-op on the code.
 */
public final class DurationPicker {

    private DurationPicker() {}

    private static final String FQN = "com.botmaker.sdk.api.interaction.Duration";

    /** The units the SDK's factories offer, in the order the dropdown lists them. */
    enum Unit {
        MS("ms", "ms"), SECONDS("s", "seconds"), MINUTES("min", "minutes");

        private final String label;
        private final String factory;

        Unit(String label, String factory) {
            this.label = label;
            this.factory = factory;
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

    /** What the source currently says: one value, or two when it is a range. */
    record Value(Unit unit, double from, double to, boolean range) {

        static Value fixed(Unit unit, double v) {
            return new Value(unit, v, v, false);
        }

        String code() {
            return range
                    ? "Duration.between(" + one(from) + ", " + one(to) + ")"
                    : one(from);
        }

        private String one(double v) {
            // ms takes a long: a fraction of a millisecond typed there would not compile, so it is rounded
            // rather than passed through. seconds/minutes take a double and keep theirs.
            return "Duration." + unit.factory + "(" + number(unit == Unit.MS ? Math.rint(v) : v) + ")";
        }

        String label() {
            return range ? number(from) + "–" + number(to) + " " + unit.label : number(from) + " " + unit.label;
        }
    }

    /** The default a slot with nothing recognisable in it opens on. */
    private static final Value DEFAULT = Value.fixed(Unit.SECONDS, 1);

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("duration-picker");
        button.setText(label(arg));
        button.setOnAction(e -> {
            Window owner = button.getScene() == null ? null : button.getScene().getWindow();
            Value current = parse(expr(arg));
            Value edited = showDialog(owner, current == null ? DEFAULT : current);
            if (edited != null) {
                context.getCodeEditor().replaceWithRawExpression(expr(arg), edited.code(), FQN);
            }
        });
        return button;
    }

    /** The current value, or the raw source when the slot holds something else (a variable, a constant). */
    private static String label(ExpressionBlock arg) {
        Value value = parse(expr(arg));
        if (value != null) return value.label();
        String raw = expr(arg).toString();
        return raw.isBlank() ? "Choose duration…" : raw;
    }

    /**
     * Reads {@code Duration.ms(n)} / {@code seconds(n)} / {@code minutes(n)} and the {@code between(a, b)}
     * range form. Null for anything else — including a range whose two ends use different units, which the
     * SDK allows but this control has no way to show; leaving it to the generic pill preserves it rather than
     * quietly rewriting one end.
     */
    static Value parse(Expression node) {
        if (!(node instanceof MethodInvocation call)) return null;
        String name = call.getName().getIdentifier();

        if ("between".equals(name) && call.arguments().size() == 2) {
            Value from = parse((Expression) call.arguments().get(0));
            Value to = parse((Expression) call.arguments().get(1));
            if (from == null || to == null || from.range() || to.range() || from.unit() != to.unit()) return null;
            return new Value(from.unit(), from.from(), to.from(), true);
        }

        Unit unit = Unit.ofFactory(name);
        if (unit == null || call.arguments().size() != 1) return null;
        Double amount = parseNumber(call.arguments().get(0).toString());
        return amount == null ? null : Value.fixed(unit, amount);
    }

    /** Value + unit + range, in one small modal. Returns null when cancelled. */
    private static Value showDialog(Window owner, Value current) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Duration");
        dialog.setHeaderText(null);

        TextField from = new TextField(number(current.from()));
        from.setPrefColumnCount(6);
        TextField to = new TextField(number(current.to()));
        to.setPrefColumnCount(6);
        Label dash = new Label("to");
        ComboBox<Unit> unit = new ComboBox<>();
        unit.getItems().addAll(Unit.values());
        unit.setValue(current.unit());
        CheckBox range = new CheckBox("Random range");
        range.setSelected(current.range());
        range.setTooltip(new javafx.scene.control.Tooltip(
                "Wait a different amount within the range each time — a bot that always waits exactly the "
                        + "same is the easiest kind to spot."));

        // Bound rather than merely disabled: the second field is meaningless for a fixed duration, and a
        // greyed-out number still reads as part of the value.
        dash.visibleProperty().bind(range.selectedProperty());
        dash.managedProperty().bind(range.selectedProperty());
        to.visibleProperty().bind(range.selectedProperty());
        to.managedProperty().bind(range.selectedProperty());

        HBox row = new HBox(6, from, dash, to, unit);
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

        double a = value(from.getText(), current.from());
        boolean isRange = range.isSelected();
        double b = isRange ? value(to.getText(), current.to()) : a;
        // The SDK would accept an inverted range (it takes the wider span), but showing back what was typed
        // is clearer than silently swapping the fields.
        if (isRange && b < a) {
            double swap = a;
            a = b;
            b = swap;
        }
        return new Value(unit.getValue() == null ? current.unit() : unit.getValue(), a, b, isRange && b > a);
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
