package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Editor for the instance-name argument of {@code Emulators.use("…")} / {@code Emulators.named("…")}: a button
 * that opens the full {@link EmulatorPickerDialog} (every configured instance with its brand, a running dot, and —
 * for a running instance — its installed apps), so the user picks a BlueStacks/LDPlayer/MEmu/MuMu/Gameloop
 * instance visually rather than typing its name blind. The chosen instance name is committed into the backing
 * string literal via {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>When the user drills into a specific app inside an emulator, that additionally points the project at it:
 * {@code launch.target = emu-app:<pkg>@<instance>} and {@code capture.source = emulator:<instance>} are written to
 * {@code botmaker-project.properties} (Phase 3 plumbing — {@link ProjectCreator#writeLaunchTarget} /
 * {@link ProjectCreator#writeCaptureSource}), so the generated {@code Startup.run()} launches the app and no-source
 * vision/click calls target the emulator.
 *
 * <p>Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} only for the emulator name
 * argument (see {@code PickerContext.isEmulatorNameArg}), so it never hijacks other {@code String} arguments.
 */
public final class EmulatorArgPicker {

    private EmulatorArgPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("emulator-arg-picker");
        button.setText(label(currentValue(arg)));

        button.setOnAction(e -> {
            Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
            EmulatorPickerDialog.show(owner).ifPresent(sel -> {
                String name = sel.instance().name();
                if (name == null || name.isBlank()) return;
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), name);
                button.setText(label(name));
                if (sel.hasApp()) {
                    pointProjectAtApp(context, name, sel.appPackage());
                }
            });
        });
        return button;
    }

    /**
     * Points the project's launch target + capture source at {@code appPackage} inside {@code instanceName}. Best
     * effort — a write failure (e.g. no project resources dir) is swallowed rather than surfaced as an editor error.
     */
    private static void pointProjectAtApp(CodeEditorService context, String instanceName, String appPackage) {
        if (context.getConfig() == null) return;
        Path resources = context.getConfig().resourcesRoot();
        try {
            ProjectCreator.writeLaunchTarget(resources, "emu-app:" + appPackage + "@" + instanceName);
            ProjectCreator.writeCaptureSource(resources, "emulator:" + instanceName);
        } catch (IOException ignored) {
            // best-effort project-default wiring; the inline Emulators.use(name) call still stands.
        }
    }

    private static String label(String instanceName) {
        return (instanceName == null || instanceName.isBlank())
                ? "Choose emulator…" : "Emulator: " + instanceName;
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
