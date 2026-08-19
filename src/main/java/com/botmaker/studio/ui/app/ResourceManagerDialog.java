package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.TemplateManifest;
import com.botmaker.studio.services.TemplateReferences;
import com.botmaker.studio.sharing.TemplateArchive;
import com.botmaker.studio.ui.app.capture.OverlayTemplateCapture;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import com.botmaker.studio.ui.render.components.TagPicklist;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import com.botmaker.studio.ui.render.components.TemplateGalleryDialog;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Manages the project's saved image templates (PNGs under {@code src/main/resources/images}): preview, rename,
 * replace, delete, tag, import/export, and capture new ones through the same on-screen overlay the toolbar
 * opens. Publishes {@link ResourcesChangedEvent} after any change so open template pickers can refresh.
 *
 * <p><b>Everything that acts on one template lives under its preview</b> — its name, its tags, its picture —
 * because those three are answered by looking at it. The buttons along the bottom are the ones that act on the
 * <em>selection</em> or on the library: capture, the two per-tag bulk actions, delete, import and export. That
 * split is why the old bottom "Tags…" button is gone: it replaced a template's whole tag set from a dialog
 * that showed no picture, duplicating in the worst possible form what the chip row now does one tag at a time
 * with the template in view.
 *
 * <p><b>Renaming and deleting are compile-safe.</b> Both go through {@link TemplateReferences}, which knows
 * every place a template is named in the bot's own source — the generated {@code Templates} constant and the
 * raw path literal alike — so a rename carries its use sites with it and a delete either finds none or offers
 * to point them at another template first. Before that, renaming an old-style template silently broke it at
 * run time and deleting a used one always did.
 *
 * <p>The listing itself is {@link TemplateGallery} — the same component a template slot opens as a picker, so
 * "which templates exist and how are they filed" has one rendering. Organisation comes from
 * {@link TemplateManifest} rather than from directories (see that class for why the files stay flat): a
 * template with two tags appears under either rail row and is still one file.
 */
public class ResourceManagerDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final EventBus eventBus;
    private final ScreenCaptureService capture;
    private final ProjectSettingsService settings;
    private final CodeEditorService editor;

    private TemplateGallery gallery;
    private final ImageView preview = new ImageView();
    private final TextField nameField = new TextField();
    private final Button renameButton = new Button("Rename");
    private final Label nameMessage = new Label();
    private final FlowPane previewTags = new FlowPane(6, 6);
    private final MenuButton addTagButton = new MenuButton("+ Tag");
    private final MenuButton replaceButton = new MenuButton("Replace image…");
    private final VBox singleBox = new VBox(8);
    private final Label previewHint = new Label("Select a single template to rename, tag or replace it.");
    private final Label duplicateNote = new Label();
    private final Button addToTagButton = new Button("Add templates…");
    private final Button removeFromTagButton = new Button("Remove from tag");
    private final Button deleteButton = new Button("Delete");
    private final Label statusLabel = new Label();
    private Stage stage;

    /**
     * Which templates share their picture with which others, as of the last {@link #reload()}. Held for the
     * length of a reload rather than recomputed per selection because it is a decode of every PNG in the
     * project, and the preview asks it on every click.
     */
    private Map<String, List<String>> duplicates = Map.of();

    public ResourceManagerDialog(Window owner, ProjectConfig config, EventBus eventBus,
                                 ScreenCaptureService capture, ProjectSettingsService settings,
                                 CodeEditorService editor) {
        this.owner = owner;
        this.config = config;
        this.eventBus = eventBus;
        this.capture = capture;
        this.settings = settings;
        this.editor = editor;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal(
                        "resource-manager", "Resource Manager — Image Templates", owner)
                .size(880, 620).minSize(700, 460);
        stage = window.stage();

        // Multi-select: plain click toggles a tile, so filing or deleting a group is the same gesture as
        // picking one. Every bulk action below reads gallery.selectedFiles().
        gallery = new TemplateGallery(config, true);
        gallery.setOnSelectionChanged(() -> {
            showPreview(selectedFile());
            refreshSingleBox();
            refreshTagActions();
        });
        gallery.setOnTagChanged(this::refreshTagActions);

        preview.setPreserveRatio(true);
        VBox previewBox = new VBox(6, new Label("Preview"), preview, singlePane());
        previewBox.setPadding(new Insets(0, 0, 0, 12));
        previewBox.setMinWidth(380);
        // Let the preview grow with the window rather than a fixed 220px box.
        preview.fitWidthProperty().bind(previewBox.widthProperty().subtract(12));
        preview.fitHeightProperty().bind(previewBox.heightProperty().subtract(220));
        VBox.setVgrow(preview, Priority.ALWAYS);

        HBox content = new HBox(8, gallery, previewBox);
        HBox.setHgrow(gallery, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button captureBtn = new Button("Capture new...");
        captureBtn.setTooltip(new Tooltip("Opens the capture overlay — capture one, or a batch in a row"));
        captureBtn.setOnAction(e -> captureNew());
        addToTagButton.setOnAction(e -> addTemplatesToCurrentTag());
        removeFromTagButton.setOnAction(e -> removeCurrentTagFromSelection());
        Button manageTagsBtn = new Button("Manage tags...");
        manageTagsBtn.setOnAction(e -> manageTags());
        deleteButton.setOnAction(e -> delete(selectedFiles()));
        Button exportBtn = new Button("Export...");
        exportBtn.setOnAction(e -> export(selectedFiles()));
        Button importBtn = new Button("Import...");
        importBtn.setOnAction(e -> importArchive());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, captureBtn, addToTagButton, removeFromTagButton, manageTagsBtn,
                deleteButton, exportBtn, importBtn, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, content, statusLabel, buttons);
        root.setPadding(new Insets(16));

        reload();
        window.show(root);
        reportMissing();
    }

    /** Re-reads the library after a change here (or in the tag manager) so the grid and the rail counts agree. */
    private void reload() {
        duplicates = ImageTemplateLibrary.duplicatePictures(config);
        gallery.reload();
        refreshSingleBox();
        refreshTagActions();
    }

    /** The one selected template, or null when nothing — or more than one thing — is selected. */
    private Path selectedFile() {
        List<Path> files = selectedFiles();
        return files.size() == 1 ? files.getFirst() : null;
    }

    /** Every selected template, in the order the tiles were clicked. */
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

    // -------------------------------------------------------------------------
    // Everything about the one template being looked at
    // -------------------------------------------------------------------------

    /**
     * The panel under the preview: name, tags, and the button that swaps the picture. It gives way to a hint
     * whenever the selection is not exactly one template — a chip's ✕ has to mean "off this template", and a
     * name field over two selected templates has no answer at all.
     */
    private VBox singlePane() {
        nameField.setPromptText("template name");
        nameField.setOnAction(e -> renameToFieldValue());
        nameField.textProperty().addListener((o, was, is) -> refreshNameState());
        renameButton.setOnAction(e -> renameToFieldValue());
        renameButton.setTooltip(new Tooltip("Renames the file and every block that uses it"));
        nameMessage.getStyleClass().add("template-gallery-empty");
        nameMessage.setWrapText(true);
        HBox nameRow = new HBox(6, new Label("Name"), nameField, renameButton);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        replaceButton.setTooltip(new Tooltip(
                "Swap this template's picture, keeping its name, its tags and every block that uses it"));
        MenuItem recapture = new MenuItem("Capture a new picture…");
        recapture.setOnAction(e -> replaceByCapture(selectedFile()));
        MenuItem fromFile = new MenuItem("Use an image file…");
        fromFile.setOnAction(e -> replaceFromFile(selectedFile()));
        replaceButton.getItems().setAll(recapture, fromFile);

        previewHint.getStyleClass().add("template-gallery-empty");
        previewHint.setWrapText(true);
        duplicateNote.getStyleClass().add("template-gallery-empty");
        duplicateNote.setWrapText(true);

        // The chips are rebuilt from the manifest on every reload, and rebuilding them takes the "+ Tag" menu
        // off the scene — which closes it. Ticking a second tag was therefore impossible without reopening
        // the menu. The rebuild is deferred while it is open (see refreshSingleBox) and run once it shuts.
        addTagButton.showingProperty().addListener((o, was, showing) -> {
            if (!showing) refreshSingleBox();
        });

        singleBox.getChildren().setAll(nameRow, nameMessage, new Label("Tags"), previewTags,
                replaceButton, duplicateNote);
        VBox box = new VBox(6, singleBox, previewHint);
        box.setPadding(new Insets(6, 0, 0, 0));
        return box;
    }

    /** Shows the single-template panel or the hint, and refills the name field and the chips. */
    private void refreshSingleBox() {
        Path file = selectedFile();
        boolean single = file != null;
        singleBox.setVisible(single);
        singleBox.setManaged(single);
        previewHint.setVisible(!single);
        previewHint.setManaged(!single);
        if (!single) {
            previewTags.getChildren().clear();
            List<Path> selection = selectedFiles();
            previewHint.setText(selection.isEmpty()
                    ? "Select a single template to rename, tag or replace it."
                    : selection.size() + " templates selected — the buttons below act on all of them.");
            return;
        }

        String name = ImageTemplateLibrary.baseName(file);
        nameField.setText(name);
        boolean isDefault = ImageTemplateLibrary.isDefaultTemplate(file);
        nameField.setDisable(isDefault);
        // Guarded like rename and delete, and for the same reason: every project generates its own default
        // template and the vision blocks a fresh project drops all point at it, so swapping its picture
        // changes what those blocks match against without naming any of them.
        replaceButton.setDisable(isDefault);
        refreshNameState();
        refreshDuplicateNote(name);

        // Leave the chips — and with them the open "+ Tag" menu — exactly where they are while the user is
        // still ticking boxes in it. The listener in singlePane() runs this again once the menu closes.
        if (addTagButton.isShowing()) return;
        previewTags.getChildren().clear();
        List<String> tags = ImageTemplateLibrary.tagCatalog(config)
                .declaredOnly(ImageTemplateLibrary.manifest(config).tagsOf(name));
        for (String tag : tags) previewTags.getChildren().add(tagChip(name, tag));
        previewTags.getChildren().add(tagMenu(name, tags));
    }

    /** Says, under the preview, when another template holds this same picture. */
    private void refreshDuplicateNote(String name) {
        List<String> others = duplicates.getOrDefault(name, List.of());
        duplicateNote.setText(others.isEmpty() ? ""
                : "Duplicate picture — also stored as " + String.join(", ", others) + ".");
        duplicateNote.setManaged(!others.isEmpty());
        duplicateNote.setVisible(!others.isEmpty());
    }

    /**
     * Says inline whether the name in the field can be taken, and enables Rename only when it can. Inline
     * rather than a dialog that refuses on OK: the answer depends on what else is in the library, which is on
     * screen right next to the field.
     */
    private void refreshNameState() {
        Path file = selectedFile();
        if (file == null) return;
        String current = ImageTemplateLibrary.baseName(file);
        String wanted = ImageTemplateLibrary.sanitizeName(nameField.getText());
        String problem = renameProblem(current, wanted);
        boolean unchanged = wanted.equals(current);
        renameButton.setDisable(problem != null || unchanged || nameField.isDisabled());
        if (ImageTemplateLibrary.isDefaultTemplate(file)) {
            nameMessage.setText("The default template can't be renamed, and its picture can't be swapped — "
                    + "capture your own and point your blocks at that.");
        } else if (problem != null) {
            nameMessage.setText(problem);
        } else if (unchanged) {
            nameMessage.setText("");
        } else {
            nameMessage.setText("Will be saved as " + wanted + ".png");
        }
    }

    /** Why {@code wanted} can't be used, or null when it can. */
    private String renameProblem(String current, String wanted) {
        if (wanted.isBlank()) return "A template needs a name.";
        if (wanted.equals(current)) return null;
        if (ImageTemplateLibrary.isReservedName(wanted)) return "\"" + wanted + "\" is a name the library uses.";
        if (ImageTemplateLibrary.exists(config, wanted)) return "There is already a template called " + wanted + ".";
        return null;
    }

    /** Renames the previewed template and every block that names it, in one step. */
    private void renameToFieldValue() {
        Path file = selectedFile();
        if (file == null || renameButton.isDisabled()) return;
        String current = ImageTemplateLibrary.baseName(file);
        String wanted = ImageTemplateLibrary.sanitizeName(nameField.getText());
        if (renameProblem(current, wanted) != null) return;
        try {
            ImageTemplateLibrary.renameTemplate(config, file, wanted);
            // After the file has moved, so a failure to rewrite leaves the sources naming a template that is
            // gone (a compile error) rather than one that no longer exists under that name (a silent miss).
            List<Path> touched = TemplateReferences.retarget(config, state(), current, wanted);
            refreshEditor();
            published();
            reload();
            gallery.setSelection(List.of(config.imagesRoot().resolve(wanted + ".png")));
            statusLabel.setText(touched.isEmpty()
                    ? "Renamed to " + wanted + "."
                    : "Renamed to " + wanted + " and updated " + touched.size()
                            + (touched.size() == 1 ? " file" : " files") + " that used it.");
        } catch (IOException e) {
            statusLabel.setText("Failed to rename: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Tagging where you are looking
    // -------------------------------------------------------------------------

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

    /**
     * The chips' "+": every declared tag as a tick box, so filing one template under three tags is one opening
     * of one menu. Each box applies immediately and the menu stays open ({@code setHideOnClick(false)}) — the
     * old version was one tag per click <em>and</em> closed itself after each, which is what made a template
     * with several tags feel like it wasn't supported.
     */
    private MenuButton tagMenu(String templateName, List<String> already) {
        addTagButton.getItems().clear();
        Set<String> carried = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        carried.addAll(already);
        for (String tag : ImageTemplateLibrary.tagCatalog(config).names()) {
            CheckBox box = new CheckBox(tag);
            box.setSelected(carried.contains(tag));
            box.setOnAction(e -> {
                if (box.isSelected()) ImageTemplateLibrary.addTag(config, List.of(templateName), tag);
                else ImageTemplateLibrary.removeTag(config, List.of(templateName), tag);
                published();
                // Repaint the chips and the rail counts without closing the menu the user is still working in.
                gallery.reload();
                refreshTagActions();
            });
            CustomMenuItem item = new CustomMenuItem(box);
            item.setHideOnClick(false);
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
        int selected = selectedFiles().size();
        addToTagButton.setText(real ? "Add templates to \"" + tag + "\"…" : "Add templates…");
        removeFromTagButton.setText(real ? "Remove from \"" + tag + "\"" : "Remove from tag");
        addToTagButton.setDisable(!real);
        removeFromTagButton.setDisable(!real || selected == 0);
        deleteButton.setText(selected > 1 ? "Delete " + selected : "Delete");
        deleteButton.setDisable(selected == 0);
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

    /** Opens the tag manager, and picks up whatever it changed when it closes. */
    private void manageTags() {
        new TagManagerDialog(stage, config, () -> {
            published();
            reload();
        }).show();
    }

    // -------------------------------------------------------------------------
    // Capture, replace
    // -------------------------------------------------------------------------

    /**
     * Opens the same capture overlay the toolbar's "Capture Templates" opens — capture one, capture many, or
     * capture an object — rather than the single-shot region crop this button used to run. Two flows for the
     * same act was the whole complaint: only one of them could take a batch, and it wasn't the one behind the
     * button named "Capture new".
     *
     * <p>The manager hides itself first. It is application-modal, so the overlay's toolbar would take no
     * clicks at all with this window still up; it comes back through the overlay's {@code onClosed}.
     */
    private void captureNew() {
        stage.hide();
        OverlayTemplateCapture.open(owner, config, settings, capture, eventBus, gallery.selectedRealTag(),
                () -> Platform.runLater(() -> {
                    stage.show();
                    reload();
                }));
    }

    /** Recaptures a template's picture from the screen, keeping everything else about it. */
    private void replaceByCapture(Path file) {
        if (file == null) return;
        stage.setIconified(true);   // a region crop owns the screen; this window is on it
        capture.captureRegion(owner, (img, sourceW, sourceH) -> Platform.runLater(() -> {
            stage.setIconified(false);
            applyReplacement(file, img, sourceW, sourceH);
        }));
    }

    /** Replaces a template's picture with an image file from disk. */
    private void replaceFromFile(Path file) {
        if (file == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Replace " + ImageTemplateLibrary.baseName(file));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        java.io.File picked = chooser.showOpenDialog(stage);
        if (picked == null) return;
        try {
            BufferedImage img = ImageIO.read(picked);
            if (img == null) {
                statusLabel.setText("Couldn't read " + picked.getName() + " as an image.");
                return;
            }
            // Unknown capture resolution: the file came from outside, so there is no window size to record.
            applyReplacement(file, img, 0, 0);
        } catch (IOException e) {
            statusLabel.setText("Failed to read the image: " + e.getMessage());
        }
    }

    private void applyReplacement(Path file, BufferedImage img, int sourceW, int sourceH) {
        if (img == null) return;
        try {
            ImageTemplateLibrary.replaceImage(config, file, img, sourceW, sourceH, null);
            published();
            reload();
            gallery.setSelection(List.of(file));
            // Straight from disk, past the JavaFX image cache, which would otherwise hand back the old picture
            // for the same URL.
            preview.setImage(new Image(file.toUri() + "?t=" + System.currentTimeMillis()));
            statusLabel.setText("Replaced the picture of " + ImageTemplateLibrary.baseName(file)
                    + " — every block that uses it now sees the new one.");
        } catch (IOException e) {
            statusLabel.setText("Failed to replace the image: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes the selection, one template or twenty. Anything the bot's source still names is not deleted
     * silently: the uses are listed, and the user either cancels or picks the template those blocks should
     * point at instead — which is the answer they wanted in the first place, since a template is usually
     * deleted because a better one replaced it.
     */
    private void delete(List<Path> selection) {
        if (selection.isEmpty()) {
            statusLabel.setText("Select the templates to delete.");
            return;
        }
        List<Path> files = new ArrayList<>();
        boolean skippedDefault = false;
        for (Path file : selection) {
            if (ImageTemplateLibrary.isDefaultTemplate(file)) skippedDefault = true;
            else files.add(file);
        }
        if (files.isEmpty()) {
            statusLabel.setText("The default template can't be deleted.");
            return;
        }

        Map<String, TemplateReferences.Scan> used = new LinkedHashMap<>();
        for (Path file : files) {
            String name = ImageTemplateLibrary.baseName(file);
            TemplateReferences.Scan scan = TemplateReferences.find(config, state(), name);
            if (!scan.isEmpty()) used.put(name, scan);
        }
        if (used.isEmpty()) {
            deleteAll(files, skippedDefault, 0);
            return;
        }
        offerTransfer(files, used, skippedDefault);
    }

    /** The "N blocks use this" conversation: cancel, or point them at another template and then delete. */
    private void offerTransfer(List<Path> files, Map<String, TemplateReferences.Scan> used, boolean skippedDefault) {
        StringBuilder detail = new StringBuilder();
        for (TemplateReferences.Scan scan : used.values()) {
            detail.append(scan.baseName()).append(" — ").append(scan.describe()).append('\n');
            for (TemplateReferences.Use use : scan.uses()) {
                detail.append("    ").append(use.file().getFileName()).append(':').append(use.line())
                        .append("  ").append(use.text()).append('\n');
            }
        }
        ButtonType transfer = new ButtonType("Point them at another template…", ButtonType.OK.getButtonData());
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "Deleting " + (used.size() == 1 ? "it" : "them") + " now would leave those blocks looking for a "
                        + "file that isn't there.\n\nYou can point them at another template first — they keep "
                        + "working, and the template goes.",
                transfer, ButtonType.CANCEL);
        ThemedWindows.apply(alert);
        alert.initOwner(stage);
        alert.setTitle("Still in use");
        alert.setHeaderText(used.size() == 1
                ? "\"" + used.keySet().iterator().next() + "\" is still used by your blocks"
                : used.size() + " of the selected templates are still used by your blocks");
        javafx.scene.control.TextArea where = new javafx.scene.control.TextArea(detail.toString().stripTrailing());
        where.setEditable(false);
        where.setPrefRowCount(Math.min(12, detail.toString().split("\n").length + 1));
        alert.getDialogPane().setExpandableContent(where);
        alert.getDialogPane().setExpanded(true);
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != transfer) {
            statusLabel.setText("Nothing was deleted.");
            return;
        }

        Set<Path> going = Set.copyOf(files);
        TemplateGalleryDialog.Options options = TemplateGalleryDialog.Options
                .pickOne("Point those blocks at…")
                .withFilter(file -> !going.contains(file));
        TemplateGalleryDialog.open(stage, config, options, picked -> {
            if (picked.isEmpty()) return;
            String replacement = ImageTemplateLibrary.baseName(picked.getFirst());
            int rewritten = 0;
            for (String name : used.keySet()) {
                rewritten += TemplateReferences.retarget(config, state(), name, replacement).size();
            }
            refreshEditor();
            deleteAll(files, skippedDefault, rewritten);
        });
    }

    private void deleteAll(List<Path> files, boolean skippedDefault, int rewritten) {
        int deleted = 0;
        for (Path file : files) {
            try {
                ImageTemplateLibrary.deleteTemplate(config, file);
                deleted++;
            } catch (IOException e) {
                statusLabel.setText("Failed to delete " + ImageTemplateLibrary.baseName(file) + ": " + e.getMessage());
                break;
            }
        }
        published();
        reload();
        String note = "Deleted " + deleted + (deleted == 1 ? " template" : " templates");
        if (rewritten > 0) note += ", after pointing " + rewritten + (rewritten == 1 ? " file" : " files") + " elsewhere";
        if (skippedDefault) note += ". The default template was left alone";
        statusLabel.setText(note + ".");
    }

    // -------------------------------------------------------------------------
    // Import / export
    // -------------------------------------------------------------------------

    /**
     * Exports the selection (or the whole library when nothing is selected) as a {@code .bmtemplates} file.
     *
     * <p>A whole-library export leaves out an untouched {@code default_template} — every project generates its
     * own, so shipping the placeholder only gives the destination a second copy of what it already has.
     * Selecting it explicitly still exports it, and one the user has replaced is a real template like any other.
     */
    private void export(List<Path> selection) {
        List<Path> files = selection.isEmpty()
                ? ImageTemplateLibrary.list(config).stream()
                        .filter(f -> !ImageTemplateLibrary.isUnmodifiedDefaultTemplate(f)).toList()
                : selection;
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
            gallery.setSelection(result.imported().stream()
                    .map(name -> config.imagesRoot().resolve(name + ".png")).toList());
            statusLabel.setText(result.summary());
            reportImport(result);
        } catch (IOException e) {
            statusLabel.setText("Failed to import: " + e.getMessage());
        }
    }

    /**
     * Says what the import did, once — <b>as pictures</b>. An archive is a set of images, and a list of names
     * is the one form in which you cannot tell whether the right ones arrived; the same tile the gallery draws
     * answers that at a glance. The prose underneath carries what a picture can't say: what was renamed
     * around a collision, what was already here, and what could not be named at all.
     */
    private void reportImport(TemplateArchive.ImportResult result) {
        String details = result.details();
        List<String> duplicated = result.imported().stream()
                .filter(name -> !duplicates.getOrDefault(name, List.of()).isEmpty()).toList();
        if (details.isEmpty() && result.imported().isEmpty()) return;

        FlowPane arrived = new FlowPane(10, 10);
        arrived.setPrefWrapLength(520);
        for (String name : result.imported()) {
            Path file = config.imagesRoot().resolve(name + ".png");
            VBox tile = TemplateGallery.plainTile(file, 84);
            List<String> others = duplicates.getOrDefault(name, List.of());
            if (!others.isEmpty()) {
                Label same = new Label("same picture as " + String.join(", ", others));
                same.getStyleClass().add("template-gallery-empty");
                same.setWrapText(true);
                same.setMaxWidth(100);
                tile.getChildren().add(same);
            }
            arrived.getChildren().add(tile);
        }

        VBox content = new VBox(10);
        if (!arrived.getChildren().isEmpty()) content.getChildren().add(arrived);
        if (!duplicated.isEmpty()) {
            content.getChildren().add(hint(duplicated.size() == 1
                    ? "One of these is a picture this project already had under another name. It was still "
                            + "imported — two names for one picture is a choice, not a mistake."
                    : duplicated.size() + " of these are pictures this project already had under other names. "
                            + "They were still imported — two names for one picture is a choice, not a mistake."));
        }
        if (!details.isEmpty()) content.getChildren().add(hint(details));

        Alert alert = ThemedWindows.alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("Import finished");
        alert.setHeaderText(result.count() == 0
                ? "Nothing new to import."
                : "Imported " + result.count() + (result.count() == 1 ? " template." : " templates."));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setMinWidth(560);
        alert.showAndWait();
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(520);
        return label;
    }

    // -------------------------------------------------------------------------
    // Templates deleted behind Studio's back
    // -------------------------------------------------------------------------

    /**
     * Asks about templates the manifest still files but whose file is gone — deleted in a file manager, or
     * lost to a git checkout. Studio used to say nothing: the tags stayed, the generated constant vanished on
     * the next regeneration, and the first anyone heard of it was a block failing to find its picture at run
     * time.
     *
     * <p>Two answers are offered, because there are two situations. Nothing in the source names it: forget it,
     * which drops the manifest entry. Something does: point those blocks at a template that still exists,
     * through the same transfer the delete path uses — that is the repair, and it is the same one either way.
     */
    private void reportMissing() {
        List<String> missing = ImageTemplateLibrary.missingTemplates(config);
        if (missing.isEmpty()) return;

        Map<String, TemplateReferences.Scan> used = new LinkedHashMap<>();
        for (String name : missing) {
            TemplateReferences.Scan scan = TemplateReferences.find(config, state(), name);
            if (!scan.isEmpty()) used.put(name, scan);
        }

        StringBuilder body = new StringBuilder(missing.size() == 1
                ? "\"" + missing.getFirst() + "\" is still filed under its tags, but its file is not in the "
                        + "project any more."
                : missing.size() + " templates are still filed under their tags, but their files are not in "
                        + "the project any more:\n    " + String.join(", ", missing));
        if (!used.isEmpty()) {
            body.append("\n\nYour blocks still name ").append(used.size() == 1 ? "one of them" : "some of them")
                    .append(":\n");
            used.values().forEach(scan -> body.append("    ").append(scan.baseName()).append(" — ")
                    .append(scan.describe()).append('\n'));
        }

        ButtonType forget = new ButtonType("Forget them", ButtonType.OK.getButtonData());
        ButtonType repoint = new ButtonType("Point those blocks at another template…", ButtonType.OK.getButtonData());
        Alert alert = new Alert(Alert.AlertType.WARNING, body.toString().stripTrailing());
        ThemedWindows.apply(alert);
        alert.initOwner(stage);
        alert.setTitle("Missing template files");
        alert.setHeaderText(missing.size() == 1 ? "A template's file is gone" : "Some template files are gone");
        alert.getDialogPane().setMinWidth(560);
        alert.getButtonTypes().setAll(used.isEmpty()
                ? List.of(forget, ButtonType.CANCEL)
                : List.of(repoint, forget, ButtonType.CANCEL));
        ButtonType chosen = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (chosen == forget) {
            forgetMissing(missing);
        } else if (chosen == repoint) {
            TemplateGalleryDialog.open(stage, config, TemplateGalleryDialog.Options.pickOne("Point those blocks at…"),
                    picked -> {
                        if (picked.isEmpty()) return;
                        String replacement = ImageTemplateLibrary.baseName(picked.getFirst());
                        for (String name : used.keySet()) {
                            TemplateReferences.retarget(config, state(), name, replacement);
                        }
                        refreshEditor();
                        forgetMissing(missing);
                    });
        }
    }

    /** Drops the manifest entries of templates that no longer have a file, and regenerates the constants. */
    private void forgetMissing(List<String> missing) {
        TemplateManifest manifest = ImageTemplateLibrary.manifest(config);
        for (String name : missing) manifest = manifest.without(name);
        ImageTemplateLibrary.saveManifest(config, manifest);
        ImageTemplateLibrary.regenerateTemplatesClass(config);
        published();
        reload();
        statusLabel.setText("Forgot " + missing.size() + (missing.size() == 1 ? " template" : " templates")
                + " whose file was gone.");
    }

    // -------------------------------------------------------------------------

    /** The editor's open files, so a rewrite reaches the buffers as well as the disk. Null in headless use. */
    private ProjectState state() {
        return editor == null ? null : editor.getState();
    }

    /** Re-renders the open file after its text was rewritten behind the editor's back. */
    private void refreshEditor() {
        ProjectState state = state();
        if (state == null || state.getActiveFile() == null) return;
        eventBus.publish(new com.botmaker.studio.events.CoreApplicationEvents.UIRefreshRequestedEvent(
                state.getActiveFile().getContent()));
    }

    private void published() {
        statusLabel.setText("");
        eventBus.publish(new ResourcesChangedEvent());
    }
}
