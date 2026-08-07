package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bounded editor for a single {@code BotSettings} setter argument, keeping the value inside the setter's
 * accepted range instead of letting the user type any number:
 * <ul>
 *   <li>{@code setFoundDelay}/{@code setNotFoundDelay} — a millisecond spinner (≥ 0);</li>
 *   <li>{@code setMaxRetryAttempts} — a count spinner (≥ 1);</li>
 *   <li>{@code setDefaultConfidence}/{@code setCompareMargin} — a 0.0–1.0 spinner;</li>
 *   <li>{@code enableRandomClicks}/{@code enableDebugMode} — an inline on/off checkbox.</li>
 * </ul>
 * The numeric editors open a small OK/Cancel dialog and commit the chosen value; the boolean editors write on
 * toggle. Every commit replaces the backing literal via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression}.
 *
 * <p>Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} only for the matching
 * {@code BotSettings} setter (see {@code PickerContext.isBotSettingsArg}), so it never hijacks other numbers.
 */
public final class BotSettingsArgPicker {

    private BotSettingsArgPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg, String methodName) {
        return switch (methodName) {
            case "enableRandomClicks", "enableDebugMode" -> booleanEditor(context, arg, methodName);
            default -> numericEditor(context, arg, methodName);
        };
    }

    // --- boolean setters ---

    private static Node booleanEditor(CodeEditorService context, ExpressionBlock arg, String methodName) {
        CheckBox box = new CheckBox(readableName(methodName));
        box.getStyleClass().add("botsettings-picker");
        box.setSelected(currentBoolean(arg));
        box.setOnAction(e -> context.getCodeEditor()
                .replaceWithRawExpression(exprNode(arg), String.valueOf(box.isSelected())));
        return box;
    }

    // --- numeric setters ---

    private static Node numericEditor(CodeEditorService context, ExpressionBlock arg, String methodName) {
        Button button = new Button();
        button.getStyleClass().add("botsettings-picker");
        button.setText(numericLabel(methodName, arg));
        button.setOnAction(e -> openNumericDialog(context, arg, methodName, button));
        return button;
    }

    private static void openNumericDialog(CodeEditorService context, ExpressionBlock arg, String methodName,
                                          Button button) {
        boolean unitInterval = methodName.equals("setDefaultConfidence")
                || methodName.equals("setCompareMargin");

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.setTitle(readableName(methodName));
        if (button.getScene() != null) dialog.initOwner(button.getScene().getWindow());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Spinner<? extends Number> spinner = unitInterval
                ? unitIntervalSpinner(currentDouble(arg, defaultReal(methodName)), methodName)
                : intSpinner(methodName, currentInt(arg, defaultInt(methodName)));
        spinner.setEditable(true);
        spinner.setPrefWidth(140);

        VBox content = new VBox(6, new Label(prompt(methodName)), spinner);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            commitEditor(spinner);
            String literal = unitInterval
                    ? formatDouble(((Number) spinner.getValue()).doubleValue())
                    : String.valueOf(((Number) spinner.getValue()).intValue());
            context.getCodeEditor().replaceWithRawExpression(exprNode(arg), literal);
            button.setText(numericLabel(methodName, arg));
        });
    }

    private static Spinner<Integer> intSpinner(String methodName, int current) {
        int min = methodName.equals("setMaxRetryAttempts") ? 1 : 0;
        int step = methodName.equals("setMaxRetryAttempts") ? 1 : 50;
        return new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                min, 600_000, Math.max(min, current), step));
    }

    /** A 0.0–1.0 spinner; the margin steps finer than the confidence because it lives near the bottom of that range. */
    private static Spinner<Double> unitIntervalSpinner(double current, String methodName) {
        double step = methodName.equals("setCompareMargin") ? 0.01 : 0.05;
        return new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.0, 1.0, Math.max(0.0, Math.min(1.0, current)), step));
    }

    /** Force a typed-but-not-committed spinner value into the model before we read it. */
    private static <T> void commitEditor(Spinner<T> spinner) {
        String text = spinner.getEditor().getText();
        if (text != null && !text.isBlank()) {
            try {
                SpinnerValueFactory<T> factory = spinner.getValueFactory();
                factory.setValue(factory.getConverter().fromString(text.trim()));
            } catch (RuntimeException ignored) {
                // keep the last valid model value when the text can't be parsed
            }
        }
    }

    // --- labels / prompts ---

    private static String readableName(String methodName) {
        return switch (methodName) {
            case "setFoundDelay" -> "Delay after a match";
            case "setNotFoundDelay" -> "Delay after no match";
            case "setMaxRetryAttempts" -> "Max stuck checks";
            case "setDefaultConfidence" -> "Match confidence";
            case "setCompareMargin" -> "Compare margin";
            case "enableRandomClicks" -> "Randomize click points";
            case "enableDebugMode" -> "Debug logging";
            default -> methodName;
        };
    }

    private static String prompt(String methodName) {
        return switch (methodName) {
            case "setFoundDelay", "setNotFoundDelay" -> "Milliseconds (≥ 0):";
            case "setMaxRetryAttempts" -> "Checks before considered stuck (≥ 1):";
            case "setDefaultConfidence" -> "Confidence (0.0 – 1.0):";
            case "setCompareMargin" -> "How far the right template must beat a look-alike (0.0 – 1.0):";
            default -> "Value:";
        };
    }

    private static String numericLabel(String methodName, ExpressionBlock arg) {
        Expression e = exprNode(arg);
        String value = e instanceof NumberLiteral n ? n.getToken() : "…";
        String unit = (methodName.equals("setFoundDelay") || methodName.equals("setNotFoundDelay")) ? " ms" : "";
        return value + unit;
    }

    private static double defaultReal(String methodName) {
        return methodName.equals("setCompareMargin") ? 0.05 : 0.8;
    }

    private static int defaultInt(String methodName) {
        return switch (methodName) {
            case "setFoundDelay" -> 500;
            case "setNotFoundDelay" -> 200;
            case "setMaxRetryAttempts" -> 20;
            default -> 0;
        };
    }

    /** A compact double literal (drops trailing zeros; {@code 0.8}, {@code 0.85}, {@code 1}). */
    private static String formatDouble(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    // --- current-value readers ---

    private static boolean currentBoolean(ExpressionBlock arg) {
        return exprNode(arg) instanceof BooleanLiteral b && b.booleanValue();
    }

    private static int currentInt(ExpressionBlock arg, int fallback) {
        if (exprNode(arg) instanceof NumberLiteral n) {
            try {
                return (int) Double.parseDouble(n.getToken());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double currentDouble(ExpressionBlock arg, double fallback) {
        if (exprNode(arg) instanceof NumberLiteral n) {
            try {
                return Double.parseDouble(n.getToken());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Expression exprNode(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
