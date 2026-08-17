package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.TemplateManifest;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        return type != null && type.is(SdkType.IMAGE_TEMPLATE);
    }

    /** Builds the picker control bound to {@code templateArg} (a {@code new ImageTemplate("…")} expression). */
    public static Node create(CodeEditorService context, ExpressionBlock templateArg) {
        ProjectConfig config = context.getConfig();
        MenuButton button = new MenuButton();
        button.getStyleClass().add("image-template-picker");
        refreshPickerLabel(button, config, currentTemplatePath(templateArg));

        button.setOnShowing(e -> {
            button.getItems().clear();
            button.getItems().addAll(templateMenuItems(config,
                    file -> applyTemplate(context, templateArg, ImageTemplateLibrary.pathFor(config, file))));
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

    /**
     * The saved templates as menu entries, one submenu per tag — the "folders" the tags stand in for. A
     * project with no tags at all gets a flat list, exactly as before tags existed, so organisation costs
     * nothing until it is used; untagged templates in a tagged project sit under
     * {@link TemplateManifest#UNTAGGED} rather than being hidden or floated to the top level.
     *
     * <p>Shared with the group picker so both menus group identically.
     */
    public static List<MenuItem> templateMenuItems(ProjectConfig config, java.util.function.Consumer<Path> onPick) {
        return templateMenuItems(config, file -> true, onPick);
    }

    /**
     * As {@link #templateMenuItems(ProjectConfig, java.util.function.Consumer)}, but offering only the
     * templates {@code filter} accepts — the group picker narrows to what an enclosing
     * {@code ImageTemplateGroup} allows. A tag left with nothing to show is dropped rather than rendered
     * empty.
     */
    public static List<MenuItem> templateMenuItems(ProjectConfig config, java.util.function.Predicate<Path> filter,
                                                   java.util.function.Consumer<Path> onPick) {
        Map<String, List<Path>> byTag = ImageTemplateLibrary.listByTag(config);
        List<MenuItem> items = new ArrayList<>();
        boolean flat = byTag.size() <= 1;
        for (Map.Entry<String, List<Path>> group : byTag.entrySet()) {
            List<MenuItem> children = new ArrayList<>();
            for (Path file : group.getValue()) {
                if (!filter.test(file)) continue;
                MenuItem item = new MenuItem(ImageTemplateLibrary.baseName(file), thumbnail(file, 18));
                item.setOnAction(a -> onPick.accept(file));
                children.add(item);
            }
            if (children.isEmpty()) {
                continue;
            }
            if (flat) {
                items.addAll(children);
            } else {
                Menu submenu = new Menu(group.getKey());
                submenu.getItems().addAll(children);
                items.add(submenu);
            }
        }
        return items;
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
     * Prompts for a template name, re-prompting until it is non-blank <em>and</em> unique (case-insensitive),
     * or the user cancels. The name field starts empty (no default). {@code allowExisting} — when non-null —
     * is the current name a rename may keep; pass {@code null} for a fresh capture. The returned name is
     * already sanitized to {@code [A-Za-z0-9_-]}. This is the rename path: it offers no tags, because a
     * rename is not the moment to re-file something.
     */
    public static Optional<String> promptTemplateName(Window owner, ProjectConfig config, String allowExisting) {
        return prompt(owner, config, allowExisting, null, null).map(NamedCapture::name);
    }

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
        return prompt(owner, config, null, preview, tags);
    }

    private static Optional<NamedCapture> prompt(Window owner, ProjectConfig config, String allowExisting,
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
            if (!name.equalsIgnoreCase(allowExisting) && ImageTemplateLibrary.exists(config, name)) {
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
