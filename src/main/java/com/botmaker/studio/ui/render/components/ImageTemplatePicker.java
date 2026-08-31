package com.botmaker.studio.ui.render.components;

import com.botmaker.sdk.api.vision.ImageTemplate;
import com.botmaker.sdk.internal.plugin.capture.TemplateNaming;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.plugin.HostServices;
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
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import com.botmaker.studio.parser.helpers.SdkNodes;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Optional<TemplateNaming.NamedCapture> named = promptNewTemplate(owner, config, img, suggestedTag);
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

    /**
     * The naming step for a freshly captured template — see {@link TemplateNaming#promptNew}, where the
     * dialog and its three refusals now live.
     *
     * <p>It moved on 2026-08-31 with the tag picker it contains: what a picture may be called and what it may
     * be tagged with are both read out of the SDK plugin's own manifest, and neither needs the host beyond
     * knowing which project is open. This overload stays because two of its callers are Studio's own capture
     * flows, which pass a {@link ProjectConfig} rather than a {@code StudioServices}.
     */
    public static Optional<TemplateNaming.NamedCapture> promptNewTemplate(Window owner, ProjectConfig config,
                                                                          BufferedImage preview,
                                                                          String suggestedTag) {
        return TemplateNaming.promptNew(HostServices.forProject(config), owner, preview, suggestedTag);
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
