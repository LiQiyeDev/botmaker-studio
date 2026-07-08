package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.project.capture.CaptureExpr;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;

/**
 * A control standing in for a {@code CaptureSource} (or {@code Window}) argument. Shows the current source
 * and, on click, opens the visual {@link com.botmaker.studio.ui.app.capture.CaptureSourcePicker Steam-style
 * chooser} (screens + windows with live thumbnails, plus "Project default"). A pick rewrites the backing
 * expression to an inline, fully-qualified SDK call ({@code CaptureSource.screen()} / {@code Screen.at(i)} /
 * {@code CaptureSource.window("t")}, from {@link CaptureExpr}) via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression}. "Project default" is resolved to
 * the current default target's inline expression at pick time (a snapshot — there is no generated sidecar).
 *
 * <p>The SDK's {@code CaptureSource} is an <em>interface</em> — it must never be offered a {@code new}
 * constructor; this picker is the only way to fill such a slot. Used for a method parameter typed
 * {@code CaptureSource}/{@code Window} (see {@link ArgumentEditors}).
 */
public final class CaptureSourcePicker {

    private CaptureSourcePicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button(label(arg));
        button.getStyleClass().add("capture-source-picker");
        button.setOnAction(e -> {
            Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
            new com.botmaker.studio.ui.app.capture.CaptureSourcePicker(owner, true).showAndWait()
                    .ifPresent(sel -> {
                        String code = sourceCode(context, sel);
                        context.getCodeEditor().replaceWithRawExpression(expr(arg), code);
                    });
        });
        return button;
    }

    /**
     * The inline, fully-qualified capture-source expression for {@code sel} (see {@link CaptureExpr}). The
     * "Project default" choice is resolved to the project's current default target at pick time; there is no
     * generated {@code BotConfig} sidecar.
     */
    private static String sourceCode(CodeEditorService context, com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection sel) {
        return switch (sel) {
            case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.ProjectDefault ignored ->
                    CaptureExpr.of(ProjectSettingsService.forProject(context).defaultTarget());
            case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.Concrete c ->
                    CaptureExpr.of(c.target(), c.region());
        };
    }

    private static Expression expr(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }

    /** A friendly label for the current inline source expression (see {@link CaptureExpr}), else raw text. */
    private static String label(ExpressionBlock arg) {
        String raw = expr(arg).toString().trim();
        if (raw.isBlank()) return "🎯 Choose source…";
        String suffix = raw.contains(".region(") ? " ▸ region" : "";
        int mon = raw.indexOf("monitor(");
        if (mon >= 0) {
            String idx = raw.substring(mon + 8, raw.indexOf(')', mon));
            try { return "🎯 Screen " + (Integer.parseInt(idx.trim()) + 1) + suffix; } catch (NumberFormatException ignored) {}
        }
        int win = raw.indexOf(".window(\"");
        if (win >= 0) {
            int start = win + 9;
            int end = raw.indexOf('"', start);
            if (end > start) return "🎯 " + raw.substring(start, end) + suffix;
        }
        if (raw.contains("desktop()")) return "🎯 Whole desktop" + suffix;
        return "🎯 " + raw;
    }
}
