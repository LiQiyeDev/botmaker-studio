package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.ColorPicker;
import javafx.scene.paint.Color;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;

import java.util.List;

/**
 * Editor for a {@code java.awt.Color} argument (e.g. the target of {@code Pixel.matchesAt(x, y, color, tol)} or
 * the {@code low}/{@code high} of {@code Pixel.findInRange}): a JavaFX {@link ColorPicker} swatch that opens the
 * OS colour palette instead of asking the user to hand-write {@code new Color(r, g, b)}. The chosen colour is
 * committed as a fully-qualified {@code new java.awt.Color(r, g, b)} via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression} (fully qualified so no import is needed).
 *
 * <p>Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} for any {@code Color}
 * parameter. When the current value is already a {@code new Color(r, g, b)} literal its RGB seeds the swatch;
 * anything else (a named constant, a variable) leaves the swatch at its default and is overwritten on the first pick.
 */
public final class ColorArgPicker {

    private ColorArgPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        ColorPicker picker = new ColorPicker();
        picker.getStyleClass().add("color-arg-picker");
        Color initial = currentColor(arg);
        if (initial != null) picker.setValue(initial);

        picker.setOnAction(e -> {
            Color c = picker.getValue();
            int r = (int) Math.round(c.getRed() * 255);
            int g = (int) Math.round(c.getGreen() * 255);
            int b = (int) Math.round(c.getBlue() * 255);
            context.getCodeEditor().replaceWithRawExpression(exprNode(arg),
                    "new java.awt.Color(" + r + ", " + g + ", " + b + ")");
        });
        return picker;
    }

    /** The RGB of a {@code new Color(r, g, b)} / {@code new java.awt.Color(r, g, b)} literal, else null. */
    private static Color currentColor(ExpressionBlock arg) {
        if (!(exprNode(arg) instanceof ClassInstanceCreation cic)) return null;
        List<?> args = cic.arguments();
        if (args.size() < 3
                || !(args.get(0) instanceof NumberLiteral rl)
                || !(args.get(1) instanceof NumberLiteral gl)
                || !(args.get(2) instanceof NumberLiteral bl)) {
            return null;
        }
        try {
            return Color.rgb(clamp(rl.getToken()), clamp(gl.getToken()), clamp(bl.getToken()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clamp(String token) {
        return Math.max(0, Math.min(255, Integer.parseInt(token.trim())));
    }

    private static Expression exprNode(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
