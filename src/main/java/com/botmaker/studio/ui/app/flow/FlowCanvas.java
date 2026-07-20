package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.FlowEdge;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The free-form Activity Flow canvas. Each activity is a draggable card with <em>one output port per
 * outcome</em>; a wire from an outcome port to another card's input port says "when this activity reports that
 * outcome, run that one next". An outcome port with no wire ends the run — that is the only "stop" there is.
 *
 * <p><b>Mouse.</b> Left-drag on empty canvas rubber-bands a selection; left-drag on a card moves it, taking the
 * rest of the selection with it. Right-drag (or middle-drag) pans, which is why {@code ScrollPane}'s own
 * panning is off: it pans on <em>any</em> button, so leaving it on left the left button with two meanings and
 * no gesture free to select with. Ctrl+scroll zooms, a wire is drawn by dragging from a ▶ port, clicking a wire
 * removes it, and right-clicking a card offers "start here".
 *
 * <p>The flow is a graph, not a chain: an activity branches by wiring each outcome somewhere different,
 * branches meet again by wiring two outcomes into one card, and a wire leading back to an earlier activity is
 * how a bot repeats. {@link FlowRules} vetoes only what can't be drawn — two wires on one outcome. Cards the
 * run can't reach are marked as orphans ("won't run"); that is a warning, not a blocked action, because while
 * you are still wiring most cards are legitimately unconnected.
 *
 * <p>This class owns only topology and presentation. The activity data itself lives in the shared
 * {@link ActivityDraft}s, so edits made in the dialog's side panel show up on the cards immediately.
 */
public final class FlowCanvas extends StackPane {

    private static final double CARD_WIDTH = 180;
    private static final double PORT_RADIUS = 6;
    private static final double GRID_STEP = 24;
    private static final double CANVAS_W = 3000;
    private static final double CANVAS_H = 2000;
    private static final double MIN_ZOOM = 0.4;
    private static final double MAX_ZOOM = 2.0;
    private static final double ARROW_LENGTH = 11;
    private static final Color WIRE = Color.web("#4a7ebb");
    private static final Color WIRE_HOVER = Color.web("#b00020");

    /** Auto-arrange spacing: the pitch between layers, the gap between stacked cards, and the orphan row gap. */
    private static final double ARRANGE_X = 300;
    private static final double ARRANGE_GAP = 40;
    private static final double CARD_HEIGHT_FALLBACK = 80;

    private static final double MINIMAP_W = 190;
    private static final double MINIMAP_H = MINIMAP_W * CANVAS_H / CANVAS_W;

    private final Pane content = new Pane();
    private final Group wires = new Group();
    private final Scale zoom = new Scale(1, 1);
    private final ScrollPane scroller;
    private final Pane minimap = new Pane();
    private final Rectangle minimapViewport = new Rectangle();
    private final Rectangle rubberBand = new Rectangle();

    private final ObservableList<ActivityDraft> drafts = FXCollections.observableArrayList();
    private final ObservableList<FlowEdge> edges = FXCollections.observableArrayList();

    /** Every card by activity name. */
    private final Map<String, NodeCard> cards = new LinkedHashMap<>();

    /**
     * The cards currently selected. Multi-selection exists for moving a group; {@link #selectedProperty()} is
     * the single-selection view the side panel edits, and is null whenever this holds anything but one card.
     */
    private final ObservableList<ActivityDraft> selection = FXCollections.observableArrayList();
    private final ObjectProperty<ActivityDraft> selected = new SimpleObjectProperty<>();

    /** Where inline feedback ("one outcome can't lead to two places") goes — the dialog's status label. */
    private Consumer<String> onMessage = m -> {};
    /** Fired whenever the wiring changes, so the dialog can refresh its reachability summary. */
    private Runnable onChainChanged = () -> {};

    private CubicCurve pendingWire;
    private ActivityDraft pendingFrom;
    private String pendingOutcome;

    private Point2D bandOrigin;
    private Point2D panOrigin;
    private double panStartH;
    private double panStartV;

    /** The activity the run begins at; blank until one is chosen (then the first placed card is used). */
    private String start = "";

    public FlowCanvas() {
        content.setPrefSize(CANVAS_W, CANVAS_H);
        content.setStyle("-fx-background-color: #fafafa;");
        content.getTransforms().add(zoom);
        content.getChildren().addAll(gridBackground(), wires, rubberBand());

        scroller = new ScrollPane(new Group(content));
        // Deliberately NOT pannable: ScrollPane pans on any button, which would steal the left drag from
        // rubber-band selection. Panning is done below, on the secondary/middle button only.
        scroller.setPannable(false);
        scroller.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) return;
            double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double next = Math.clamp(zoom.getX() * factor, MIN_ZOOM, MAX_ZOOM);
            zoom.setX(next);
            zoom.setY(next);
            drawMinimap();
            e.consume();
        });

        content.setOnMousePressed(this::beginCanvasGesture);
        content.setOnMouseDragged(this::continueCanvasGesture);
        content.setOnMouseReleased(this::endCanvasGesture);

        getChildren().addAll(scroller, buildMinimap());
        scroller.hvalueProperty().addListener((o, was, is) -> drawMinimapViewport());
        scroller.vvalueProperty().addListener((o, was, is) -> drawMinimapViewport());
        scroller.viewportBoundsProperty().addListener((o, was, is) -> drawMinimapViewport());
    }

    /** A faint dot grid, drawn once — it makes dragging and alignment legible without any per-frame cost. */
    private static Canvas gridBackground() {
        Canvas grid = new Canvas(CANVAS_W, CANVAS_H);
        GraphicsContext g = grid.getGraphicsContext2D();
        g.setFill(Color.web("#d8d8d8"));
        for (double x = 0; x < CANVAS_W; x += GRID_STEP) {
            for (double y = 0; y < CANVAS_H; y += GRID_STEP) {
                g.fillRect(x, y, 1, 1);
            }
        }
        grid.setMouseTransparent(true);
        return grid;
    }

    private Node rubberBand() {
        rubberBand.setFill(Color.web("#4a7ebb", 0.12));
        rubberBand.setStroke(WIRE);
        rubberBand.getStrokeDashArray().addAll(4.0, 4.0);
        rubberBand.setVisible(false);
        rubberBand.setMouseTransparent(true);
        return rubberBand;
    }

    // --- contents ---

    public ObservableList<ActivityDraft> drafts() { return drafts; }

    public ObservableList<FlowEdge> edges() { return edges; }

    /** The single selected activity — null when nothing, or more than one, is selected. */
    public ObjectProperty<ActivityDraft> selectedProperty() { return selected; }

    /** Everything currently selected; a group drag moves all of it. */
    public ObservableList<ActivityDraft> selection() { return selection; }

    public void setOnMessage(Consumer<String> onMessage) { this.onMessage = onMessage; }

    public void setOnChainChanged(Runnable onChainChanged) { this.onChainChanged = onChainChanged; }

    /** Adds a card for {@code draft} at its stored position and selects it. */
    public void add(ActivityDraft draft) {
        drafts.add(draft);
        NodeCard card = new NodeCard(draft);
        cards.put(draft.name(), card);
        content.getChildren().add(card);
        select(draft);
        refresh();
    }

    /** Removes the activity and every wire touching it. */
    public void remove(ActivityDraft draft) {
        drafts.remove(draft);
        NodeCard card = cards.remove(draft.name());
        if (card != null) content.getChildren().remove(card);
        edges.removeIf(e -> e.from().equals(draft.name()) || e.to().equals(draft.name()));
        if (draft.name().equals(start)) start = "";
        selection.remove(draft);
        if (selected.get() == draft) select(null);
        refresh();
    }

    /** Selects exactly {@code draft} (or nothing, when null). */
    public void select(ActivityDraft draft) {
        selection.setAll(draft == null ? List.of() : List.of(draft));
        syncSelection();
    }

    private void selectAll(List<ActivityDraft> chosen) {
        selection.setAll(chosen);
        syncSelection();
    }

    /** Pushes the selection out to the cards and to the single-selection property the side panel binds to. */
    private void syncSelection() {
        selected.set(selection.size() == 1 ? selection.getFirst() : null);
        for (NodeCard c : cards.values()) c.setSelected(selection.contains(c.draft()));
    }

    /** A free spot for a new card: laid out in a column down the canvas, next to what is already placed. */
    public Point2D nextFreeSpot() {
        double x = 60;
        double y = 40 + drafts.size() * 110.0;
        if (y > CANVAS_H - 120) { // wrap into a new column rather than run off the bottom
            x += 260 * (int) (y / (CANVAS_H - 120));
            y = 40 + (y % (CANVAS_H - 120));
        }
        return new Point2D(x, y);
    }

    /** The activities a run can reach, exactly as the generator will walk them. */
    public List<String> chain() {
        return FlowRules.reachable(placedNames(), edges, start);
    }

    /** Placed activities the flow never reaches — they won't run. */
    public List<String> orphans() {
        return FlowRules.orphans(placedNames(), edges, start);
    }

    /**
     * The {@code activity.outcome} ports with no wire leaving them. Each one ends the run, which is legitimate
     * — the dialog reports them as information, not as a problem.
     */
    public List<String> unwiredOutcomes() {
        List<String> unwired = new ArrayList<>();
        for (String name : chain()) {
            ActivityDraft draft = cards.get(name).draft();
            for (String outcome : draft.allOutcomes()) {
                boolean wired = edges.stream()
                        .anyMatch(e -> e.from().equals(name) && e.outcomeOrNext().equals(outcome));
                if (!wired) unwired.add(name + "." + outcome);
            }
        }
        return unwired;
    }

    /** The activity the run begins at, resolved against what is placed (blank when nothing is). */
    public String resolvedStart() {
        List<String> placed = placedNames();
        if (placed.contains(start)) return start;
        return placed.isEmpty() ? "" : placed.getFirst();
    }

    /** The stored start, which may be blank ("no explicit choice — use the first card"). */
    public String start() {
        return start;
    }

    public void setStart(String newStart) {
        this.start = newStart == null ? "" : newStart;
        refresh();
    }

    private List<String> placedNames() {
        List<String> names = new ArrayList<>(drafts.size());
        for (ActivityDraft d : drafts) names.add(d.name());
        return names;
    }

    /**
     * Puts the cards back in the middle of the viewport at 1:1 zoom — the way out of "I panned into empty
     * canvas and lost everything". The canvas is 3000×2000, so this is easy to do and impossible to undo by
     * dragging.
     *
     * <p>A {@code ScrollPane}'s h/v value is a fraction of the <em>scrollable extent</em> (content minus
     * viewport), not of the content, and the content is scaled by the zoom. Getting either wrong lands the view
     * somewhere plausible-looking but never actually centred, which is what this used to do.
     */
    public void recenter() {
        zoom.setX(1);
        zoom.setY(1);
        // The viewport is only known after a layout pass; without this the first recenter divides by zero.
        Platform.runLater(() -> {
            Bounds view = scroller.getViewportBounds();
            if (cards.isEmpty() || view.getWidth() <= 0) {
                scroller.setHvalue(0);
                scroller.setVvalue(0);
                drawMinimap();
                return;
            }
            Bounds box = cardsBoundingBox();
            scroller.setHvalue(scrollFraction(box.getCenterX() * zoom.getX(), view.getWidth(),
                    CANVAS_W * zoom.getX()));
            scroller.setVvalue(scrollFraction(box.getCenterY() * zoom.getY(), view.getHeight(),
                    CANVAS_H * zoom.getY()));
            drawMinimap();
        });
    }

    /** The h/v value that centres {@code center} in a viewport of {@code viewLength} over {@code contentLength}. */
    private static double scrollFraction(double center, double viewLength, double contentLength) {
        double scrollable = contentLength - viewLength;
        if (scrollable <= 0) return 0;   // everything fits: there is nothing to scroll
        return Math.clamp((center - viewLength / 2) / scrollable, 0, 1);
    }

    /** The box the cards occupy, in unscaled canvas coordinates. Empty when there are none. */
    private Bounds cardsBoundingBox() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = 0;
        double maxY = 0;
        for (NodeCard c : cards.values()) {
            minX = Math.min(minX, c.getLayoutX());
            minY = Math.min(minY, c.getLayoutY());
            maxX = Math.max(maxX, c.getLayoutX() + c.getWidth());
            maxY = Math.max(maxY, c.getLayoutY() + heightOf(c));
        }
        if (cards.isEmpty()) return new javafx.geometry.BoundingBox(0, 0, 0, 0);
        return new javafx.geometry.BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }

    private static double heightOf(NodeCard card) {
        double height = Math.max(card.getHeight(), card.prefHeight(-1));
        return height > 0 ? height : CARD_HEIGHT_FALLBACK;
    }

    // --- auto-arrange ---

    /**
     * Lays the cards out in layers, left to right: a card's column is the <em>longest</em> path from the start
     * to it, cards sharing a column stack down it, and the whole block is centred on the canvas. Orphans go in
     * a row of their own beneath everything.
     *
     * <p>Three things make the arrows readable, and all three were wrong before:
     * <ul>
     *   <li><b>Longest path, not breadth-first depth.</b> With shortest-path layers a card can land to the left
     *       of one of its own predecessors, so a perfectly ordinary forward wire is drawn backwards.</li>
     *   <li><b>Back-edges are excluded from the layering</b> (found by a depth-first walk). A loop back to an
     *       earlier activity should be the <em>only</em> right-to-left arrow in the picture.</li>
     *   <li><b>Cards are stacked by their real heights</b> and ordered within a column by the average position
     *       of what points at them (the standard barycenter heuristic), so branches don't cross for no reason
     *       and a card with six outcome ports doesn't overlap the one below it.</li>
     * </ul>
     */
    public void autoArrange() {
        // Nothing wired yet: there are no layers to compute, so lay every card out as a uniform grid. Doing this
        // rather than the layer walk (which would place only the start card and leave the rest where they were)
        // is what makes a repeated click idempotent — otherwise centreOnCanvas translates the un-placed cards by
        // an ever-growing delta and they drift further apart on every click.
        if (edges.isEmpty()) {
            gridCards(placedNames(), 0);
            centreOnCanvas();
            refresh();
            recenter();
            return;
        }

        List<String> reachable = chain();
        Map<String, Integer> layers = longestPathLayers(reachable);

        Map<Integer, List<String>> byLayer = new LinkedHashMap<>();
        for (String name : reachable) {
            byLayer.computeIfAbsent(layers.getOrDefault(name, 0), k -> new ArrayList<>()).add(name);
        }

        Map<String, Integer> rowOf = new HashMap<>();
        double bottom = 0;
        for (int layer = 0; layer <= maxLayer(byLayer); layer++) {
            List<String> column = byLayer.getOrDefault(layer, List.of());
            column.sort((a, b) -> Double.compare(barycenter(a, rowOf), barycenter(b, rowOf)));
            double y = 0;
            for (int row = 0; row < column.size(); row++) {
                NodeCard card = cards.get(column.get(row));
                rowOf.put(column.get(row), row);
                placeAt(card, layer * ARRANGE_X, y);
                y += heightOf(card) + ARRANGE_GAP;
            }
            bottom = Math.max(bottom, y);
        }

        // Every placed card the layer walk didn't position — real orphans plus anything unreachable — is laid
        // out on a fresh grid below the arranged block. Giving them fresh positions (rather than leaving them
        // where they were) is what stops centreOnCanvas from feeding stale coordinates back in and drifting
        // them on each click.
        Set<String> arranged = new HashSet<>(reachable);
        List<String> leftovers = new ArrayList<>();
        for (String name : placedNames()) {
            if (!arranged.contains(name)) leftovers.add(name);
        }
        gridCards(leftovers, bottom + ARRANGE_GAP);

        centreOnCanvas();
        refresh();
        recenter();
    }

    /** Lays {@code names} out as a squarish grid whose top edge sits at {@code top}, in placement order. */
    private void gridCards(List<String> names, double top) {
        if (names.isEmpty()) return;
        int columns = (int) Math.ceil(Math.sqrt(names.size()));
        double y = top;
        double rowHeight = 0;
        for (int i = 0; i < names.size(); i++) {
            int col = i % columns;
            if (col == 0 && i > 0) {
                y += rowHeight + ARRANGE_GAP;
                rowHeight = 0;
            }
            NodeCard card = cards.get(names.get(i));
            if (card == null) continue;
            placeAt(card, col * ARRANGE_X, y);
            rowHeight = Math.max(rowHeight, heightOf(card));
        }
    }

    private static int maxLayer(Map<Integer, List<String>> byLayer) {
        int max = 0;
        for (int layer : byLayer.keySet()) max = Math.max(max, layer);
        return max;
    }

    /** The mean row of {@code name}'s already-placed predecessors — where it wants to sit to avoid crossings. */
    private double barycenter(String name, Map<String, Integer> rowOf) {
        double total = 0;
        int count = 0;
        for (FlowEdge e : edges) {
            Integer row = e.to().equals(name) ? rowOf.get(e.from()) : null;
            if (row != null) {
                total += row;
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : total / count;   // no known predecessor: park it at the bottom
    }

    /**
     * Each reachable activity's column: the longest path to it from the start, over the flow with its
     * back-edges removed. Removing them is what keeps a cycle from making "longest path" unbounded.
     */
    private Map<String, Integer> longestPathLayers(List<String> reachable) {
        Set<String> known = new LinkedHashSet<>(reachable);
        Map<String, List<String>> forward = forwardEdges(known);

        Map<String, Integer> layer = new LinkedHashMap<>();
        for (String name : reachable) layer.put(name, 0);
        // Relax |V| times: with the back-edges gone this is a DAG, so that is enough for the longest path.
        for (int pass = 0; pass < reachable.size(); pass++) {
            boolean moved = false;
            for (String from : reachable) {
                for (String to : forward.getOrDefault(from, List.of())) {
                    if (layer.get(to) < layer.get(from) + 1) {
                        layer.put(to, layer.get(from) + 1);
                        moved = true;
                    }
                }
            }
            if (!moved) break;
        }
        return layer;
    }

    /** The wiring with cycles broken: every edge except the ones a depth-first walk finds leading backwards. */
    private Map<String, List<String>> forwardEdges(Set<String> known) {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (FlowEdge e : edges) {
            if (known.contains(e.from()) && known.contains(e.to()) && !e.from().equals(e.to())) {
                all.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            }
        }

        Set<String> onStack = new LinkedHashSet<>();
        Set<String> done = new HashSet<>();
        Map<String, List<String>> forward = new LinkedHashMap<>();
        for (String root : known) {
            if (!done.contains(root)) dropBackEdges(root, all, forward, onStack, done);
        }
        return forward;
    }

    /** Depth-first from {@code node}, copying every edge except those pointing at the current stack. */
    private static void dropBackEdges(String node, Map<String, List<String>> all,
                                      Map<String, List<String>> forward, Set<String> onStack, Set<String> done) {
        onStack.add(node);
        for (String next : all.getOrDefault(node, List.of())) {
            if (onStack.contains(next)) continue;   // a back-edge: this is the loop, not a layout constraint
            forward.computeIfAbsent(node, k -> new ArrayList<>()).add(next);
            if (!done.contains(next)) dropBackEdges(next, all, forward, onStack, done);
        }
        onStack.remove(node);
        done.add(node);
    }

    /** Shifts every card so the block they form sits in the middle of the canvas. */
    private void centreOnCanvas() {
        if (cards.isEmpty()) return;
        Bounds box = cardsBoundingBox();
        double dx = (CANVAS_W - box.getWidth()) / 2 - box.getMinX();
        double dy = (CANVAS_H - box.getHeight()) / 2 - box.getMinY();
        for (NodeCard card : cards.values()) {
            placeAt(card, card.getLayoutX() + dx, card.getLayoutY() + dy);
        }
    }

    private static void placeAt(NodeCard card, double x, double y) {
        card.setLayoutX(Math.max(0, x));
        card.setLayoutY(Math.max(0, y));
        card.draft().moveTo(card.getLayoutX(), card.getLayoutY());
    }

    /** Redraws the wires and re-marks orphan and start cards. Call after any topology or naming change. */
    public void refresh() {
        redrawWires();
        List<String> orphans = orphans();
        String startNow = resolvedStart();
        for (Map.Entry<String, NodeCard> entry : cards.entrySet()) {
            entry.getValue().setOrphan(orphans.contains(entry.getKey()));
            entry.getValue().setStart(entry.getKey().equals(startNow));
        }
        drawMinimap();
        onChainChanged.run();
    }

    // --- canvas gestures: rubber-band select (left) and pan (right/middle) ---

    private void beginCanvasGesture(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            if (e.getTarget() != content) return;   // a card handles its own press
            select(null);
            bandOrigin = new Point2D(e.getX(), e.getY());
            rubberBand.setX(bandOrigin.getX());
            rubberBand.setY(bandOrigin.getY());
            rubberBand.setWidth(0);
            rubberBand.setHeight(0);
            rubberBand.setVisible(true);
        } else {
            panOrigin = new Point2D(e.getSceneX(), e.getSceneY());
            panStartH = scroller.getHvalue();
            panStartV = scroller.getVvalue();
        }
        e.consume();
    }

    private void continueCanvasGesture(MouseEvent e) {
        if (bandOrigin != null) {
            rubberBand.setX(Math.min(bandOrigin.getX(), e.getX()));
            rubberBand.setY(Math.min(bandOrigin.getY(), e.getY()));
            rubberBand.setWidth(Math.abs(e.getX() - bandOrigin.getX()));
            rubberBand.setHeight(Math.abs(e.getY() - bandOrigin.getY()));
        } else if (panOrigin != null) {
            pan(e);
        }
        e.consume();
    }

    /** Drags the view: a scene-space delta divided by the scrollable extent is the h/v change it represents. */
    private void pan(MouseEvent e) {
        Bounds view = scroller.getViewportBounds();
        double scrollableX = CANVAS_W * zoom.getX() - view.getWidth();
        double scrollableY = CANVAS_H * zoom.getY() - view.getHeight();
        if (scrollableX > 0) {
            scroller.setHvalue(Math.clamp(
                    panStartH - (e.getSceneX() - panOrigin.getX()) / scrollableX, 0, 1));
        }
        if (scrollableY > 0) {
            scroller.setVvalue(Math.clamp(
                    panStartV - (e.getSceneY() - panOrigin.getY()) / scrollableY, 0, 1));
        }
    }

    private void endCanvasGesture(MouseEvent e) {
        if (bandOrigin != null) {
            Bounds band = rubberBand.getBoundsInParent();
            List<ActivityDraft> caught = new ArrayList<>();
            for (NodeCard card : cards.values()) {
                if (band.intersects(card.getBoundsInParent())) caught.add(card.draft());
            }
            selectAll(caught);
            rubberBand.setVisible(false);
            bandOrigin = null;
        }
        panOrigin = null;
        e.consume();
    }

    // --- minimap ---

    /**
     * A thumbnail of the whole canvas in the corner, with the current viewport outlined. The canvas is far
     * bigger than any window, so without it there is no way to tell where you are or that a card exists off
     * screen — the same problem {@link #recenter()} rescues you from, but continuously.
     */
    private Node buildMinimap() {
        minimap.setPrefSize(MINIMAP_W, MINIMAP_H);
        minimap.setMaxSize(MINIMAP_W, MINIMAP_H);
        minimap.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-border-color: #c8c8c8;");
        minimapViewport.setFill(Color.web("#4a7ebb", 0.15));
        minimapViewport.setStroke(WIRE);
        minimap.setOnMousePressed(this::jumpFromMinimap);
        minimap.setOnMouseDragged(this::jumpFromMinimap);
        StackPane.setAlignment(minimap, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(minimap, new Insets(0, 16, 16, 0));
        return minimap;
    }

    private void jumpFromMinimap(MouseEvent e) {
        Bounds view = scroller.getViewportBounds();
        double centerX = e.getX() / MINIMAP_W * CANVAS_W * zoom.getX();
        double centerY = e.getY() / MINIMAP_H * CANVAS_H * zoom.getY();
        scroller.setHvalue(scrollFraction(centerX, view.getWidth(), CANVAS_W * zoom.getX()));
        scroller.setVvalue(scrollFraction(centerY, view.getHeight(), CANVAS_H * zoom.getY()));
        e.consume();
    }

    private void drawMinimap() {
        minimap.getChildren().clear();
        for (NodeCard card : cards.values()) {
            Rectangle dot = new Rectangle(
                    card.getLayoutX() / CANVAS_W * MINIMAP_W,
                    card.getLayoutY() / CANVAS_H * MINIMAP_H,
                    Math.max(3, CARD_WIDTH / CANVAS_W * MINIMAP_W),
                    Math.max(2, heightOf(card) / CANVAS_H * MINIMAP_H));
            dot.setFill(card.draft().enabled() ? WIRE : Color.web("#b8b8b8"));
            minimap.getChildren().add(dot);
        }
        minimap.getChildren().add(minimapViewport);
        drawMinimapViewport();
    }

    private void drawMinimapViewport() {
        Bounds view = scroller.getViewportBounds();
        if (view.getWidth() <= 0) return;
        double contentW = CANVAS_W * zoom.getX();
        double contentH = CANVAS_H * zoom.getY();
        double x = scroller.getHvalue() * Math.max(0, contentW - view.getWidth()) / contentW;
        double y = scroller.getVvalue() * Math.max(0, contentH - view.getHeight()) / contentH;
        minimapViewport.setX(x * MINIMAP_W);
        minimapViewport.setY(y * MINIMAP_H);
        minimapViewport.setWidth(Math.min(MINIMAP_W, view.getWidth() / contentW * MINIMAP_W));
        minimapViewport.setHeight(Math.min(MINIMAP_H, view.getHeight() / contentH * MINIMAP_H));
    }

    // --- wiring ---

    private void tryConnect(ActivityDraft from, String outcome, String to) {
        String rejection = FlowRules.rejectionFor(edges, from.name(), outcome, to);
        if (rejection != null) {
            onMessage.accept(rejection);
            return;
        }
        edges.add(new FlowEdge(from.name(), to, outcome));
        onMessage.accept("");
        refresh();
    }

    private void redrawWires() {
        wires.getChildren().clear();
        for (FlowEdge e : edges) {
            NodeCard from = cards.get(e.from());
            NodeCard to = cards.get(e.to());
            if (from == null || to == null) continue; // stale wire; save() drops it
            wires.getChildren().add(buildWire(e, from, to));
        }
    }

    private Node buildWire(FlowEdge edge, NodeCard from, NodeCard to) {
        Point2D start = from.outPortCenter(edge.outcomeOrNext());
        Point2D end = to.inPortCenter();
        CubicCurve curve = styledCurve();
        curve.setStartX(start.getX());
        curve.setStartY(start.getY());
        curve.setEndX(end.getX());
        curve.setEndY(end.getY());
        if (from == to) {
            // A self-wire's two ends are on the same card, so an ordinary S-curve runs straight through it and
            // is invisible. Loop it out to the right, over the top, and back into the input port.
            double lift = heightOf(from) / 2 + 55;
            curve.setControlX1(start.getX() + 70);
            curve.setControlY1(start.getY() - lift);
            curve.setControlX2(end.getX() - 70);
            curve.setControlY2(end.getY() - lift);
        } else {
            // Horizontal control points give the flat S-curve that reads as "flows left to right".
            double bend = Math.max(40, Math.abs(end.getX() - start.getX()) / 2);
            curve.setControlX1(start.getX() + bend);
            curve.setControlY1(start.getY());
            curve.setControlX2(end.getX() - bend);
            curve.setControlY2(end.getY());
        }

        // A wire is symmetrical, so without a head you can't tell "A then B" from "B then A" — the direction
        // is the entire meaning of the wire. The head is aimed along the curve's end tangent (control2 → end)
        // rather than the straight start→end line, so it stays flush with the curve however the cards sit.
        Polygon head = arrowHead(curve.getControlX2(), curve.getControlY2(), end.getX(), end.getY());

        Group wire = new Group(curve, head);
        Tooltip.install(wire, new Tooltip(edge.from() + " — " + edge.outcomeOrNext() + " → " + edge.to()
                + "  (click to remove)"));
        wire.setOnMouseEntered(e -> paintWire(curve, head, WIRE_HOVER));
        wire.setOnMouseExited(e -> paintWire(curve, head, WIRE));
        wire.setOnMouseClicked(e -> {
            edges.remove(edge);
            onMessage.accept("");
            refresh();
        });
        return wire;
    }

    /** A filled triangle at {@code (tipX, tipY)}, pointing away from {@code (fromX, fromY)}. */
    private static Polygon arrowHead(double fromX, double fromY, double tipX, double tipY) {
        double angle = Math.atan2(tipY - fromY, tipX - fromX);
        double spread = Math.toRadians(22);
        Polygon head = new Polygon(
                tipX, tipY,
                tipX - ARROW_LENGTH * Math.cos(angle - spread), tipY - ARROW_LENGTH * Math.sin(angle - spread),
                tipX - ARROW_LENGTH * Math.cos(angle + spread), tipY - ARROW_LENGTH * Math.sin(angle + spread));
        head.setFill(WIRE);
        return head;
    }

    private static void paintWire(CubicCurve curve, Polygon head, Color color) {
        curve.setStroke(color);
        head.setFill(color);
    }

    private static CubicCurve styledCurve() {
        CubicCurve curve = new CubicCurve();
        curve.setStroke(WIRE);
        curve.setStrokeWidth(2.5);
        curve.setFill(null);
        return curve;
    }

    private void startPendingWire(ActivityDraft from, String outcome, Point2D at) {
        pendingFrom = from;
        pendingOutcome = outcome;
        pendingWire = styledCurve();
        pendingWire.getStrokeDashArray().addAll(6.0, 4.0);
        pendingWire.setMouseTransparent(true);
        movePendingWire(at, at);
        wires.getChildren().add(pendingWire);
    }

    private void movePendingWire(Point2D start, Point2D end) {
        pendingWire.setStartX(start.getX());
        pendingWire.setStartY(start.getY());
        pendingWire.setEndX(end.getX());
        pendingWire.setEndY(end.getY());
        pendingWire.setControlX1(start.getX() + 40);
        pendingWire.setControlY1(start.getY());
        pendingWire.setControlX2(end.getX() - 40);
        pendingWire.setControlY2(end.getY());
    }

    private void finishPendingWire(Point2D at) {
        wires.getChildren().remove(pendingWire);
        pendingWire = null;
        String target = cardIdAt(at);
        ActivityDraft from = pendingFrom;
        String outcome = pendingOutcome;
        pendingFrom = null;
        pendingOutcome = null;
        if (target != null && from != null) tryConnect(from, outcome, target);
    }

    /** The activity name of the card containing the given content-space point, or null. */
    private String cardIdAt(Point2D at) {
        for (Map.Entry<String, NodeCard> entry : cards.entrySet()) {
            if (entry.getValue().getBoundsInParent().contains(at)) return entry.getKey();
        }
        return null;
    }

    // --- cards ---

    /**
     * One activity's card: a title, its enable and go-home toggles, a param summary, and a labelled output port
     * per outcome. Dragging the body moves the card — and everything else selected — writing each new position
     * straight back into its draft; dragging an outcome's ▶ port draws a wire for that outcome.
     */
    private final class NodeCard extends HBox {

        private final ActivityDraft draft;
        private final Label orphanNote = new Label("not wired — won't run");
        private final Label startBadge = new Label("▶ start");
        private final VBox body = new VBox(2);
        private final VBox ports = new VBox(4);
        private final Map<String, Circle> outPorts = new LinkedHashMap<>();

        private boolean selectedNow;
        private Point2D dragAnchor;
        private Map<ActivityDraft, Point2D> dragStartPositions = Map.of();

        NodeCard(ActivityDraft draft) {
            super(6);
            setAlignment(Pos.CENTER);
            this.draft = draft;
            setLayoutX(draft.x());
            setLayoutY(draft.y());
            // A card's size isn't known until it has been laid out, and the ports hang off its edges — so
            // re-draw the wires once the real bounds arrive (and again whenever the card resizes).
            layoutBoundsProperty().addListener((o, was, is) -> redrawWires());

            Label title = new Label();
            title.textProperty().bind(draft.nameProperty());
            title.setStyle("-fx-font-weight: bold;");

            CheckBox enabled = new CheckBox();
            enabled.selectedProperty().bindBidirectional(draft.enabledProperty());
            enabled.setTooltip(new Tooltip("Run this activity (the flow still passes through it when off)"));

            CheckBox goHome = new CheckBox("⌂");
            goHome.selectedProperty().bindBidirectional(draft.goHomeProperty());
            goHome.setTooltip(new Tooltip("Go back to the home screen before running this activity"));

            startBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #2e7d32; -fx-font-weight: bold;");
            startBadge.setManaged(false);
            startBadge.setVisible(false);

            HBox header = new HBox(8, title, enabled, goHome, startBadge);
            header.setAlignment(Pos.CENTER_LEFT);

            Label params = new Label();
            params.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
            params.setWrapText(true);
            draft.params().addListener((javafx.collections.ListChangeListener<Object>) c ->
                    params.setText(ActivityValueWidgets.summarize(draft.params(), 2)));
            params.setText(ActivityValueWidgets.summarize(draft.params(), 2));

            orphanNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #b06000;");
            orphanNote.setManaged(false);
            orphanNote.setVisible(false);

            body.setPrefWidth(CARD_WIDTH);
            body.setMinWidth(CARD_WIDTH);
            body.getChildren().addAll(header, params, orphanNote);
            body.setPadding(new Insets(8, 10, 8, 10));

            ports.setAlignment(Pos.CENTER_LEFT);
            rebuildPorts();
            // Adding an outcome in the side panel has to put a port on the card straight away — otherwise
            // there is nothing to drag the new wire from until the dialog is reopened.
            draft.outcomes().addListener((javafx.collections.ListChangeListener<String>) c -> {
                rebuildPorts();
                dropWiresForRemovedOutcomes();
                refresh();
            });

            // The input port is decoration only — a wire's endpoint is computed from the card's geometry
            // (inPortCenter), so nothing needs to hold on to the circle.
            getChildren().addAll(port("#8a8a8a"), body, ports);
            setSelected(false);
            // Cards greyed out when disabled: the flow still passes through, the activity just doesn't run.
            draft.enabledProperty().addListener((o, was, is) -> {
                restyle();
                drawMinimap();
            });
            draft.nameProperty().addListener((o, was, is) -> renamed(was, is));

            body.setOnMousePressed(this::beginDrag);
            body.setOnMouseDragged(this::continueDrag);
            body.setOnContextMenuRequested(e -> cardMenu().show(body, e.getScreenX(), e.getScreenY()));
        }

        ActivityDraft draft() {
            return draft;
        }

        private ContextMenu cardMenu() {
            MenuItem startHere = new MenuItem("Start the flow here");
            startHere.setOnAction(e -> setStartTo(draft.name()));
            return new ContextMenu(startHere);
        }

        /** One labelled port per outcome, in the order the enum will declare them. */
        private void rebuildPorts() {
            ports.getChildren().clear();
            outPorts.clear();
            for (String outcome : draft.allOutcomes()) {
                Circle circle = port("#4a7ebb");
                installPortHandlers(circle, outcome);
                outPorts.put(outcome, circle);
                Label label = new Label(FlowEdge.NEXT_OUTCOME.equals(outcome) ? "then" : outcome);
                label.setStyle("-fx-font-size: 9px; -fx-text-fill: #666;");
                HBox row = new HBox(3, label, circle);
                row.setAlignment(Pos.CENTER_RIGHT);
                ports.getChildren().add(row);
            }
        }

        /** Drops any wire whose outcome the activity no longer declares — its port has just disappeared. */
        private void dropWiresForRemovedOutcomes() {
            Set<String> live = new LinkedHashSet<>(draft.allOutcomes());
            edges.removeIf(e -> e.from().equals(draft.name()) && !live.contains(e.outcomeOrNext()));
        }

        Point2D inPortCenter() {
            return new Point2D(getLayoutX() + PORT_RADIUS, getLayoutY() + getHeight() / 2);
        }

        Point2D outPortCenter(String outcome) {
            Circle circle = outPorts.get(outcome);
            if (circle == null) return new Point2D(getLayoutX() + getWidth(), getLayoutY() + getHeight() / 2);
            Bounds bounds = circle.getBoundsInParent();
            Bounds row = circle.getParent().getBoundsInParent();
            return new Point2D(getLayoutX() + ports.getLayoutX() + row.getMinX() + bounds.getWidth() / 2
                    + bounds.getMinX(),
                    getLayoutY() + ports.getLayoutY() + row.getMinY() + bounds.getMinY()
                            + bounds.getHeight() / 2);
        }

        private void renamed(String oldName, String newName) {
            cards.remove(oldName);
            cards.put(newName, this);
            if (oldName.equals(start)) start = newName;
            List<FlowEdge> rewired = new ArrayList<>(edges.size());
            for (FlowEdge e : edges) {
                rewired.add(e.rewired(e.from().equals(oldName) ? newName : e.from(),
                        e.to().equals(oldName) ? newName : e.to()));
            }
            edges.setAll(rewired);
            refresh();
        }

        private void beginDrag(MouseEvent e) {
            if (e.getButton() != MouseButton.PRIMARY) return;   // let a right-drag bubble up and pan
            // Dragging an unselected card selects it first, so a drag never moves something you can't see.
            if (!selection.contains(draft)) select(draft);
            dragAnchor = new Point2D(e.getSceneX(), e.getSceneY());
            Map<ActivityDraft, Point2D> starts = new HashMap<>();
            for (ActivityDraft d : selection) starts.put(d, new Point2D(d.x(), d.y()));
            dragStartPositions = starts;
            e.consume();
        }

        private void continueDrag(MouseEvent e) {
            if (dragAnchor == null) return;
            double dx = (e.getSceneX() - dragAnchor.getX()) / zoom.getX();
            double dy = (e.getSceneY() - dragAnchor.getY()) / zoom.getY();
            for (Map.Entry<ActivityDraft, Point2D> entry : dragStartPositions.entrySet()) {
                NodeCard card = cards.get(entry.getKey().name());
                if (card == null) continue;
                placeAt(card, entry.getValue().getX() + dx, entry.getValue().getY() + dy);
            }
            redrawWires();
            drawMinimap();
            e.consume();
        }

        private void installPortHandlers(Circle circle, String outcome) {
            Tooltip.install(circle, new Tooltip(FlowEdge.NEXT_OUTCOME.equals(outcome)
                    ? "Drag to the activity that runs next when there's nothing special to report"
                    : "Drag to the activity that runs next when " + draft.name() + " reports " + outcome));
            circle.setOnMousePressed(e -> {
                startPendingWire(draft, outcome, outPortCenter(outcome));
                e.consume();
            });
            circle.setOnMouseDragged(e -> {
                if (pendingWire != null) movePendingWire(outPortCenter(outcome), toContent(e));
                e.consume();
            });
            circle.setOnMouseReleased(e -> {
                if (pendingWire != null) finishPendingWire(toContent(e));
                e.consume();
            });
        }

        private Point2D toContent(MouseEvent e) {
            return content.sceneToLocal(e.getSceneX(), e.getSceneY());
        }

        void setSelected(boolean isSelected) {
            this.selectedNow = isSelected;
            restyle();
        }

        void setOrphan(boolean orphan) {
            orphanNote.setManaged(orphan);
            orphanNote.setVisible(orphan);
        }

        void setStart(boolean isStart) {
            startBadge.setManaged(isStart);
            startBadge.setVisible(isStart);
        }

        void restyle() {
            String border = selectedNow ? "#4a7ebb" : "#c8c8c8";
            String background = draft.enabled() ? "white" : "#eeeeee";
            body.setStyle("-fx-background-color: " + background + ";"
                    + "-fx-border-color: " + border + ";"
                    + "-fx-border-width: " + (selectedNow ? 2 : 1) + ";"
                    + "-fx-border-radius: 6; -fx-background-radius: 6;");
        }
    }

    private void setStartTo(String activity) {
        start = activity;
        onMessage.accept("The flow now starts at '" + activity + "'.");
        refresh();
    }

    private static Circle port(String color) {
        Circle c = new Circle(PORT_RADIUS, Color.web(color));
        c.setStroke(Color.WHITE);
        return c;
    }
}
