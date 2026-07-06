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
 * A control standing in for a {@code Rect} argument. Shows the current {@code x,y,w×h} and opens a menu to
 * "Select on screen…" (drag a region via {@link ScreenCaptureService}) or "Edit values…" (a manual popup).
 * A pick rewrites the backing expression to {@code new Rect(x, y, w, h)} via
 * {@link com.botmaker.studio.parser.CodeEditor#setRect}.
 *
 * <p>Used for a method parameter typed {@code Rect} (see {@link ArgumentEditors}).
 */
public final class RectPicker {

    private RectPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        MenuButton button = new MenuButton();
        button.getStyleClass().add("rect-picker");
        button.setText(label(arg));

        button.setOnShowing(e -> {
            button.getItems().clear();
            MenuItem select = new MenuItem("Select on screen…");
            select.setOnAction(a -> {
                Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
                screenCapture(context).selectRegion(owner, r -> Platform.runLater(() ->
                        context.getCodeEditor().setRect(expr(arg), r[0], r[1], r[2], r[3])));
            });
            MenuItem edit = new MenuItem("Edit values…");
            edit.setOnAction(a -> NumberFieldsDialog.show("Rect", new String[]{"x", "y", "width", "height"},
                    currentValues(arg), button.getScene() == null ? null : button.getScene().getWindow(),
                    v -> context.getCodeEditor().setRect(expr(arg), v[0], v[1], v[2], v[3])));
            button.getItems().addAll(select, new SeparatorMenuItem(), edit);
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

    /** {@code x,y w×h} for a {@code new Rect(...)}; otherwise the raw expression (e.g. a variable name). */
    private static String label(ExpressionBlock arg) {
        int[] v = currentValues(arg);
        if (v != null) return v[0] + ", " + v[1] + "  " + v[2] + "×" + v[3];
        String raw = expr(arg).toString();
        return raw.isBlank() ? "Choose region…" : raw;
    }

    /** Reads {@code [x,y,w,h]} from {@code new Rect(x,y,w,h)}, defaulting missing args to 0; null if not a Rect ctor. */
    private static int[] currentValues(ExpressionBlock arg) {
        if (expr(arg) instanceof ClassInstanceCreation cic) {
            int[] out = new int[4];
            for (int i = 0; i < 4 && i < cic.arguments().size(); i++) {
                out[i] = NumberFieldsDialog.parseInt(cic.arguments().get(i).toString());
            }
            return out;
        }
        return null;
    }
}
