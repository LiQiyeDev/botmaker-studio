package com.botmaker.studio.ui.render.components;

import com.botmaker.sdk.api.vision.ImageTemplate;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import com.botmaker.studio.parser.helpers.SdkNodes;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A thumbnail button standing in for an {@code ImageTemplate} expression. Shows the current template (if
 * any) and opens the {@link TemplateGallery} to change it — which is also where "Capture new…" (crop the
 * screen) and "Open Resource Manager…" live, so the slot itself is one button rather than a menu whose first
 * job was listing images it could not show. A pick rewrites the backing expression to
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
        return type != null && type.is(ImageTemplate.class);
    }

    /** Builds the picker control bound to {@code templateArg} (a {@code new ImageTemplate("…")} expression). */
    public static Node create(CodeEditorService context, ValueSlot templateArg) {
        ProjectConfig config = context.getConfig();
        Button button = new Button();
        button.getStyleClass().add("image-template-picker");
        refreshPickerLabel(button, config, currentTemplatePath(templateArg));
        button.setOnAction(e -> TemplateGalleryDialog.open(windowOf(button), config,
                galleryOptions(context, button, "Choose an image"),
                picked -> applyTemplate(context, templateArg,
                        ImageTemplateLibrary.pathFor(config, picked.getFirst()))));
        return button;
    }

    /**
     * The gallery a template slot opens: the whole library, with capture and the resource manager offered
     * inside it. Shared with the group picker so a template is chosen the same way wherever one is needed —
     * the two used to build their own tag submenus from the same list and had already grown apart.
     */
    public static TemplateGalleryDialog.Options galleryOptions(CodeEditorService context, Node anchor,
                                                               String title) {
        return TemplateGalleryDialog.Options.pickOne(title).withActions(
                (owner, onSaved) -> captureAndSave(context, anchor,
                        path -> onSaved.accept(context.getConfig().projectPath().resolve(path))),
                () -> context.getEventBus().publish(new CoreApplicationEvents.OpenResourceManagerEvent()));
    }

    /** The window a control lives in, or null before it is shown — what a dialog wants as its owner. */
    public static Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    /** Reads the current template path from {@code new ImageTemplate("path")}, or null. */
    private static String currentTemplatePath(ValueSlot arg) {
        var n = arg.node();
        if (!(n instanceof ClassInstanceCreation cic) || cic.arguments().isEmpty()) return null;
        String path = SdkNodes.templatePathOf(cic.arguments().getFirst()).orElse(null);
        return path == null || path.isBlank() ? null : path;
    }

    private static void applyTemplate(CodeEditorService context, ValueSlot arg, String path) {
        context.getCodeEditor().setImageTemplate(
                arg.node(), path, defaultWindowTitle(context));
    }

    /**
     * The configured default window's title substring, or {@code null} when the project's default capture
     * target isn't a window — drives whether an {@code ImageFinder.find} pick becomes window-targeted.
     */
    static String defaultWindowTitle(CodeEditorService context) {
        CaptureTargetModel target = ProjectSettingsService.forProject(context).defaultTarget();
        return target == null ? null : target.windowTitle();
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
        String targetTitle = defaultWindowTitle(context);
        String suggestedTag = ImageTemplateLibrary.openActivityTag(config, context.getState());
        screenCapture(context).captureRegion(owner, (img, sourceW, sourceH) -> Platform.runLater(() -> {
            Optional<NamedCapture> named = promptNewTemplate(owner, config, img, suggestedTag);
            if (named.isEmpty()) return;
            try {
                onSaved.accept(ImageTemplateLibrary.saveTemplate(config, img, named.get().name(),
                        sourceW, sourceH, targetTitle));
                ImageTemplateLibrary.applyTags(config, Map.of(named.get().name(), named.get().tags()));
            } catch (IOException ex) {
                Alert error = ThemedWindows.alert(Alert.AlertType.ERROR, "Failed to save template: " + ex.getMessage());
                error.initOwner(owner);
                error.showAndWait();
            }
        }));
    }

    /** A template about to be saved: its validated name and the declared tags chosen for it. */
    public record NamedCapture(String name, List<String> tags) {}

    /**
     * The naming step for a freshly captured template: a thumbnail of {@code preview} so the user sees what
     * they are naming, the name field, and the tag picklist — the single-capture flow had no tag field at
     * all, so a template captured this way could only be filed later, from the resource manager.
     *
     * <p>{@code suggestedTag} is preselected when the project declares it (the open activity's tag, normally);
     * it is a selection over declared tags, never a new one. ARGB (ellipse/object) crops preview with their
     * transparency, via {@link ScreenCaptureService#toFxImage}.
     */
    public static Optional<NamedCapture> promptNewTemplate(Window owner, ProjectConfig config,
                                                           BufferedImage preview, String suggestedTag) {
        TagPicklist tags = new TagPicklist(config);
        if (suggestedTag != null) tags.select(List.of(suggestedTag));
        return prompt(owner, config, preview, tags);
    }

    /**
     * The naming loop. Only ever reached for a <em>new</em> template: renaming is inline in the resource
     * manager, under the picture, where the name that is already taken is on screen next to the field — a
     * dialog that accepts a name and then refuses it was the wrong shape for the one operation whose answer
     * depends on what else the library holds.
     */
    private static Optional<NamedCapture> prompt(Window owner, ProjectConfig config,
                                                 BufferedImage preview, TagPicklist tags) {
        while (true) {
            Dialog<String> dialog = new Dialog<>();
            ThemedWindows.apply(dialog);
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("Template name");
            dialog.setHeaderText(null);
            ButtonType ok = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

            TextField field = new TextField();
            field.setPromptText("template name");

            VBox content = new VBox(10);
            content.setPadding(new Insets(10));
            if (preview != null) {
                ImageView thumb = new ImageView(ScreenCaptureService.toFxImage(preview));
                thumb.setPreserveRatio(true);
                thumb.setFitWidth(180);
                thumb.setFitHeight(140);
                HBox thumbRow = new HBox(thumb);
                thumbRow.setAlignment(Pos.CENTER);
                content.getChildren().add(thumbRow);
            }
            HBox nameRow = new HBox(8, new Label("Name:"), field);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(field, Priority.ALWAYS);
            content.getChildren().add(nameRow);
            if (tags != null) {
                HBox tagRow = new HBox(8, new Label("Tags:"), tags);
                tagRow.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(tags, Priority.ALWAYS);
                content.getChildren().add(tagRow);
            }
            dialog.getDialogPane().setContent(content);
            Platform.runLater(field::requestFocus);
            dialog.setResultConverter(bt -> bt == ok ? field.getText() : null);

            Optional<String> raw = dialog.showAndWait();
            if (raw.isEmpty()) return Optional.empty(); // cancelled
            String name = ImageTemplateLibrary.sanitizeName(raw.get());
            if (name.isBlank()) {
                warn(owner, "Please enter a name for the template.");
                continue;
            }
            if (ImageTemplateLibrary.exists(config, name)) {
                warn(owner, "A template named \"" + name + "\" already exists. Choose a different name.");
                continue;
            }
            if (ImageTemplateLibrary.isReservedName(name)) {
                warn(owner, "\"" + name + "\" is reserved for the template tag list. Choose a different name.");
                continue;
            }
            return Optional.of(new NamedCapture(name, tags == null ? List.of() : tags.selected()));
        }
    }

    private static void warn(Window owner, String message) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING, message);
        alert.initOwner(owner);
        alert.showAndWait();
    }

    /** A capture service bound to this project's settings, so it honors the default capture target. */
    private static ScreenCaptureService screenCapture(CodeEditorService context) {
        return ScreenCaptureService.forProject(context);
    }

    /** Sets the button's label + thumbnail to reflect {@code path} (project-root-relative), or a prompt. */
    private static void refreshPickerLabel(Button button, ProjectConfig config, String path) {
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
