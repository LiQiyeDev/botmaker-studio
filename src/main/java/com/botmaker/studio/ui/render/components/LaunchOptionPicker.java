package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.util.function.UnaryOperator;

/**
 * Editor for a trailing command-line argument of {@code Game.launch(path, args...)} — i.e. any argument
 * after the program path. Renders a small text field with a "launch option" prompt so the user reads it
 * as an optional flag passed to the program (e.g. {@code --fullscreen}), not as a second program to run.
 * Commits the typed text into the backing string literal (on Enter / focus loss) via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>Selected by {@link ArgumentEditors} only for {@code Game.launch} arguments at index ≥ 1
 * (see {@code PickerContext.isGameLaunchOptionArg}), so the program path (index 0) still uses the
 * {@link ExecutablePicker}.
 */
public final class LaunchOptionPicker {

    private LaunchOptionPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        String current = currentValue(arg);
        TextField field = new TextField(current == null ? "" : current);
        field.getStyleClass().add("launch-option-picker");
        field.setPromptText("launch option (e.g. --fullscreen)");
        field.setPrefColumnCount(14);

        // A command-line token is a plain string: forbid the characters that would break the string literal.
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String added = change.getText();
            return (added.contains("\"") || added.contains("\n") || added.contains("\r")) ? null : change;
        };
        field.setTextFormatter(new TextFormatter<>(filter));

        Runnable commit = () -> {
            String newText = field.getText();
            String oldText = currentValue(arg);
            if (!newText.equals(oldText == null ? "" : oldText)) {
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), newText);
            }
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((obs, was, focused) -> { if (!focused) commit.run(); });

        return field;
    }

    /** The current value if the backing expression is a string literal, else null. */
    private static String currentValue(ExpressionBlock arg) {
        Expression e = exprLiteral(arg);
        return e instanceof StringLiteral lit ? lit.getLiteralValue() : null;
    }

    private static Expression exprLiteral(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
