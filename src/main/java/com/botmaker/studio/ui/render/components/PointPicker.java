package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;

/**
 * A control standing in for a {@code Point} argument. Shows the current {@code x, y} and opens a menu to
 * "Pick on screen…" (a magnifier overlay that follows the cursor; click to set) or "Edit values…" (a manual
 * popup). A pick rewrites the backing expression to {@code new Point(x, y)} via
 * {@link com.botmaker.studio.parser.CodeEditor#setPoint}.
 *
 * <p>Used for a method parameter typed {@code Point} (see {@link ArgumentEditors}).
 */
public final class PointPicker {

    private PointPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        MenuButton button = new MenuButton();
        button.getStyleClass().add("point-picker");
        button.setText(label(arg));

        button.setOnShowing(e -> {
            button.getItems().clear();
            MenuItem pick = new MenuItem("Pick on screen…");
            pick.setOnAction(a -> {
                Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
                screenCapture(context).pickPoint(owner, p -> Platform.runLater(() ->
                        context.getCodeEditor().setPoint(expr(arg), p[0], p[1])));
            });
            MenuItem edit = new MenuItem("Edit values…");
            edit.setOnAction(a -> NumberFieldsDialog.show("Point", new String[]{"x", "y"},
                    currentValues(arg), button.getScene() == null ? null : button.getScene().getWindow(),
                    v -> context.getCodeEditor().setPoint(expr(arg), v[0], v[1])));
            button.getItems().addAll(pick, new SeparatorMenuItem(), edit);
        });
        return button;
    }

    /** A capture service bound to this project's settings, so it honors the default capture target. */
    private static ScreenCaptureService screenCapture(CodeEditorService context) {
        return ScreenCaptureService.forProject(context);
    }

    private static Expression expr(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }

    /** {@code x, y} for a {@code new Point(...)}; otherwise the raw expression (e.g. a variable name). */
    private static String label(ExpressionBlock arg) {
        int[] v = currentValues(arg);
        if (v != null) return v[0] + ", " + v[1];
        String raw = expr(arg).toString();
        return raw.isBlank() ? "Choose point…" : raw;
    }

    /** Reads {@code [x,y]} from {@code new Point(x,y)}, defaulting missing args to 0; null if not a Point ctor. */
    private static int[] currentValues(ExpressionBlock arg) {
        if (expr(arg) instanceof ClassInstanceCreation cic) {
            int[] out = new int[2];
            for (int i = 0; i < 2 && i < cic.arguments().size(); i++) {
                out[i] = NumberFieldsDialog.parseInt(cic.arguments().get(i).toString());
            }
            return out;
        }
        return null;
    }
}
