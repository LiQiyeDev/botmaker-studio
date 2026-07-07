package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.project.capture.CaptureTarget;
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
 * expression to a fully-qualified {@code BotConfig} helper call ({@code defaultSource()} / {@code screen(i)}
 * / {@code window("t")}) via {@link com.botmaker.studio.parser.CodeEditor#replaceWithRawExpression}.
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
     * The fully-qualified {@code BotConfig} helper call for {@code sel}. Emitting any of these makes the bot
     * reference {@code BotConfig}, so we lazily materialize {@code BotConfig.java} here (it is not written
     * proactively on project open — only when a block actually uses it).
     */
    private static String sourceCode(CodeEditorService context, com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection sel) {
        ProjectSettingsService.forProject(context).ensureBotConfig();
        String botConfig = "com." + context.getConfig().packageName() + ".BotConfig";
        return switch (sel) {
            case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.ProjectDefault ignored ->
                    botConfig + ".defaultSource()";
            case com.botmaker.studio.ui.app.capture.CaptureSourcePicker.Selection.Concrete c -> switch (c.target()) {
                case CaptureTarget.ScreenTarget st -> botConfig + ".screen(" + st.index() + ")";
                case CaptureTarget.WindowTarget wt -> botConfig + ".window(\""
                        + wt.titleSubstring().replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
            };
        };
    }

    private static Expression expr(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }

    /** A friendly label for the current source expression (BotConfig helper call), else the raw text. */
    private static String label(ExpressionBlock arg) {
        String raw = expr(arg).toString().trim();
        if (raw.isBlank()) return "🎯 Choose source…";
        if (raw.endsWith("defaultSource()")) return "🎯 Project default";
        int screen = raw.indexOf(".screen(");
        if (screen >= 0) {
            String idx = raw.substring(screen + 8, raw.indexOf(')', screen));
            try { return "🎯 Screen " + (Integer.parseInt(idx.trim()) + 1); } catch (NumberFormatException ignored) {}
        }
        int win = raw.indexOf(".window(\"");
        if (win >= 0) {
            int start = win + 9;
            int end = raw.indexOf('"', start);
            if (end > start) return "🎯 " + raw.substring(start, end);
        }
        return "🎯 " + raw;
    }
}
