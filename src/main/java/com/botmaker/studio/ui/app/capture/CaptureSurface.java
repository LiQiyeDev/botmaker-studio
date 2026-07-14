package com.botmaker.studio.ui.app.capture;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The transparent, always-on-top rubber-band surface shown <em>only while drawing</em> a template region
 * over the live window (it is created, shown, and disposed per capture session by {@link OverlayTemplateCapture}
 * — the persistent mini-toolbar never covers the window, which keeps the window clickable between captures).
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Mode#SINGLE} — the first rubber-band fires {@code onRegion} once (the caller then hides the
 *       surface, crops, and names it).</li>
 *   <li>{@link Mode#MANY} — each rubber-band is kept on screen with an index badge and recorded; a
 *       {@code Done (N)} / {@code Cancel} bar ends the pass, firing {@code onDone} with all recorded regions.</li>
 * </ul>
 * Escape cancels the surface in either mode ({@code onCancel}); it does not tear down the whole tool.
 */
public final class CaptureSurface {

    /** How the surface collects regions — one-shot ({@link #SINGLE}) or accumulate-then-done ({@link #MANY}). */
    public enum Mode { SINGLE, MANY }

    /**
     * A drawn selection in the surface's own (overlay-logical) pixels, together with the surface size it was
     * drawn on ({@code paneW}/{@code paneH}) so it can be scaled onto the captured physical image later.
     */
    public record Region(double x, double y, double w, double h, double paneW, double paneH) {}

    private final Stage stage;
    private final Pane pane;
    private final Mode mode;
    private final Consumer<Region> onRegion;
    private final Consumer<List<Region>> onDone;
    private final Runnable onCancel;

    private final Rectangle rubberBand = new Rectangle();
    private final List<Region> regions = new ArrayList<>();
    private Button doneButton;
    private boolean finished;

    private CaptureSurface(Window owner, java.awt.Rectangle bounds, Mode mode,
                           Consumer<Region> onRegion, Consumer<List<Region>> onDone, Runnable onCancel) {
        this.mode = mode;
        this.onRegion = onRegion;
        this.onDone = onDone;
        this.onCancel = onCancel;

        rubberBand.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
        rubberBand.setStroke(Color.web("#2f80ed"));
        rubberBand.setStrokeWidth(1.5);
        rubberBand.setVisible(false);
        rubberBand.setMouseTransparent(true);

        pane = new Pane(rubberBand);
        // A faint tint gives the transparent surface a pickable body (so drags register) and signals that
        // capture mode is active, while keeping the live window clearly visible underneath.
        pane.setStyle("-fx-background-color: rgba(20,110,220,0.06);");
        pane.getChildren().add(buildControlBar());
        installDrawHandlers();

        stage = new Stage(StageStyle.TRANSPARENT);
        // Deliberately ownerless: an owned stage minimizes with the Studio window; the capture surface must
        // stay put so the user can capture while Studio is out of the way.
        stage.setAlwaysOnTop(true);
        stage.setX(bounds.x);
        stage.setY(bounds.y);
        stage.setWidth(bounds.width);
        stage.setHeight(bounds.height);

        Scene scene = new Scene(pane, bounds.width, bounds.height, Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancel(); });
        stage.setScene(scene);
    }

    /** Opens a one-shot surface: the first drawn region fires {@code onRegion}; Esc/Cancel fires {@code onCancel}. */
    public static CaptureSurface single(Window owner, java.awt.Rectangle bounds,
                                        Consumer<Region> onRegion, Runnable onCancel) {
        CaptureSurface s = new CaptureSurface(owner, bounds, Mode.SINGLE, onRegion, null, onCancel);
        s.stage.show();
        com.botmaker.studio.ui.app.overlay.OverlayToolbars.promoteAboveFullscreen(s.stage);
        return s;
    }

    /** Opens a multi-region surface: draw several, then Done fires {@code onDone}; Esc/Cancel fires {@code onCancel}. */
    public static CaptureSurface many(Window owner, java.awt.Rectangle bounds,
                                      Consumer<List<Region>> onDone, Runnable onCancel) {
        CaptureSurface s = new CaptureSurface(owner, bounds, Mode.MANY, null, onDone, onCancel);
        s.stage.show();
        com.botmaker.studio.ui.app.overlay.OverlayToolbars.promoteAboveFullscreen(s.stage);
        return s;
    }

    /** Hides the surface without disposing it — used before an off-thread window re-capture. */
    public void hide() {
        stage.hide();
    }

    /** Disposes the surface stage. */
    public void close() {
        stage.close();
    }

    private HBox buildControlBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: rgba(0,0,0,0.72); -fx-background-radius: 6;");
        bar.setLayoutX(12);
        bar.setLayoutY(12);

        Label hint = new Label(mode == Mode.SINGLE
                ? "Draw a region over the window · Esc to cancel"
                : "Draw regions over the window, then Done · Esc to cancel");
        hint.setTextFill(Color.WHITE);

        Button cancel = new Button("✕ Cancel");
        cancel.setOnAction(e -> cancel());

        if (mode == Mode.MANY) {
            doneButton = new Button("✓ Done (0)");
            doneButton.setOnAction(e -> finish());
            bar.getChildren().addAll(doneButton, cancel, hint);
        } else {
            bar.getChildren().addAll(cancel, hint);
        }
        return bar;
    }

    private void installDrawHandlers() {
        final double[] origin = new double[2];
        pane.setOnMousePressed(e -> {
            if (finished || inControlBar(e.getX(), e.getY())) return;
            origin[0] = e.getX();
            origin[1] = e.getY();
            rubberBand.setX(e.getX());
            rubberBand.setY(e.getY());
            rubberBand.setWidth(0);
            rubberBand.setHeight(0);
            rubberBand.setVisible(true);
            rubberBand.toFront();
        });
        pane.setOnMouseDragged(e -> {
            if (finished || !rubberBand.isVisible()) return;
            rubberBand.setX(Math.min(origin[0], e.getX()));
            rubberBand.setY(Math.min(origin[1], e.getY()));
            rubberBand.setWidth(Math.abs(e.getX() - origin[0]));
            rubberBand.setHeight(Math.abs(e.getY() - origin[1]));
        });
        pane.setOnMouseReleased(e -> {
            if (finished || !rubberBand.isVisible()) return;
            double x = rubberBand.getX(), y = rubberBand.getY();
            double w = rubberBand.getWidth(), h = rubberBand.getHeight();
            rubberBand.setVisible(false);
            if (w < 3 || h < 3) return;
            Region region = new Region(x, y, w, h, pane.getWidth(), pane.getHeight());
            if (mode == Mode.SINGLE) {
                finished = true;
                onRegion.accept(region);
            } else {
                addRegion(region);
            }
        });
    }

    /** Records a region and marks it on the surface with a persistent outline + index badge (MANY mode). */
    private void addRegion(Region region) {
        regions.add(region);

        Rectangle mark = new Rectangle(region.x(), region.y(), region.w(), region.h());
        mark.setFill(Color.color(0.3, 0.6, 1.0, 0.15));
        mark.setStroke(Color.web("#2f80ed"));
        mark.setStrokeWidth(1.5);
        mark.setMouseTransparent(true);

        Label badge = new Label(String.valueOf(regions.size()));
        badge.setTextFill(Color.WHITE);
        badge.setStyle("-fx-background-color: rgba(47,128,237,0.9); -fx-background-radius: 3; -fx-padding: 0 4 0 4;");
        badge.setMouseTransparent(true);
        badge.setLayoutX(region.x() + 2);
        badge.setLayoutY(region.y() + 2);

        pane.getChildren().addAll(mark, badge);
        rubberBand.toFront();
        if (doneButton != null) doneButton.setText("✓ Done (" + regions.size() + ")");
    }

    private void finish() {
        if (finished) return;
        finished = true;
        onDone.accept(List.copyOf(regions));
    }

    private void cancel() {
        if (finished) return;
        finished = true;
        onCancel.run();
    }

    /** True when ({@code x},{@code y}) falls within the control bar so a click there isn't a rubber-band. */
    private boolean inControlBar(double x, double y) {
        for (var node : pane.getChildren()) {
            if (node instanceof HBox bar) return bar.getBoundsInParent().contains(x, y);
        }
        return false;
    }
}
