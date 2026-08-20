package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
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

    /** What {@code LocalDate.now()} is called, on the button and on its own radio button. */
    private static final String TODAY = "Today, whenever the bot runs";

    public static Node create(CodeEditorService context, ValueSlot arg) {
        Button button = new Button();
        button.getStyleClass().add("date-picker-button");
        button.setText(label(arg));
        button.setOnAction(e -> {
            if (arg.node() == null) return;
            String source = showCalendar(button.getScene() == null ? null : button.getScene().getWindow(),
                    currentDate(arg.node()), isNow(arg.node()));
            if (source != null) context.getCodeEditor().replaceWithRawExpression(arg.node(), source, FQN);
        });
        return button;
    }

    /**
     * The calendar, plus the choice the calendar cannot express.
     *
     * <p>{@code LocalDate.now()} is the value a fresh date variable is <em>seeded</em> with, and it was the one
     * value this control could neither show nor produce: the button read back the raw
     * {@code java.time.LocalDate.now()} source, and opening the picker could only replace it with a fixed day.
     * "Today" is not a day on a calendar — it is a different kind of answer — so it gets a radio button of its
     * own rather than a square nobody would recognise.
     *
     * @return the source to write, or null when the dialog was cancelled
     */
    private static String showCalendar(Window owner, LocalDate current, boolean nowSelected) {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Date");
        dialog.setHeaderText(null);

        ToggleGroup which = new ToggleGroup();
        RadioButton today = new RadioButton(TODAY);
        RadioButton fixed = new RadioButton("A fixed date");
        today.setToggleGroup(which);
        fixed.setToggleGroup(which);
        today.setSelected(nowSelected);
        fixed.setSelected(!nowSelected);

        DatePicker calendar = new DatePicker(current == null ? LocalDate.now() : current);
        calendar.setShowWeekNumbers(false);
        calendar.disableProperty().bind(fixed.selectedProperty().not());
        VBox.setMargin(calendar, new Insets(0, 0, 0, 22));

        VBox content = new VBox(8, today, fixed, calendar);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        if (today.isSelected()) return "LocalDate.now()";
        LocalDate picked = calendar.getValue();
        return picked == null ? null : "LocalDate.of(%d, %d, %d)".formatted(
                picked.getYear(), picked.getMonthValue(), picked.getDayOfMonth());
    }

    /** The date shown on the button, or the raw source when the slot holds something else (a variable, a call). */
    private static String label(ValueSlot arg) {
        if (isNow(arg.node())) return TODAY;
        LocalDate date = currentDate(arg.node());
        if (date != null) return date.format(SHOWN);
        String raw = arg.source();
        return raw.isBlank() ? "Pick a date…" : raw;
    }

    /** Whether the slot holds {@code LocalDate.now()} — however the file spells the qualifier. */
    static boolean isNow(Expression node) {
        return node instanceof MethodInvocation call
                && "now".equals(call.getName().getIdentifier())
                && call.arguments().isEmpty();
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
}
