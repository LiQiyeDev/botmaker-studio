package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DatePicker;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A calendar for a {@code java.time.LocalDate} slot — the fourth {@code java.time} editor, and the one that
 * was missing: a date initializer rendered as the bare {@code LocalDate.parse("2026-08-20")} call it is,
 * leaving the one control every other date field in the world has to the user's memory of the ISO format.
 *
 * <p>It commits {@code LocalDate.of(y, m, d)} — three numbers the compiler checks — rather than the
 * {@code parse} form, whose argument is a string that only fails at run time. It <em>reads</em> both, so a
 * date already written either way opens on the day it names.
 *
 * <p>Like {@link TimeArgPicker}, this exists because {@link EnumPicker} and the generic pill cannot serve a
 * JDK type: the project type index covers the SDK jar and the user's own sources, not {@code java.time}.
 */
public final class DateArgPicker {

    private DateArgPicker() {}

    private static final String FQN = "java.time.LocalDate";

    /** How the button spells a date it recognises — the locale's own medium form, never ISO by accident. */
    private static final DateTimeFormatter SHOWN =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault());

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("date-picker-button");
        button.setText(label(arg));
        button.setOnAction(e -> {
            LocalDate current = currentDate(expr(arg));
            LocalDate picked = showCalendar(button.getScene() == null ? null : button.getScene().getWindow(),
                    current == null ? LocalDate.now() : current);
            if (picked != null) {
                context.getCodeEditor().replaceWithRawExpression(expr(arg),
                        "LocalDate.of(%d, %d, %d)".formatted(
                                picked.getYear(), picked.getMonthValue(), picked.getDayOfMonth()), FQN);
            }
        });
        return button;
    }

    private static LocalDate showCalendar(Window owner, LocalDate current) {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Date");
        dialog.setHeaderText(null);

        DatePicker calendar = new DatePicker(current);
        calendar.setShowWeekNumbers(false);
        calendar.setPadding(new Insets(12));

        dialog.getDialogPane().setContent(calendar);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        return calendar.getValue();
    }

    /** The date shown on the button, or the raw source when the slot holds something else (a variable, now()). */
    private static String label(ExpressionBlock arg) {
        LocalDate date = currentDate(expr(arg));
        if (date != null) return date.format(SHOWN);
        String raw = expr(arg).toString();
        return raw.isBlank() ? "Pick a date…" : raw;
    }

    /**
     * Reads {@code LocalDate.of(y, m, d)} and {@code LocalDate.parse("yyyy-mm-dd")}. Null for anything else —
     * {@code LocalDate.now()}, a variable, arithmetic — which keeps the raw source on the button rather than
     * showing today's date for something that is not today's date.
     */
    static LocalDate currentDate(Expression node) {
        if (!(node instanceof MethodInvocation call)) return null;
        String name = call.getName().getIdentifier();
        if ("parse".equals(name) && call.arguments().size() == 1
                && call.arguments().get(0) instanceof StringLiteral literal) {
            try {
                return LocalDate.parse(literal.getLiteralValue());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (!"of".equals(name) || call.arguments().size() != 3) return null;
        Integer year = intLiteral(call.arguments().get(0).toString());
        Integer month = intLiteral(call.arguments().get(1).toString());
        Integer day = intLiteral(call.arguments().get(2).toString());
        if (year == null || month == null || day == null) return null;
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;   // 31 February compiles; it just isn't a day this control can show
        }
    }

    private static Integer intLiteral(String raw) {
        String s = raw == null ? "" : raw.trim().replace("_", "");
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
