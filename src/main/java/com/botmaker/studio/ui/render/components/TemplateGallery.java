package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.TagCatalog;
import com.botmaker.studio.services.TemplateGalleryModel;
import com.botmaker.studio.services.TemplateManifest;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

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
    /** How far a press has to travel before it is a band and not a click on the background. */
    private static final double BAND_THRESHOLD = 4;

    private final ProjectConfig config;
    private final boolean multiSelect;

    private final ListView<TemplateGalleryModel.Row> rail = new ListView<>();
    private final TextField search = new TextField();
    private final FlowPane grid = new FlowPane(10, 10);
    private final Label empty = new Label();
    /** "N selected · Select all · Clear" — only built, and only shown, for a multi-select gallery. */
    private final Label selectionCount = new Label();
    private final HBox selectionBar = new HBox(10);

    /** The tiles currently on screen, so a selection change repaints without rebuilding the grid. */
    private final Map<Path, Node> tiles = new LinkedHashMap<>();
    private final LinkedHashSet<Path> selected = new LinkedHashSet<>();

    /** The grid plus the rubber band drawn over it; the band is unmanaged, so it moves without re-laying out. */
    private final StackPane gridLayer = new StackPane();
    private final Rectangle band = new Rectangle();
    /** The selection the band adds to — everything already picked on a Ctrl-drag, nothing on a plain one. */
    private List<Path> bandBase = List.of();
    private double bandOriginX;
    private double bandOriginY;
    private boolean banding;

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
        grid.setMaxWidth(Double.MAX_VALUE);
        grid.setMaxHeight(Double.MAX_VALUE);
        band.getStyleClass().add("template-gallery-band");
        band.setManaged(false);
        band.setVisible(false);
        gridLayer.setAlignment(Pos.TOP_LEFT);
        gridLayer.setMaxWidth(Double.MAX_VALUE);
        gridLayer.setMaxHeight(Double.MAX_VALUE);
        gridLayer.getChildren().addAll(grid, band);
        ScrollPane scroll = new ScrollPane(gridLayer);
        scroll.setFitToWidth(true);
        // The layer has to reach the bottom of the viewport, or the empty space under the last row belongs to
        // the ScrollPane and a band cannot be started in the most natural place to start one.
        scroll.setFitToHeight(true);
        installBand();

        empty.getStyleClass().add("template-gallery-empty");
        empty.setWrapText(true);

        VBox right = new VBox(8, search, selectionBar(), scroll, empty);
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
        refreshSelectionBar();
        if (onSelectionChanged != null) onSelectionChanged.run();
    }

    /** Selects every template the grid is currently showing — the tag being viewed, narrowed by the search. */
    public void selectAll() {
        setSelection(List.copyOf(tiles.keySet()));
    }

    /** Drops the selection without moving the rail or the search. */
    public void clearSelection() {
        setSelection(List.of());
    }

    /**
     * The bar above the grid that says how many tiles are picked and offers the two answers to "now what" —
     * all of them, or none. A single-select gallery has no such state, so it gets no bar at all rather than a
     * permanently-empty strip.
     */
    private HBox selectionBar() {
        if (!multiSelect) {
            selectionBar.setManaged(false);
            selectionBar.setVisible(false);
            return selectionBar;
        }
        selectionCount.getStyleClass().add("template-gallery-selection-count");
        Hyperlink all = new Hyperlink("Select all");
        all.setOnAction(e -> selectAll());
        Hyperlink none = new Hyperlink("Clear");
        none.setOnAction(e -> clearSelection());
        selectionBar.getChildren().addAll(selectionCount, all, none);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.getStyleClass().add("template-gallery-selection-bar");
        refreshSelectionBar();
        return selectionBar;
    }

    private void refreshSelectionBar() {
        if (!multiSelect) return;
        int n = selected.size();
        // The empty state is where the gesture is taught, now that a plain click no longer accumulates.
        selectionCount.setText(n == 0
                ? "Click to select · Ctrl-click to add · drag a box for several"
                : n + " selected");
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
        refreshSelectionBar();
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

    /**
     * A template as a picture with its name under it, at {@code size} pixels — the tile without the behaviour.
     *
     * <p>Public and static because "what does a template look like in a list" is asked outside the grid too:
     * the resource manager's end-of-import summary shows what arrived, and it should look like the gallery it
     * arrived into rather than be a second, worse rendering of the same thing.
     */
    public static VBox plainTile(Path file, double size) {
        VBox box = new VBox(4);
        box.getStyleClass().add("template-tile");
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(size + 24);

        // A fixed-height holder rather than the ImageView itself: thumbnails preserve their ratio, so a wide
        // and a tall one differ in height, and rows of tiles would step up and down across the grid.
        StackPane picture = new StackPane();
        picture.setMinHeight(size);
        picture.setPrefHeight(size);
        ImageView thumb = ImageTemplatePicker.thumbnail(file, size);
        if (thumb != null) picture.getChildren().add(thumb);
        box.getChildren().add(picture);

        Label name = new Label(ImageTemplateLibrary.baseName(file));
        name.getStyleClass().add("template-tile-name");
        name.setMaxWidth(size + 16);
        box.getChildren().add(name);
        return box;
    }

    /** One template: its picture at the size you would judge it by, its name, and the tags it carries. */
    private Node tile(Path file, List<String> tags) {
        VBox box = plainTile(file, 96);

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
        if (!multiSelect) {
            setSelection(List.of(file));
            return;
        }
        // The two click gestures every file manager has, and in that order of frequency. A plain click
        // *replaces*: making it toggle instead was meant to advertise multi-select, and what it actually did was
        // make looking at a second template select both — so the common gesture, "show me that one", was the one
        // that behaved unlike everywhere else. Ctrl toggles. Taking several at once is the band's job now:
        // Shift-click extended over the grid's *wrap* order, which reads as a rectangle only by accident and
        // swept up whole rows the user never crossed.
        List<Path> next = new ArrayList<>();
        if (e.isShortcutDown()) {
            next.addAll(selected);
            if (!next.remove(file)) next.add(file);
        } else {
            next.add(file);
        }
        setSelection(next);
    }

    /**
     * Selection by dragging a box over the grid — the gesture a folder of thumbnails is expected to have.
     *
     * <p>It is installed on the layer under the tiles, and starts only where a press lands on the background,
     * so a press on a tile is still that tile's click. A plain drag replaces the selection; Ctrl-drag adds to
     * what was already picked. A press that never travels is a click on the background, which is how a
     * selection is cleared — the behaviour the ScrollPane handler used to carry on its own.
     */
    private void installBand() {
        gridLayer.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getTarget() != gridLayer && e.getTarget() != grid) return;
            if (!multiSelect) {
                setSelection(List.of());
                return;
            }
            banding = true;
            bandBase = e.isShortcutDown() ? List.copyOf(selected) : List.of();
            Point2D origin = gridLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
            bandOriginX = origin.getX();
            bandOriginY = origin.getY();
            stretchBand(bandOriginX, bandOriginY);
            band.setVisible(false);   // nothing to draw until the press actually travels
        });
        gridLayer.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!banding) return;
            Point2D here = gridLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
            stretchBand(here.getX(), here.getY());
            if (!band.isVisible() && (band.getWidth() >= BAND_THRESHOLD || band.getHeight() >= BAND_THRESHOLD)) {
                band.setVisible(true);
            }
            if (band.isVisible()) applyBand();
        });
        gridLayer.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (!banding) return;
            boolean travelled = band.isVisible();
            banding = false;
            band.setVisible(false);
            // A press on the background that went nowhere: clear, or — on Ctrl — leave the selection alone.
            if (!travelled) setSelection(bandBase);
        });
    }

    /** Redraws the band as the rectangle between where the press started and where the pointer is now. */
    private void stretchBand(double x, double y) {
        band.setX(Math.min(bandOriginX, x));
        band.setY(Math.min(bandOriginY, y));
        band.setWidth(Math.abs(x - bandOriginX));
        band.setHeight(Math.abs(y - bandOriginY));
    }

    /** Everything the band touches, on top of what it started with. Live, so the drag shows what it will take. */
    private void applyBand() {
        Bounds box = band.getBoundsInParent();
        List<Path> next = new ArrayList<>(bandBase);
        tiles.forEach((file, tile) -> {
            Bounds where = gridLayer.sceneToLocal(tile.localToScene(tile.getBoundsInLocal()));
            if (box.intersects(where) && !next.contains(file)) next.add(file);
        });
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
