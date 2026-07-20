package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.FlowEdge;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
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
import javafx.scene.transform.Scale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The free-form Activity Flow canvas: each activity is a draggable card, and a wire from one card's output
 * port to another's input port says "when this finishes, that runs next". Pan with the scrollbars, zoom with
 * Ctrl+scroll, drag a wire from the ▶ port, click a wire to delete it.
 *
 * <p>The canvas keeps the flow a single linear chain — {@link ChainRules} vetoes a fork, a join, a self-wire
 * or anything that would loop, and the attempted connection is simply dropped with a message. Cards outside
 * the chain are marked as orphans ("won't run"); that is a warning, not a blocked action, because while you
 * are still wiring most cards are legitimately unconnected.
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

    private final Pane content = new Pane();
    private final Group wires = new Group();
    private final Scale zoom = new Scale(1, 1);

    private final ObservableList<ActivityDraft> drafts = FXCollections.observableArrayList();
    private final ObservableList<FlowEdge> edges = FXCollections.observableArrayList();
    private final Map<String, NodeCard> cards = new LinkedHashMap<>();

    private final ObjectProperty<ActivityDraft> selected = new SimpleObjectProperty<>();

    /** Where inline feedback ("that would make a loop") goes — the dialog's status label. */
    private Consumer<String> onMessage = m -> {};
    /** Fired whenever the wiring changes, so the dialog can refresh its run-order preview. */
    private Runnable onChainChanged = () -> {};

    private CubicCurve pendingWire;
    private ActivityDraft pendingFrom;

    public FlowCanvas() {
        content.setPrefSize(CANVAS_W, CANVAS_H);
        content.setStyle("-fx-background-color: #fafafa;");
        content.getTransforms().add(zoom);
        content.getChildren().addAll(gridBackground(), wires);

        ScrollPane scroller = new ScrollPane(new Group(content));
        scroller.setPannable(true);
        scroller.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) return;
            double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double next = clamp(zoom.getX() * factor, MIN_ZOOM, MAX_ZOOM);
            zoom.setX(next);
            zoom.setY(next);
            e.consume();
        });
        // Clicking empty canvas clears the selection (and any half-drawn wire).
        content.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getTarget() == content) select(null);
        });

        getChildren().add(scroller);
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

    // --- contents ---

    public ObservableList<ActivityDraft> drafts() { return drafts; }

    public ObservableList<FlowEdge> edges() { return edges; }

    public ObjectProperty<ActivityDraft> selectedProperty() { return selected; }

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
        if (selected.get() == draft) select(null);
        refresh();
    }

    public void select(ActivityDraft draft) {
        selected.set(draft);
        for (NodeCard c : cards.values()) c.setSelected(c.draft == draft);
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

    /** The current run order, exactly as the generator will linearize it. */
    public List<String> chain() {
        return ChainRules.chain(placedNames(), edges);
    }

    /** Placed activities that the chain never reaches — they won't run. */
    public List<String> orphans() {
        return ChainRules.orphans(placedNames(), edges);
    }

    private List<String> placedNames() {
        List<String> names = new ArrayList<>(drafts.size());
        for (ActivityDraft d : drafts) names.add(d.name());
        return names;
    }

    /** Redraws the wires and re-marks orphan cards. Call after any topology or naming change. */
    public void refresh() {
        redrawWires();
        List<String> orphans = orphans();
        for (NodeCard c : cards.values()) c.setOrphan(orphans.contains(c.draft.name()));
        onChainChanged.run();
    }

    // --- wiring ---

    private void tryConnect(ActivityDraft from, ActivityDraft to) {
        String rejection = ChainRules.rejectionFor(edges, from.name(), to.name());
        if (rejection != null) {
            onMessage.accept(rejection);
            return;
        }
        edges.add(new FlowEdge(from.name(), to.name()));
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

    private CubicCurve buildWire(FlowEdge edge, NodeCard from, NodeCard to) {
        Point2D start = from.outPortCenter();
        Point2D end = to.inPortCenter();
        CubicCurve curve = styledCurve();
        curve.setStartX(start.getX());
        curve.setStartY(start.getY());
        curve.setEndX(end.getX());
        curve.setEndY(end.getY());
        // Horizontal control points give the flat S-curve that reads as "flows left to right".
        double bend = Math.max(40, Math.abs(end.getX() - start.getX()) / 2);
        curve.setControlX1(start.getX() + bend);
        curve.setControlY1(start.getY());
        curve.setControlX2(end.getX() - bend);
        curve.setControlY2(end.getY());

        Tooltip.install(curve, new Tooltip(edge.from() + " → " + edge.to() + "  (click to remove)"));
        curve.setOnMouseEntered(e -> curve.setStroke(Color.web("#b00020")));
        curve.setOnMouseExited(e -> curve.setStroke(Color.web("#4a7ebb")));
        curve.setOnMouseClicked(e -> {
            edges.remove(edge);
            onMessage.accept("");
            refresh();
        });
        return curve;
    }

    private static CubicCurve styledCurve() {
        CubicCurve curve = new CubicCurve();
        curve.setStroke(Color.web("#4a7ebb"));
        curve.setStrokeWidth(2.5);
        curve.setFill(null);
        return curve;
    }

    private void startPendingWire(ActivityDraft from, Point2D at) {
        pendingFrom = from;
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
        ActivityDraft target = cardAt(at);
        ActivityDraft from = pendingFrom;
        pendingFrom = null;
        if (target != null && from != null) tryConnect(from, target);
    }

    /** The activity whose card contains the given content-space point, or null. */
    private ActivityDraft cardAt(Point2D at) {
        for (NodeCard c : cards.values()) {
            if (c.getBoundsInParent().contains(at)) return c.draft;
        }
        return null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // --- the node card ---

    /**
     * One activity's card: a title, its enable toggle, a param summary, and the two ports. Dragging the body
     * moves the card (writing the position straight back into the draft); dragging the ▶ output port draws a
     * wire.
     */
    private final class NodeCard extends HBox {

        private final ActivityDraft draft;
        private final Circle inPort = port("#8a8a8a");
        private final Circle outPort = port("#4a7ebb");
        private final Label orphanNote = new Label("not wired — won't run");
        private final VBox body = new VBox(2);

        private double dragOffsetX;
        private double dragOffsetY;

        NodeCard(ActivityDraft draft) {
            super(6);
            this.draft = draft;
            setAlignment(javafx.geometry.Pos.CENTER);
            setLayoutX(draft.x());
            setLayoutY(draft.y());

            Label title = new Label();
            title.textProperty().bind(draft.nameProperty());
            title.setStyle("-fx-font-weight: bold;");

            CheckBox enabled = new CheckBox();
            enabled.selectedProperty().bindBidirectional(draft.enabledProperty());
            enabled.setTooltip(new Tooltip("Run this activity"));

            HBox header = new HBox(8, title, enabled);
            header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

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
            body.setPadding(new javafx.geometry.Insets(8, 10, 8, 10));

            getChildren().addAll(inPort, body, outPort);
            setSelected(false);
            // Cards greyed out when disabled: the flow still passes through, the activity just doesn't run.
            draft.enabledProperty().addListener((o, was, is) -> restyle());
            draft.nameProperty().addListener((o, was, is) -> renamed(was, is));

            // A card's size isn't known until it has been laid out, and the ports hang off its edges — so
            // re-draw the wires once the real bounds arrive (and again whenever the card resizes).
            layoutBoundsProperty().addListener((o, was, is) -> redrawWires());

            body.setOnMousePressed(this::beginDrag);
            body.setOnMouseDragged(this::continueDrag);
            installPortHandlers();
        }

        private void renamed(String oldName, String newName) {
            cards.remove(oldName);
            cards.put(newName, this);
            List<FlowEdge> rewired = new ArrayList<>(edges.size());
            for (FlowEdge e : edges) {
                rewired.add(new FlowEdge(e.from().equals(oldName) ? newName : e.from(),
                        e.to().equals(oldName) ? newName : e.to()));
            }
            edges.setAll(rewired);
            refresh();
        }

        private void beginDrag(MouseEvent e) {
            select(draft);
            dragOffsetX = e.getSceneX() - getLayoutX() * zoom.getX();
            dragOffsetY = e.getSceneY() - getLayoutY() * zoom.getY();
            e.consume();
        }

        private void continueDrag(MouseEvent e) {
            double x = Math.max(0, (e.getSceneX() - dragOffsetX) / zoom.getX());
            double y = Math.max(0, (e.getSceneY() - dragOffsetY) / zoom.getY());
            setLayoutX(x);
            setLayoutY(y);
            draft.moveTo(x, y);
            redrawWires();
            e.consume();
        }

        private void installPortHandlers() {
            outPort.setOnMousePressed(e -> {
                startPendingWire(draft, outPortCenter());
                e.consume();
            });
            outPort.setOnMouseDragged(e -> {
                if (pendingWire != null) movePendingWire(outPortCenter(), toContent(e));
                e.consume();
            });
            outPort.setOnMouseReleased(e -> {
                if (pendingWire != null) finishPendingWire(toContent(e));
                e.consume();
            });
        }

        private Point2D toContent(MouseEvent e) {
            return content.sceneToLocal(e.getSceneX(), e.getSceneY());
        }

        Point2D outPortCenter() {
            return new Point2D(getLayoutX() + getWidth() - PORT_RADIUS, getLayoutY() + getHeight() / 2);
        }

        Point2D inPortCenter() {
            return new Point2D(getLayoutX() + PORT_RADIUS, getLayoutY() + getHeight() / 2);
        }

        void setSelected(boolean isSelected) {
            this.selectedNow = isSelected;
            restyle();
        }

        void setOrphan(boolean orphan) {
            orphanNote.setManaged(orphan);
            orphanNote.setVisible(orphan);
        }

        private boolean selectedNow;

        private void restyle() {
            String border = selectedNow ? "#4a7ebb" : "#c8c8c8";
            String background = draft.enabled() ? "white" : "#eeeeee";
            body.setStyle("-fx-background-color: " + background + ";"
                    + "-fx-border-color: " + border + ";"
                    + "-fx-border-width: " + (selectedNow ? 2 : 1) + ";"
                    + "-fx-border-radius: 6; -fx-background-radius: 6;");
        }

        private Circle port(String color) {
            Circle c = new Circle(PORT_RADIUS, Color.web(color));
            c.setStroke(Color.WHITE);
            return c;
        }
    }
}
