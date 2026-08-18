package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.TagCatalog;
import com.botmaker.studio.services.TemplateGalleryModel;
import com.botmaker.studio.services.TemplateManifest;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The project's image templates, as a tag rail beside a thumbnail grid. <b>The</b> gallery: the resource
 * manager embeds it and every picker opens it in a dialog ({@link TemplateGalleryDialog}), so there is one
 * rendering of "which templates exist and how are they filed" rather than the two that had drifted apart — a
 * tree in the manager and tag submenus in the picker, neither of which showed a picture at the size you pick
 * by and neither of which could be searched.
 *
 * <p>The rail's rows and the search are {@link TemplateGalleryModel}, deliberately outside this class: they
 * are the decisions (what a group is, what an empty declared tag looks like, what a query matches) and they
 * are testable without a display. What is left here is genuinely a widget.
 *
 * <p>Selection is by tile, and multiple selection is opt-in ({@code multiSelect}) because the two callers
 * want different things: the manager tags and deletes in bulk, a single {@code ImageTemplate} slot holds
 * exactly one image. A template carrying two tags is one tile shown under either rail row — never two files.
 */
public final class TemplateGallery extends HBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final ProjectConfig config;
    private final boolean multiSelect;

    private final ListView<TemplateGalleryModel.Row> rail = new ListView<>();
    private final TextField search = new TextField();
    private final FlowPane grid = new FlowPane(10, 10);
    private final Label empty = new Label();

    /** The tiles currently on screen, so a selection change repaints without rebuilding the grid. */
    private final Map<Path, Node> tiles = new LinkedHashMap<>();
    private final LinkedHashSet<Path> selected = new LinkedHashSet<>();

    private Predicate<Path> filter = file -> true;
    private Runnable onSelectionChanged;
    private Runnable onTagChanged;
    private Consumer<Path> onActivate;

    public TemplateGallery(ProjectConfig config, boolean multiSelect) {
        super(10);
        this.config = config;
        this.multiSelect = multiSelect;
        getStyleClass().add("template-gallery");

        rail.setMinWidth(180);
        rail.setPrefWidth(180);
        rail.setCellFactory(v -> new RailCell());
        rail.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            // A heading is a label, not a bucket. Bounce the selection back rather than showing nothing.
            if (is instanceof TemplateGalleryModel.Heading) {
                rail.getSelectionModel().select(was);
                return;
            }
            refreshGrid();
        });

        search.setPromptText("Search templates…");
        search.textProperty().addListener((o, was, is) -> refreshGrid());

        grid.setPadding(new Insets(4));
        grid.setPrefWrapLength(520);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        // Clicking the background clears the selection — otherwise the only way out of a selection is to pick
        // something else, and "tag nothing" then reads as "tag whatever was left over".
        scroll.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getTarget() == scroll || e.getTarget() == grid) setSelection(List.of());
        });

        empty.getStyleClass().add("template-gallery-empty");
        empty.setWrapText(true);

        VBox right = new VBox(8, search, scroll, empty);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        getChildren().addAll(rail, right);
        reload();
    }

    /** Narrows the gallery to the templates {@code filter} accepts — a group picker offers only its own. */
    public void setFilter(Predicate<Path> filter) {
        this.filter = filter == null ? file -> true : filter;
        refreshGrid();
    }

    /** Called whenever the selection changes, so a dialog can enable its Choose button. */
    public void setOnSelectionChanged(Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    /** Called whenever the rail moves to another tag, so a dialog can retitle its per-tag actions. */
    public void setOnTagChanged(Runnable onTagChanged) {
        this.onTagChanged = onTagChanged;
    }

    /** Called on a double-click — "pick this one and be done". */
    public void setOnActivate(Consumer<Path> onActivate) {
        this.onActivate = onActivate;
    }

    /** The selected template files, in the order they were selected. */
    public List<Path> selectedFiles() {
        return List.copyOf(selected);
    }

    /** Re-reads the library and the tag catalog from disk, keeping the rail row and selection where it can. */
    public void reload() {
        TemplateGalleryModel.Row was = rail.getSelectionModel().getSelectedItem();
        TagCatalog catalog = ImageTemplateLibrary.tagCatalog(config);
        List<TemplateGalleryModel.Row> rows =
                TemplateGalleryModel.rows(ImageTemplateLibrary.listByTag(config), catalog);
        rail.getItems().setAll(rows);

        String wanted = was instanceof TemplateGalleryModel.TagRow row ? row.tag() : TemplateManifest.ALL;
        rows.stream()
                .filter(r -> r instanceof TemplateGalleryModel.TagRow t && t.tag().equalsIgnoreCase(wanted))
                .findFirst()
                .ifPresentOrElse(r -> rail.getSelectionModel().select(r),
                        () -> rail.getSelectionModel().selectFirst());
        refreshGrid();
    }

    /** Selects exactly {@code files} (those still on screen), as a caller re-opening a picker would want. */
    public void setSelection(Collection<Path> files) {
        selected.clear();
        selected.addAll(files);
        tiles.forEach((file, node) -> node.pseudoClassStateChanged(SELECTED, selected.contains(file)));
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    /**
     * The rail row currently shown — a declared tag, or {@link TemplateManifest#ALL} / {@code UNTAGGED}. The
     * two computed rows are named the same way as real ones on purpose; {@link #selectedRealTag()} is what
     * asks the question a per-tag action needs answered.
     */
    public String selectedTag() {
        TemplateGalleryModel.Row row = rail.getSelectionModel().getSelectedItem();
        return row instanceof TemplateGalleryModel.TagRow tag ? tag.tag() : TemplateManifest.ALL;
    }

    /** The selected tag when it is one templates can be filed under, else null ("All" and "Untagged" are not). */
    public String selectedRealTag() {
        String tag = selectedTag();
        return TemplateManifest.isSyntheticTag(tag) ? null : tag;
    }

    private void refreshGrid() {
        List<Path> files = ImageTemplateLibrary.listByTag(config)
                .getOrDefault(selectedTag(), List.of()).stream().filter(filter).toList();
        List<Path> visible = TemplateGalleryModel.matching(files, search.getText());

        tiles.clear();
        grid.getChildren().clear();
        TemplateManifest manifest = ImageTemplateLibrary.manifest(config);
        TagCatalog catalog = ImageTemplateLibrary.tagCatalog(config);
        for (Path file : visible) {
            Node tile = tile(file, catalog.declaredOnly(manifest.tagsOf(ImageTemplateLibrary.baseName(file))));
            tiles.put(file, tile);
            grid.getChildren().add(tile);
        }
        // Keep only what is still on screen selected: acting on a tile the user can no longer see is the kind
        // of surprise a search box invites.
        selected.retainAll(tiles.keySet());
        tiles.forEach((file, node) -> node.pseudoClassStateChanged(SELECTED, selected.contains(file)));
        if (onSelectionChanged != null) onSelectionChanged.run();
        if (onTagChanged != null) onTagChanged.run();

        empty.setText(visible.isEmpty() ? emptyMessage(files.isEmpty()) : "");
        empty.setManaged(visible.isEmpty());
        empty.setVisible(visible.isEmpty());
    }

    private String emptyMessage(boolean nothingAtAll) {
        if (!nothingAtAll) return "No template here matches \"" + search.getText().trim() + "\".";
        if (TemplateManifest.ALL.equals(selectedTag())) return "This project has no image templates yet.";
        return "Nothing is filed under \"" + selectedTag() + "\" yet.";
    }

    /** One template: its picture at the size you would judge it by, its name, and the tags it carries. */
    private Node tile(Path file, List<String> tags) {
        VBox box = new VBox(4);
        box.getStyleClass().add("template-tile");
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(120);

        // A fixed-height holder rather than the ImageView itself: thumbnails preserve their ratio, so a wide
        // and a tall one differ in height, and rows of tiles would step up and down across the grid.
        StackPane picture = new StackPane();
        picture.setMinHeight(96);
        picture.setPrefHeight(96);
        ImageView thumb = ImageTemplatePicker.thumbnail(file, 96);
        if (thumb != null) picture.getChildren().add(thumb);
        box.getChildren().add(picture);

        Label name = new Label(ImageTemplateLibrary.baseName(file));
        name.getStyleClass().add("template-tile-name");
        name.setMaxWidth(112);
        box.getChildren().add(name);

        if (!tags.isEmpty()) {
            FlowPane chips = new FlowPane(3, 3);
            chips.setAlignment(Pos.CENTER);
            for (String tag : tags) {
                Label chip = new Label(tag);
                chip.getStyleClass().add("template-tile-chip");
                chips.getChildren().add(chip);
            }
            box.getChildren().add(chips);
        }

        box.setOnMouseClicked(e -> onTileClicked(file, e));
        return box;
    }

    private void onTileClicked(Path file, MouseEvent e) {
        if (e.getClickCount() >= 2) {
            if (onActivate != null) onActivate.accept(file);
            return;
        }
        List<Path> next = new ArrayList<>();
        if (multiSelect && (e.isShortcutDown() || e.isShiftDown())) {
            next.addAll(selected);
            if (!next.remove(file)) next.add(file);
        } else {
            next.add(file);
        }
        setSelection(next);
    }

    /** A rail row: a heading, or a tag with its count and — for an activity tag — where it comes from. */
    private static final class RailCell extends ListCell<TemplateGalleryModel.Row> {
        @Override protected void updateItem(TemplateGalleryModel.Row item, boolean isEmpty) {
            super.updateItem(item, isEmpty);
            getStyleClass().remove("template-gallery-heading");
            setDisable(false);
            if (isEmpty || item == null) {
                setText(null);
                return;
            }
            switch (item) {
                case TemplateGalleryModel.Heading heading -> {
                    setText(heading.text());
                    getStyleClass().add("template-gallery-heading");
                    // Disabled so a click can't land on it at all; the listener's bounce-back is the backstop
                    // for keyboard navigation, which walks straight through a disabled cell.
                    setDisable(true);
                }
                case TemplateGalleryModel.TagRow tag -> setText(tag.tag() + "  (" + tag.count() + ")");
            }
        }
    }
}
