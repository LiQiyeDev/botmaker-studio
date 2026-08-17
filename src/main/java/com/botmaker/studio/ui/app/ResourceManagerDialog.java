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
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Manages the project's saved image templates (PNGs under {@code src/main/resources/images}): preview,
 * rename, delete, tag, import/export, and capture a new one by cropping the screen
 * ({@link ScreenCaptureService}). Publishes {@link ResourcesChangedEvent} after any change so open template
 * pickers can refresh.
 *
 * <p>Templates are shown as a <em>tag tree</em>. The tree is the only place organisation is visible, and it
 * is built from {@link TemplateManifest} rather than from directories — see that class for why the files
 * stay flat. A template with two tags appears under both branches; selecting either selects the same file.
 */
public class ResourceManagerDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final EventBus eventBus;
    private final ScreenCaptureService capture;

    /** A tree row: a tag branch ({@code file == null}) or a template under it. */
    private record Row(String tag, Path file) {
        @Override public String toString() {
            return file == null ? tag : ImageTemplateLibrary.baseName(file);
        }
    }

    private final TreeView<Row> tree = new TreeView<>();
    private final ImageView preview = new ImageView();
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

        tree.setShowRoot(false);
        // Multi-select so a tag can be applied to a whole group in one go — the bulk case is why tagging
        // lives here rather than only on the capture dialogs.
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(v -> new TreeCell<>() {
            @Override protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.toString());
                setGraphic(item.file() == null ? null : ImageTemplatePicker.thumbnail(item.file(), 18));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, old, sel) ->
                showPreview(sel == null ? null : sel.getValue().file()));

        preview.setPreserveRatio(true);
        VBox previewBox = new VBox(6, new Label("Preview"), preview);
        previewBox.setPadding(new Insets(0, 0, 0, 12));
        previewBox.setMinWidth(380);
        // Let the preview grow with the window rather than a fixed 220px box.
        preview.fitWidthProperty().bind(previewBox.widthProperty().subtract(12));
        preview.fitHeightProperty().bind(previewBox.heightProperty().subtract(28));

        HBox content = new HBox(8, tree, previewBox);
        HBox.setHgrow(tree, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button captureBtn = new Button("Capture new...");
        captureBtn.setOnAction(e -> captureNew());
        Button tagBtn = new Button("Tags...");
        tagBtn.setOnAction(e -> editTags(selectedFiles()));
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
        HBox buttons = new HBox(8, captureBtn, tagBtn, manageTagsBtn, renameBtn, deleteBtn, exportBtn, importBtn, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, content, statusLabel, buttons);
        root.setPadding(new Insets(16));

        reload();
        stage.setScene(ThemedWindows.scene(root, 820, 560));
        stage.show();
    }

    /**
     * Rebuilds the tag tree from disk over the project's declared tags — so a tag with nothing in it is still
     * a branch (that is what declaring it means, and it is where a drag would go), and a template assigned to
     * a tag the project no longer declares surfaces under {@code Untagged} rather than under a ghost.
     *
     * <p>Branches start expanded — the tree is the listing, not a drill-down — except
     * {@link TemplateManifest#ALL}, which would otherwise repeat the entire library above the groups.
     */
    private void reload() {
        TreeItem<Row> root = new TreeItem<>(new Row("", null));
        Map<String, List<Path>> byTag = ImageTemplateLibrary.listByTag(config);
        for (Map.Entry<String, List<Path>> group : byTag.entrySet()) {
            TreeItem<Row> branch = new TreeItem<>(new Row(group.getKey(), null));
            branch.setExpanded(!TemplateManifest.ALL.equals(group.getKey()));
            for (Path file : group.getValue()) branch.getChildren().add(new TreeItem<>(new Row(group.getKey(), file)));
            root.getChildren().add(branch);
        }
        tree.setRoot(root);
    }

    /** The one selected template, or null when a tag branch (or nothing, or several) is selected. */
    private Path selectedFile() {
        List<Path> files = selectedFiles();
        return files.size() == 1 ? files.get(0) : null;
    }

    /**
     * Every template covered by the selection: selected templates, plus every template under a selected tag
     * branch — so "tag this whole group" is one click on the branch rather than a rubber-band over its rows.
     */
    private List<Path> selectedFiles() {
        List<Path> files = new ArrayList<>();
        for (TreeItem<Row> item : tree.getSelectionModel().getSelectedItems()) {
            if (item == null) continue;
            if (item.getValue().file() != null) {
                if (!files.contains(item.getValue().file())) files.add(item.getValue().file());
            } else {
                for (TreeItem<Row> child : item.getChildren()) {
                    if (!files.contains(child.getValue().file())) files.add(child.getValue().file());
                }
            }
        }
        return files;
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

    /**
     * Edits the tags of {@code files}. One file shows its own tags; several show the tags they <em>all</em>
     * share, so saving the field applies to every one of them — the bulk operation the tree's branch
     * selection is for.
     */
    private void editTags(List<Path> files) {
        if (files.isEmpty()) {
            statusLabel.setText("Select a template or a tag to edit tags.");
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
