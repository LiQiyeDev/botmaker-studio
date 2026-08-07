package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The three editors the SDK's {@code Time} facade needs — the clock and calendar behind daily-reset logic
 * ("only farm between 05:30 and 07:00", "the weekly boss is on Sunday", "the event runs in December").
 *
 * <ul>
 *   <li><b>A {@code LocalTime} slot</b> ({@code Time.isBetween(start, end)}, {@code isBetweenUtc}) → a
 *       24-hour clock, committing {@code LocalTime.of(h, m)}. Hand-writing that is where the off-by-one
 *       lives: {@code LocalTime.of(5, 30)} is half past five, {@code LocalTime.of(5, 3)} is three minutes
 *       past — one keystroke apart, and both compile.</li>
 *   <li><b>A {@code DayOfWeek} slot</b> ({@code Time.isDay(…)}) → the seven days by their display name.</li>
 *   <li><b>A {@code Month} slot</b> ({@code Time.isMonth(…)}) → the twelve months, likewise.</li>
 * </ul>
 *
 * <p>Every one of them is dispatched on its <em>type</em>. The facade used to carry
 * {@code isBetween(int startHour, int endHour)} overloads whose bare hours could only be reached by a
 * {@code (method, argIndex)} hook — the kind that stops firing the day a facade gains an overload, silently,
 * because nothing tests a picker that merely fails to appear. Those overloads are gone and so is the hook.
 */
public final class TimeArgPicker {

    private TimeArgPicker() {}

    private static final String LOCAL_TIME_FQN = "java.time.LocalTime";
    private static final String DAY_OF_WEEK_FQN = "java.time.DayOfWeek";
    private static final String MONTH_FQN = "java.time.Month";

    // --- LocalTime ---

    public static Node localTime(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("time-picker");
        button.setText(clockLabel(arg));
        button.setOnAction(e -> {
            LocalTime current = currentTime(expr(arg));
            LocalTime picked = showClock(button.getScene() == null ? null : button.getScene().getWindow(),
                    current == null ? LocalTime.of(12, 0) : current);
            if (picked != null) {
                context.getCodeEditor().replaceWithRawExpression(expr(arg),
                        "LocalTime.of(" + picked.getHour() + ", " + picked.getMinute() + ")", LOCAL_TIME_FQN);
            }
        });
        return button;
    }

    private static LocalTime showClock(Window owner, LocalTime current) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Time of day");
        dialog.setHeaderText(null);

        Spinner<Integer> hour = wrappingSpinner(23, current.getHour());
        Spinner<Integer> minute = wrappingSpinner(59, current.getMinute());
        HBox row = new HBox(6, hour, new Label(":"), minute, new Label("(24h)"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12));

        dialog.getDialogPane().setContent(row);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        return LocalTime.of(hour.getValue(), minute.getValue());
    }

    /** Wraps, so stepping down from 00 lands on the top of the range rather than stopping there. */
    private static Spinner<Integer> wrappingSpinner(int max, int value) {
        Spinner<Integer> spinner = new Spinner<>();
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, max, value);
        factory.setWrapAround(true);
        spinner.setValueFactory(factory);
        spinner.setEditable(true);
        spinner.setPrefWidth(70);
        return spinner;
    }

    /** {@code 05:30} for a {@code LocalTime.of(…)}; otherwise the raw source (a constant, a variable). */
    private static String clockLabel(ExpressionBlock arg) {
        LocalTime time = currentTime(expr(arg));
        if (time != null) return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
        String raw = expr(arg).toString();
        return raw.isBlank() ? "Pick a time…" : raw;
    }

    /** Reads {@code LocalTime.of(h, m)} (seconds, if present, are dropped — this control edits to the minute). */
    static LocalTime currentTime(Expression node) {
        if (!(node instanceof MethodInvocation call) || !"of".equals(call.getName().getIdentifier())) return null;
        if (call.arguments().size() < 2) return null;
        Integer hour = intLiteral(call.arguments().get(0).toString());
        Integer minute = intLiteral(call.arguments().get(1).toString());
        if (hour == null || minute == null || hour > 23 || minute > 59) return null;
        return LocalTime.of(hour, minute);
    }

    // --- DayOfWeek and Month ---

    public static Node dayOfWeek(CodeEditorService context, ExpressionBlock arg) {
        return constants(context, arg, DayOfWeek.values(), "DayOfWeek", DAY_OF_WEEK_FQN, "day-of-week-picker",
                day -> day.getDisplayName(TextStyle.FULL, Locale.getDefault()));
    }

    public static Node month(CodeEditorService context, ExpressionBlock arg) {
        return constants(context, arg, Month.values(), "Month", MONTH_FQN, "month-picker",
                month -> month.getDisplayName(TextStyle.FULL, Locale.getDefault()));
    }

    /**
     * A dropdown over a {@code java.time} enum's constants, shown by display name and committed as
     * {@code Type.CONSTANT}. Both callers exist because {@link EnumPicker} cannot serve them: it resolves an
     * enum's constants through the project's type index, which covers the SDK jar and the user's own sources
     * but not the JDK — so a {@code java.time} slot would fall through to a text pill.
     */
    private static <E extends Enum<E>> Node constants(CodeEditorService context, ExpressionBlock arg,
                                                      E[] values, String simpleName, String fqn,
                                                      String styleClass,
                                                      java.util.function.Function<E, String> display) {
        ComboBox<E> combo = new ComboBox<>();
        combo.getStyleClass().add(styleClass);
        combo.getItems().addAll(values);
        combo.setValue(currentConstant(expr(arg), values));
        combo.getStyleClass().add("block-selector");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(E constant) {
                return constant == null ? "" : display.apply(constant);
            }
            @Override public E fromString(String s) {
                return null;   // not editable
            }
        });
        combo.setOnAction(e -> {
            E picked = combo.getValue();
            if (picked != null) {
                context.getCodeEditor().replaceWithRawExpression(
                        expr(arg), simpleName + "." + picked.name(), fqn);
            }
        });
        return combo;
    }

    /** Reads the constant out of {@code DayOfWeek.MONDAY} or a bare {@code MONDAY}; null if it is neither. */
    static DayOfWeek currentDay(Expression node) {
        return currentConstant(node, DayOfWeek.values());
    }

    /** Reads the constant out of {@code Month.JANUARY} or a bare {@code JANUARY}; null if it is neither. */
    static Month currentMonth(Expression node) {
        return currentConstant(node, Month.values());
    }

    /** The named constant a qualified or bare reference names, or null when the slot holds something else. */
    private static <E extends Enum<E>> E currentConstant(Expression node, E[] values) {
        String text = node.toString();
        int dot = text.lastIndexOf('.');
        String name = (dot >= 0 ? text.substring(dot + 1) : text).trim();
        for (E constant : values) {
            if (constant.name().equals(name)) return constant;
        }
        return null;
    }

    private static Integer intLiteral(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("_", "");
        try {
            return s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Expression expr(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
