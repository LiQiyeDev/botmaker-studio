package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import com.botmaker.studio.services.CodeEditorService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.util.List;

/**
 * Editor for the instance-name argument of {@code Emulators.use("…")} / {@code Emulators.named("…")}: an
 * editable combo box pre-populated with the emulator instances discovered on this machine
 * ({@link EmulatorInstanceScanner}), so the user picks a running BlueStacks/LDPlayer instance by name rather
 * than typing it blind — while still allowing free-text entry (an instance created after Studio opened, or a
 * name discovery can't see). Commits the chosen/typed text into the backing string literal via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>Selected by {@link com.botmaker.studio.ui.render.components.pickers.PickerRegistry} only for the emulator
 * name argument (see {@code PickerContext.isEmulatorNameArg}), so it never hijacks other {@code String}
 * arguments. Discovery runs off the FX thread (it shells out to {@code reg query} + reads config files).
 */
public final class EmulatorArgPicker {

    private EmulatorArgPicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setEditable(true);
        combo.getStyleClass().add("emulator-arg-picker");
        combo.setPromptText("emulator instance…");
        combo.setVisibleRowCount(8);

        String current = currentValue(arg);
        if (current != null) {
            combo.setValue(current);
        }

        // Populate the dropdown asynchronously so the (registry + file) scan never blocks the FX thread.
        Thread scan = new Thread(() -> {
            List<String> names = new EmulatorInstanceScanner().instanceNames();
            Platform.runLater(() -> {
                combo.getItems().setAll(names);
                String value = combo.getValue();
                if (value != null && !value.isBlank() && !combo.getItems().contains(value)) {
                    combo.getItems().add(value); // keep the current literal selectable even if not discovered
                }
            });
        }, "emulator-instance-scan");
        scan.setDaemon(true);
        scan.start();

        Runnable commit = () -> {
            String newText = combo.getValue();
            if (newText == null) {
                newText = "";
            }
            String oldText = currentValue(arg);
            if (!newText.equals(oldText == null ? "" : oldText)) {
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), newText);
            }
        };
        combo.setOnAction(e -> commit.run());
        combo.getEditor().focusedProperty().addListener((obs, was, focused) -> { if (!focused) commit.run(); });

        return combo;
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
