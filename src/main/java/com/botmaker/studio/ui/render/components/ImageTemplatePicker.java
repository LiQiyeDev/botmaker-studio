package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.types.ResolvedType;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A thumbnail/menu control standing in for an {@code ImageTemplate} expression. Shows the current
 * template (if any) and opens a menu of saved templates plus "Capture new…" (crop the screen) and
 * "Open Resource Manager…". A pick rewrites the backing expression to
 * {@code new ImageTemplate("<project-relative path>")} via {@link com.botmaker.studio.parser.CodeEditor#setImageTemplate}.
 *
 * <p>Used both for a method parameter typed {@code ImageTemplate}
 * ({@link com.botmaker.studio.blocks.func.MethodInvocationBlock}) and for {@code ImageTemplate} elements
 * inside a list/array ({@link com.botmaker.studio.blocks.expr.ListBlock}).
 */
public final class ImageTemplatePicker {

    private ImageTemplatePicker() {}

    /** True when {@code type} is the SDK {@code ImageTemplate} (by simple or qualified name). */
    public static boolean isImageTemplateType(ResolvedType type) {
        return type != null
                && (type.simpleName().equals("ImageTemplate")
                    || type.qualifiedName().endsWith(".ImageTemplate"));
    }

    /** Builds the picker control bound to {@code templateArg} (a {@code new ImageTemplate("…")} expression). */
    public static Node create(CodeEditorService context, ExpressionBlock templateArg) {
        ProjectConfig config = context.getConfig();
        MenuButton button = new MenuButton();
        button.getStyleClass().add("image-template-picker");
        refreshPickerLabel(button, config, currentTemplatePath(templateArg));

        button.setOnShowing(e -> {
            button.getItems().clear();
            for (Path file : ImageTemplateLibrary.list(config)) {
                MenuItem item = new MenuItem(ImageTemplateLibrary.baseName(file), thumbnail(file, 18));
                item.setOnAction(a -> applyTemplate(context, templateArg, ImageTemplateLibrary.pathFor(config, file)));
                button.getItems().add(item);
            }
            if (!button.getItems().isEmpty()) button.getItems().add(new SeparatorMenuItem());
            MenuItem capture = new MenuItem("Capture new…");
            capture.setOnAction(a -> captureNewTemplate(context, templateArg, button));
            MenuItem openManager = new MenuItem("Open Resource Manager…");
            openManager.setOnAction(a ->
                    context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
            button.getItems().addAll(capture, openManager);
        });
        return button;
    }

    /** Reads the current template path from {@code new ImageTemplate("path")}, or null. */
    private static String currentTemplatePath(ExpressionBlock arg) {
        var n = ((AbstractCodeBlock) arg).getAstNode();
        if (n instanceof ClassInstanceCreation cic && !cic.arguments().isEmpty()
                && cic.arguments().get(0) instanceof StringLiteral sl) {
            String v = sl.getLiteralValue();
            return v.isBlank() ? null : v;
        }
        return null;
    }

    private static void applyTemplate(CodeEditorService context, ExpressionBlock arg, String path) {
        context.getCodeEditor().setImageTemplate(
                (Expression) ((AbstractCodeBlock) arg).getAstNode(), path, defaultWindowTitle(context));
    }

    /**
     * The configured default window's title substring, or {@code null} when the project's default capture
     * target isn't a window — drives whether an {@code ImageFinder.find} pick becomes window-targeted.
     */
    static String defaultWindowTitle(CodeEditorService context) {
        CaptureTarget target = ProjectSettingsService.forProject(context).defaultTarget();
        return (target instanceof CaptureTarget.WindowTarget w) ? w.titleSubstring() : null;
    }

    private static void captureNewTemplate(CodeEditorService context, ExpressionBlock arg, Node anchor) {
        captureAndSave(context, anchor, path -> applyTemplate(context, arg, path));
    }

    /**
     * Shared "Capture new…" flow: drag a screen region, prompt for a name, save it under the project's
     * images root, then hand the project-relative path to {@code onSaved} (on the FX thread). Used by both
     * the single {@link ImageTemplatePicker} and the multi-template group picker so both offer capture.
     */
    public static void captureAndSave(CodeEditorService context, Node anchor,
                                      java.util.function.Consumer<String> onSaved) {
        ProjectConfig config = context.getConfig();
        Window owner = anchor.getScene() != null ? anchor.getScene().getWindow() : null;
        screenCapture(context).captureRegion(owner, img -> Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog("accept_button");
            dialog.initOwner(owner);
            dialog.setTitle("Template name");
            dialog.setHeaderText(null);
            dialog.setContentText("Name:");
            Optional<String> name = dialog.showAndWait()
                    .map(s -> s.trim().replaceAll("[^A-Za-z0-9_-]", "_"))
                    .filter(s -> !s.isBlank());
            if (name.isEmpty()) return;
            try {
                onSaved.accept(saveTemplate(config, img, name.get()));
            } catch (IOException ex) {
                Alert error = new Alert(Alert.AlertType.ERROR, "Failed to save template: " + ex.getMessage());
                error.initOwner(owner);
                error.showAndWait();
            }
        }));
    }

    /**
     * Saves {@code img} as {@code <imagesRoot>/<name>.png} and returns its project-root-relative path
     * (the string that goes inside {@code new ImageTemplate("…")}). Shared by "Capture new…" and the
     * multi-argument "Pick all on screen" session. {@code name} must already be sanitized.
     */
    public static String saveTemplate(ProjectConfig config, java.awt.image.BufferedImage img, String name)
            throws IOException {
        Path target = config.imagesRoot().resolve(name + ".png");
        Files.createDirectories(target.getParent());
        new ScreenCaptureService().savePng(img, target);
        return ImageTemplateLibrary.pathFor(config, target);
    }

    /** A capture service bound to this project's settings, so it honors the default capture target. */
    private static ScreenCaptureService screenCapture(CodeEditorService context) {
        return ScreenCaptureService.forProject(context);
    }

    /** Sets the button's label + thumbnail to reflect {@code path} (project-root-relative), or a prompt. */
    private static void refreshPickerLabel(MenuButton button, ProjectConfig config, String path) {
        if (path == null) {
            button.setText("Choose image…");
            button.setGraphic(null);
            return;
        }
        Path file = config.projectPath().resolve(path);
        button.setText(ImageTemplateLibrary.baseName(file));
        button.setGraphic(thumbnail(file, 48));
    }

    /** A small {@link ImageView} of {@code file}, or null if it can't be loaded. Shared with the group picker. */
    public static ImageView thumbnail(Path file, double size) {
        try {
            if (!Files.exists(file)) return null;
            return new ImageView(new Image(file.toUri().toString(), size, size, true, true));
        } catch (Exception e) {
            return null;
        }
    }
}
