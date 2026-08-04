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
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The three editors the SDK's {@code Time} facade needs — the clock and calendar behind daily-reset logic
 * ("only farm between 05:30 and 07:00", "the weekly boss is on Sunday").
 *
 * <ul>
 *   <li><b>A {@code LocalTime} slot</b> ({@code Time.isBetween(start, end)}) → a 24-hour clock, committing
 *       {@code LocalTime.of(h, m)}. Hand-writing that is where the off-by-one lives: {@code LocalTime.of(5, 30)}
 *       is half past five, {@code LocalTime.of(5, 3)} is three minutes past — one keystroke apart, and both
 *       compile.</li>
 *   <li><b>A {@code DayOfWeek} slot</b> ({@code Time.isDay(…)}) → the seven days by their display name. This
 *       is a plain enum dropdown in spirit, but {@code java.time} types are not in the project's type index,
 *       so {@link EnumPicker} cannot resolve their constants and would fall through to a text pill.</li>
 *   <li><b>The bare hour arguments</b> of {@code Time.isBetween(int, int)} / {@code isBetweenUtc} → an 0–23
 *       dropdown. These predate the typed overload and are the one place in the facade where a number means
 *       an hour with nothing in the type to say so.</li>
 * </ul>
 */
public final class TimeArgPicker {

    private TimeArgPicker() {}

    private static final String LOCAL_TIME_FQN = "java.time.LocalTime";
    private static final String DAY_OF_WEEK_FQN = "java.time.DayOfWeek";

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

    // --- DayOfWeek ---

    public static Node dayOfWeek(CodeEditorService context, ExpressionBlock arg) {
        ComboBox<DayOfWeek> combo = new ComboBox<>();
        combo.getStyleClass().add("day-of-week-picker");
        combo.getItems().addAll(DayOfWeek.values());
        combo.setValue(currentDay(expr(arg)));
        combo.setStyle("-fx-font-size: 11px;");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(DayOfWeek day) {
                return day == null ? "" : day.getDisplayName(TextStyle.FULL, Locale.getDefault());
            }
            @Override public DayOfWeek fromString(String s) {
                return null;   // not editable
            }
        });
        combo.setOnAction(e -> {
            DayOfWeek day = combo.getValue();
            if (day != null) {
                context.getCodeEditor().replaceWithRawExpression(
                        expr(arg), "DayOfWeek." + day.name(), DAY_OF_WEEK_FQN);
            }
        });
        return combo;
    }

    /** Reads the constant out of {@code DayOfWeek.MONDAY} or a bare {@code MONDAY}; null if it is neither. */
    static DayOfWeek currentDay(Expression node) {
        String text = node.toString();
        int dot = text.lastIndexOf('.');
        String name = (dot >= 0 ? text.substring(dot + 1) : text).trim();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equals(name)) return day;
        }
        return null;
    }

    // --- Bare hour arguments ---

    public static Node hourOfDay(CodeEditorService context, ExpressionBlock arg) {
        ComboBox<Integer> combo = new ComboBox<>();
        combo.getStyleClass().add("hour-picker");
        for (int h = 0; h <= 23; h++) combo.getItems().add(h);
        combo.setValue(intLiteral(expr(arg).toString()));
        combo.setStyle("-fx-font-size: 11px;");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer hour) {
                return hour == null ? "" : String.format(Locale.ROOT, "%02d:00", hour);
            }
            @Override public Integer fromString(String s) {
                return null;   // not editable
            }
        });
        combo.setOnAction(e -> {
            Integer hour = combo.getValue();
            if (hour != null) {
                context.getCodeEditor().replaceLiteralValue(expr(arg), Integer.toString(hour));
            }
        });
        return combo;
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
