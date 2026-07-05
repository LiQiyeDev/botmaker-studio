package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.util.NativeFileDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.io.File;
import java.util.Optional;

/**
 * Editor for the path argument of {@code Game.launch(...)}: a menu button that opens the OS-native "open
 * file" dialog ("Browse for program…", via {@link NativeFileDialog}) to pick the executable, or a text
 * dialog ("Enter path…") for manual entry. Either writes the chosen path into the backing string literal
 * via {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}. The native dialog is used because
 * the JavaFX {@link FileChooser} can neither show hidden dotfiles nor let the user type a path; when no
 * native tool is available it falls back to the JavaFX {@link FileChooser}.
 *
 * <p>Selected by {@link ArgumentEditors} only for the first argument of {@code Game.launch}, so it never
 * hijacks other {@code String} arguments (the trailing launch-option args use {@link LaunchOptionPicker}).
 */
public final class ExecutablePicker {

    /** Remembered across pickers within a session so browsing starts where the user last looked. */
    private static File lastDirectory;

    private ExecutablePicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        MenuButton button = new MenuButton(label(currentPath(arg)));
        button.getStyleClass().add("executable-picker");

        MenuItem browse = new MenuItem("Browse for program…");
        browse.setOnAction(e -> browse(context, arg, button));

        MenuItem manual = new MenuItem("Enter path…");
        manual.setOnAction(e -> {
            String current = currentPath(arg);
            TextInputDialog dialog = new TextInputDialog(current == null ? "" : current);
            if (button.getScene() != null) dialog.initOwner(button.getScene().getWindow());
            dialog.setTitle("Program path");
            dialog.setHeaderText(null);
            dialog.setContentText("Path or command:");
            Optional<String> result = dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
            result.ifPresent(path -> apply(context, arg, button, path));
        });

        button.getItems().addAll(browse, new SeparatorMenuItem(), manual);
        return button;
    }

    /**
     * Opens the OS-native file dialog on a background thread (native dialogs block), then applies the
     * result on the FX thread — falling back to the JavaFX {@link FileChooser} when no native tool exists.
     */
    private static void browse(CodeEditorService context, ExpressionBlock arg, MenuButton button) {
        Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
        String initialDir = (lastDirectory != null && lastDirectory.isDirectory())
                ? lastDirectory.getAbsolutePath() : null;

        Thread worker = new Thread(() -> {
            NativeFileDialog.Choice choice = NativeFileDialog.chooseProgram(initialDir);
            Platform.runLater(() -> {
                if (!choice.nativeDialogShown()) {
                    browseWithJavaFx(context, arg, button, owner);
                } else {
                    choice.path().ifPresent(path -> {
                        File chosen = new File(path);
                        lastDirectory = chosen.getParentFile();
                        apply(context, arg, button, chosen.getAbsolutePath());
                    });
                }
            });
        }, "native-file-dialog");
        worker.setDaemon(true);
        worker.start();
    }

    /** Fallback picker when no native "open file" dialog is available. */
    private static void browseWithJavaFx(CodeEditorService context, ExpressionBlock arg, MenuButton button, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a program to launch");
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        File chosen = chooser.showOpenDialog(owner);
        if (chosen != null) {
            lastDirectory = chosen.getParentFile();
            apply(context, arg, button, chosen.getAbsolutePath());
        }
    }

    private static void apply(CodeEditorService context, ExpressionBlock arg, MenuButton button, String path) {
        context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), path);
        button.setText(label(path));
    }

    private static String label(String path) {
        if (path == null || path.isBlank()) return "Choose program…";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    private static String currentPath(ExpressionBlock arg) {
        Expression e = exprLiteral(arg);
        return e instanceof StringLiteral lit ? lit.getLiteralValue() : null;
    }

    private static Expression exprLiteral(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
