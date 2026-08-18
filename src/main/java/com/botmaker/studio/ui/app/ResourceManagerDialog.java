package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.TemplateManifest;
import com.botmaker.studio.sharing.TemplateArchive;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.TagPicklist;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import com.botmaker.studio.ui.render.components.TemplateGalleryDialog;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Manages the project's saved image templates (PNGs under {@code src/main/resources/images}): preview,
 * rename, delete, tag, import/export, and capture a new one by cropping the screen
 * ({@link ScreenCaptureService}). Publishes {@link ResourcesChangedEvent} after any change so open template
 * pickers can refresh.
 *
 * <p>Tagging is offered three ways, because the three questions are different. The chips under the preview
 * re-tag the <em>one</em> template being looked at, where the answer is visible. "Add templates…" works from
 * the tag inwards — the only way to fill a tag that is currently empty, which the grid cannot offer because
 * an empty tag draws nothing to select. "Remove from tag" is the bulk inverse, over the grid's selection.
 * The old "Tags…" button remains the fourth: it <em>replaces</em> a template's whole tag set rather than
 * adding or removing one, which is the wrong operation for all three of the above.
 *
 * <p>The listing itself is {@link TemplateGallery} — the same component a template slot opens as a picker, so
 * "which templates exist and how are they filed" has one rendering rather than a tree here and tag submenus
 * there. Organisation comes from {@link TemplateManifest} rather than from directories (see that class for
 * why the files stay flat): a template with two tags appears under either rail row and is still one file.
 */
public class ResourceManagerDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final EventBus eventBus;
    private final ScreenCaptureService capture;

    private TemplateGallery gallery;
    private final ImageView preview = new ImageView();
    private final FlowPane previewTags = new FlowPane(6, 6);
    private final MenuButton addTagButton = new MenuButton("+ Tag");
    private final Label previewTagsHint = new Label("Select a single template to tag it here.");
    private final Button addToTagButton = new Button("Add templates…");
    private final Button removeFromTagButton = new Button("Remove from tag");
    private final Label statusLabel = new Label();
    private Stage stage;

    public ResourceManagerDialog(Window owner, ProjectConfig config, EventBus eventBus, ScreenCaptureService capture) {
        this.owner = owner;
        this.config = config;
        this.eventBus = eventBus;
        this.capture = capture;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Resource Manager — Image Templates");

        // Multi-select so a tag can be applied to a whole group in one go — the bulk case is why tagging
        // lives here rather than only on the capture dialogs.
        gallery = new TemplateGallery(config, true);
        gallery.setOnSelectionChanged(() -> {
            showPreview(selectedFile());
            refreshTagRow();
        });
        gallery.setOnTagChanged(this::refreshTagActions);

        preview.setPreserveRatio(true);
        VBox previewBox = new VBox(6, new Label("Preview"), preview, tagRow());
        previewBox.setPadding(new Insets(0, 0, 0, 12));
        previewBox.setMinWidth(380);
        // Let the preview grow with the window rather than a fixed 220px box.
        preview.fitWidthProperty().bind(previewBox.widthProperty().subtract(12));
        preview.fitHeightProperty().bind(previewBox.heightProperty().subtract(96));
        VBox.setVgrow(preview, Priority.ALWAYS);

        HBox content = new HBox(8, gallery, previewBox);
        HBox.setHgrow(gallery, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button captureBtn = new Button("Capture new...");
        captureBtn.setOnAction(e -> captureNew());
        Button tagBtn = new Button("Tags...");
        tagBtn.setOnAction(e -> editTags(selectedFiles()));
        addToTagButton.setOnAction(e -> addTemplatesToCurrentTag());
        removeFromTagButton.setOnAction(e -> removeCurrentTagFromSelection());
        Button manageTagsBtn = new Button("Manage tags...");
        manageTagsBtn.setOnAction(e -> manageTags());
        Button renameBtn = new Button("Rename");
        renameBtn.setOnAction(e -> rename(selectedFile()));
        Button deleteBtn = new Button("Delete");
        deleteBtn.setOnAction(e -> delete(selectedFile()));
        Button exportBtn = new Button("Export...");
        exportBtn.setOnAction(e -> export(selectedFiles()));
        Button importBtn = new Button("Import...");
        importBtn.setOnAction(e -> importArchive());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, captureBtn, tagBtn, addToTagButton, removeFromTagButton, manageTagsBtn,
                renameBtn, deleteBtn, exportBtn, importBtn, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, content, statusLabel, buttons);
        root.setPadding(new Insets(16));

        reload();
        refreshTagRow();
        refreshTagActions();
        stage.setScene(ThemedWindows.scene(root, 820, 560));
        stage.show();
    }

    /** Re-reads the library after a change here (or in the tag manager) so the grid and the rail counts agree. */
    private void reload() {
        gallery.reload();
        refreshTagRow();
        refreshTagActions();
    }

    /** The one selected template, or null when nothing — or more than one thing — is selected. */
    private Path selectedFile() {
        List<Path> files = selectedFiles();
        return files.size() == 1 ? files.getFirst() : null;
    }

    /** Every selected template. Ctrl/⌘-click extends the selection; that is the bulk-tagging path. */
    private List<Path> selectedFiles() {
        return gallery.selectedFiles();
    }

    private void showPreview(Path file) {
        if (file == null) { preview.setImage(null); return; }
        try {
            preview.setImage(new Image(file.toUri().toString()));
        } catch (Exception e) {
            preview.setImage(null);
        }
    }

    private void captureNew() {
        stage.setIconified(true); // get the dialog out of the way of the capture overlay
        capture.captureRegion(owner, (img, sourceW, sourceH) -> Platform.runLater(() -> {
            stage.setIconified(false);
            Optional<ImageTemplatePicker.NamedCapture> named =
                    ImageTemplatePicker.promptNewTemplate(stage, config, img, null);
            if (named.isEmpty()) return;
            try {
                ImageTemplateLibrary.saveTemplate(config, img, named.get().name(), sourceW, sourceH, null);
                ImageTemplateLibrary.applyTags(config, Map.of(named.get().name(), named.get().tags()));
                published();
                reload();
            } catch (IOException e) {
                statusLabel.setText("Failed to save: " + e.getMessage());
            }
        }));
    }

    // -------------------------------------------------------------------------
    // Tagging where you are looking
    // -------------------------------------------------------------------------

    /** The row under the preview: the selected template's tags as removable chips, plus one to add. */
    private VBox tagRow() {
        previewTagsHint.getStyleClass().add("template-gallery-empty");
        previewTagsHint.setWrapText(true);
        VBox box = new VBox(6, new Label("Tags"), previewTags, previewTagsHint);
        box.setPadding(new Insets(6, 0, 0, 0));
        return box;
    }

    /**
     * Redraws the chips for the one selected template. With nothing — or more than one thing — selected the
     * chips give way to a hint rather than showing the tags of an arbitrary member of the selection: a chip's
     * ✕ has to mean "off this template", and with two selected it would be ambiguous. Bulk work is the two
     * per-tag buttons.
     */
    private void refreshTagRow() {
        Path file = selectedFile();
        previewTags.getChildren().clear();
        boolean single = file != null;
        previewTags.setVisible(single);
        previewTags.setManaged(single);
        previewTagsHint.setVisible(!single);
        previewTagsHint.setManaged(!single);
        if (!single) return;

        String name = ImageTemplateLibrary.baseName(file);
        List<String> tags = ImageTemplateLibrary.tagCatalog(config)
                .declaredOnly(ImageTemplateLibrary.manifest(config).tagsOf(name));
        for (String tag : tags) previewTags.getChildren().add(tagChip(name, tag));
        previewTags.getChildren().add(addTagMenu(name, tags));
    }

    /** One tag on the previewed template, with the ✕ that takes it off — one click, no dialog. */
    private HBox tagChip(String templateName, String tag) {
        Label label = new Label(tag);
        Button remove = new Button("✕");
        remove.getStyleClass().add("tag-chip-remove");
        remove.setTooltip(new Tooltip("Take \"" + tag + "\" off " + templateName));
        remove.setOnAction(e -> {
            ImageTemplateLibrary.removeTag(config, List.of(templateName), tag);
            published();
            reload();
        });
        HBox chip = new HBox(4, label, remove);
        chip.getStyleClass().add("tag-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** The chips' "+": the declared tags this template does not already carry, plus a way to declare one. */
    private MenuButton addTagMenu(String templateName, List<String> already) {
        addTagButton.getItems().clear();
        Set<String> carried = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        carried.addAll(already);
        for (String tag : ImageTemplateLibrary.tagCatalog(config).names()) {
            if (carried.contains(tag)) continue;
            MenuItem item = new MenuItem(tag);
            item.setOnAction(e -> {
                ImageTemplateLibrary.addTag(config, List.of(templateName), tag);
                published();
                reload();
            });
            addTagButton.getItems().add(item);
        }
        if (!addTagButton.getItems().isEmpty()) addTagButton.getItems().add(new SeparatorMenuItem());
        MenuItem create = new MenuItem("New tag…");
        create.setOnAction(e -> TagPicklist.promptNewTag(stage, config).ifPresent(tag -> {
            ImageTemplateLibrary.declareTag(config, tag);
            ImageTemplateLibrary.addTag(config, List.of(templateName), tag);
            published();
            reload();
        }));
        addTagButton.getItems().add(create);
        return addTagButton;
    }

    /** Names the two per-tag buttons after the tag the rail is on, and disables them where they make no sense. */
    private void refreshTagActions() {
        String tag = gallery.selectedRealTag();
        boolean real = tag != null;
        addToTagButton.setText(real ? "Add templates to \"" + tag + "\"…" : "Add templates…");
        removeFromTagButton.setText(real ? "Remove from \"" + tag + "\"" : "Remove from tag");
        addToTagButton.setDisable(!real);
        removeFromTagButton.setDisable(!real || selectedFiles().isEmpty());
    }

    /**
     * Files templates chosen from the whole library under the tag the rail is on. This is the only way to
     * fill an <em>empty</em> tag: the grid of an empty tag has nothing in it to select, so every other
     * tagging path here starts from a template that is already somewhere else.
     */
    private void addTemplatesToCurrentTag() {
        String tag = gallery.selectedRealTag();
        if (tag == null) return;
        TemplateManifest manifest = ImageTemplateLibrary.manifest(config);
        TemplateGalleryDialog.Options options = TemplateGalleryDialog.Options
                .pickOne("Add templates to \"" + tag + "\"").multi()
                // Offer only what is not already filed here — re-adding is a no-op the user would have to
                // check for themselves.
                .withFilter(file -> !manifest.tagsOf(ImageTemplateLibrary.baseName(file)).contains(tag));
        TemplateGalleryDialog.open(stage, config, options, files -> {
            ImageTemplateLibrary.addTag(config, files.stream().map(ImageTemplateLibrary::baseName).toList(), tag);
            published();
            reload();
            statusLabel.setText("Added " + files.size() + " template(s) to \"" + tag + "\".");
        });
    }

    /** Takes the rail's tag off every selected template. The tag survives, even when it ends up empty. */
    private void removeCurrentTagFromSelection() {
        String tag = gallery.selectedRealTag();
        List<Path> files = selectedFiles();
        if (tag == null || files.isEmpty()) {
            statusLabel.setText("Select the templates to take out of this tag.");
            return;
        }
        ImageTemplateLibrary.removeTag(config, files.stream().map(ImageTemplateLibrary::baseName).toList(), tag);
        published();
        reload();
        statusLabel.setText("Removed \"" + tag + "\" from " + files.size() + " template(s).");
    }

    /**
     * Edits the tags of {@code files}. One file shows its own tags; several show the tags they <em>all</em>
     * share, so saving the picklist applies to every one of them — the bulk operation the gallery's
     * multi-select is for.
     */
    private void editTags(List<Path> files) {
        if (files.isEmpty()) {
            statusLabel.setText("Select one or more templates to tag.");
            return;
        }
        TemplateManifest manifest = ImageTemplateLibrary.manifest(config);
        TreeSet<String> shared = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        shared.addAll(manifest.tagsOf(ImageTemplateLibrary.baseName(files.get(0))));
        for (Path file : files) shared.retainAll(manifest.tagsOf(ImageTemplateLibrary.baseName(file)));

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(stage);
        dialog.setTitle("Tags");
        dialog.setHeaderText(files.size() == 1
                ? "Tags for " + ImageTemplateLibrary.baseName(files.get(0))
                : "Tags for " + files.size() + " templates (only tags they all share are shown)");
        ButtonType ok = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        TagPicklist picklist = new TagPicklist(config);
        picklist.select(shared);
        VBox box = new VBox(8, new Label("Choose the tags to file "
                + (files.size() == 1 ? "this template" : "these templates") + " under."), picklist);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.setResultConverter(bt -> bt);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ok) return;

        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (Path file : files) tags.put(ImageTemplateLibrary.baseName(file), picklist.selected());
        ImageTemplateLibrary.applyTags(config, tags);
        published();
        reload();
    }

    /** Opens the tag manager, and picks up whatever it changed when it closes. */
    private void manageTags() {
        new TagManagerDialog(stage, config, () -> {
            published();
            reload();
        }).show();
    }

    private void rename(Path file) {
        if (file == null) {
            statusLabel.setText("Select a single template to rename.");
            return;
        }
        if (ImageTemplateLibrary.isDefaultTemplate(file)) {
            statusLabel.setText("The default template can't be renamed.");
            return;
        }
        Optional<String> name = ImageTemplatePicker.promptTemplateName(stage, config, ImageTemplateLibrary.baseName(file));
        if (name.isEmpty()) return;
        try {
            ImageTemplateLibrary.renameTemplate(config, file, name.get());
            published();
            reload();
        } catch (IOException e) {
            statusLabel.setText("Failed to rename: " + e.getMessage());
        }
    }

    private void delete(Path file) {
        if (file == null) {
            statusLabel.setText("Select a single template to delete.");
            return;
        }
        if (ImageTemplateLibrary.isDefaultTemplate(file)) {
            statusLabel.setText("The default template can't be deleted.");
            return;
        }
        try {
            ImageTemplateLibrary.deleteTemplate(config, file);
            published();
            reload();
        } catch (IOException e) {
            statusLabel.setText("Failed to delete: " + e.getMessage());
        }
    }

    /** Exports the selection (or the whole library when nothing is selected) as a {@code .bmtemplates} file. */
    private void export(List<Path> selection) {
        List<Path> files = selection.isEmpty() ? ImageTemplateLibrary.list(config) : selection;
        if (files.isEmpty()) {
            statusLabel.setText("There are no templates to export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export templates");
        chooser.setInitialFileName(config.projectName() + TemplateArchive.EXTENSION);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("BotMaker templates", "*" + TemplateArchive.EXTENSION));
        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        try {
            TemplateArchive.export(config, files, target.toPath());
            statusLabel.setText("Exported " + files.size() + " template(s) to " + target.getName());
        } catch (IOException e) {
            statusLabel.setText("Failed to export: " + e.getMessage());
        }
    }

    private void importArchive() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import templates");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("BotMaker templates", "*" + TemplateArchive.EXTENSION));
        java.io.File source = chooser.showOpenDialog(stage);
        if (source == null) return;
        try {
            TemplateArchive.ImportResult result = TemplateArchive.importInto(config, source.toPath());
            if (result.count() > 0) eventBus.publish(new ResourcesChangedEvent());
            reload();
            statusLabel.setText(result.summary());
        } catch (IOException e) {
            statusLabel.setText("Failed to import: " + e.getMessage());
        }
    }

    private void published() {
        statusLabel.setText("");
        eventBus.publish(new ResourcesChangedEvent());
    }
}
