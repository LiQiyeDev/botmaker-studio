package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The "Capture object" surface: shows a <em>frozen</em> snapshot of the target window and lets the user cut an
 * object out of it with a transparent background, via {@link MagicWand}'s GrabCut segmentation. Freezing the
 * frame (rather than working against the live window) keeps what the user sees exactly aligned with the pixels
 * being segmented.
 *
 * <p>Interaction is two-phase:
 * <ul>
 *   <li><b>Box</b> — drag a rectangle around the object. On release GrabCut solves and previews the result
 *       (blue tint).</li>
 *   <li><b>Refine</b> — drag with the <b>left</b> button to paint definite <em>foreground</em>, the
 *       <b>right</b> button to paint definite <em>background</em>, wherever GrabCut guessed wrong. Each
 *       release re-solves, continuing from the previous models.</li>
 *   <li><b>✓ Capture</b> — fires {@code onExtract} with the transparent-background crop.</li>
 *   <li><b>Esc / Cancel</b> — fires {@code onCancel} (returns to the mini-toolbar).</li>
 * </ul>
 *
 * <p>GrabCut takes hundreds of milliseconds, so every solve runs on a worker thread with a busy indicator;
 * input is ignored while one is in flight.
 */
public final class ObjectCaptureSurface {

    /** Brush radius in image pixels for refinement strokes. */
    private static final int BRUSH_RADIUS = 6;

    private final Stage stage;
    private final BufferedImage frame;      // the frozen window snapshot, physical pixels
    private final MagicWand.Session session;
    private final double scaleX, scaleY;    // image px per surface-logical px
    private final Consumer<BufferedImage> onExtract;
    private final Runnable onCancel;

    private final Pane pane = new Pane();
    private final ImageView preview = new ImageView();   // blue-tinted mask overlay
    private final Canvas strokeLayer;                    // transient stroke trail
    private final Rectangle band = new Rectangle();      // rubber band while boxing
    private final Label hud = new Label();
    private final Button captureBtn = new Button("✓ Capture");
    private final ProgressIndicator busySpinner = new ProgressIndicator();

    private MagicWand.Result lastResult;
    private boolean busy = false;
    private boolean boxed = false;          // true once the initial rect solve has run
    private double dragStartX, dragStartY;
    private boolean paintingForeground = true;

    private ObjectCaptureSurface(Window owner, java.awt.Rectangle bounds, BufferedImage frame,
                                 Consumer<BufferedImage> onExtract, Runnable onCancel) {
        this.frame = frame;
        this.session = new MagicWand.Session(frame);
        this.onExtract = onExtract;
        this.onCancel = onCancel;
        this.scaleX = frame.getWidth() / (double) bounds.width;
        this.scaleY = frame.getHeight() / (double) bounds.height;

        // Show the frozen frame as the surface body so the user points at exactly what will be captured.
        ImageView background = new ImageView(ScreenCaptureService.toFxImage(frame));
        background.setFitWidth(bounds.width);
        background.setFitHeight(bounds.height);
        preview.setMouseTransparent(true);

        strokeLayer = new Canvas(bounds.width, bounds.height);
        strokeLayer.setMouseTransparent(true);

        band.setFill(Color.TRANSPARENT);
        band.setStroke(Color.web("#4da3ff"));
        band.setStrokeWidth(1.5);
        band.getStrokeDashArray().addAll(6.0, 4.0);
        band.setVisible(false);
        band.setMouseTransparent(true);

        pane.getChildren().addAll(background, preview, strokeLayer, band, buildControlBar(bounds));
        pane.setStyle("-fx-background-color: rgba(10,14,20,0.15);");
        installHandlers();

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setX(bounds.x);
        stage.setY(bounds.y);
        stage.setWidth(bounds.width);
        stage.setHeight(bounds.height);
        Scene scene = new Scene(pane, bounds.width, bounds.height, Color.TRANSPARENT);
        scene.setOnKeyPressed(this::onKeyPressed);
        stage.setScene(scene);
    }

    /** Opens the object-capture surface over {@code frame} (the frozen snapshot of {@code bounds}). */
    public static ObjectCaptureSurface open(Window owner, java.awt.Rectangle bounds, BufferedImage frame,
                                            Consumer<BufferedImage> onExtract, Runnable onCancel) {
        ObjectCaptureSurface s = new ObjectCaptureSurface(owner, bounds, frame, onExtract, onCancel);
        s.stage.show();
        OverlayToolbars.promoteAboveFullscreen(s.stage);
        return s;
    }

    public void hide() { stage.hide(); }

    public void close() {
        session.close();
        stage.close();
    }

    private HBox buildControlBar(java.awt.Rectangle bounds) {
        hud.setTextFill(Color.web("#e8eefb"));

        busySpinner.setPrefSize(16, 16);
        busySpinner.setVisible(false);

        captureBtn.setOnAction(e -> finalizeSelection());
        captureBtn.setDisable(true);

        Button cancel = new Button("✕ Cancel");
        cancel.setOnAction(e -> cancel());

        HBox bar = new HBox(10, busySpinner, hud, captureBtn, cancel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;");
        bar.setLayoutX(12);
        bar.setLayoutY(12);
        updateHud();
        return bar;
    }

    private void installHandlers() {
        pane.setOnMousePressed(e -> {
            if (busy) return;
            dragStartX = e.getX();
            dragStartY = e.getY();
            if (!boxed) {
                band.setX(dragStartX);
                band.setY(dragStartY);
                band.setWidth(0);
                band.setHeight(0);
                band.setVisible(true);
            } else {
                paintingForeground = e.getButton() != MouseButton.SECONDARY;
                beginStrokeTrail();
                paintAt(e.getX(), e.getY());
            }
        });

        pane.setOnMouseDragged(e -> {
            if (busy) return;
            if (!boxed) {
                band.setX(Math.min(dragStartX, e.getX()));
                band.setY(Math.min(dragStartY, e.getY()));
                band.setWidth(Math.abs(e.getX() - dragStartX));
                band.setHeight(Math.abs(e.getY() - dragStartY));
            } else {
                paintAt(e.getX(), e.getY());
            }
        });

        pane.setOnMouseReleased(e -> {
            if (busy) return;
            if (!boxed) {
                band.setVisible(false);
                int x = (int) Math.round(Math.min(dragStartX, e.getX()) * scaleX);
                int y = (int) Math.round(Math.min(dragStartY, e.getY()) * scaleY);
                int w = (int) Math.round(Math.abs(e.getX() - dragStartX) * scaleX);
                int h = (int) Math.round(Math.abs(e.getY() - dragStartY) * scaleY);
                solve(() -> session.initFromRect(x, y, w, h, MagicWand.DEFAULT_ITERATIONS));
            } else {
                solve(() -> session.refine(MagicWand.DEFAULT_ITERATIONS));
            }
        });
    }

    /** Runs a GrabCut solve off the FX thread, then republishes the preview. */
    private void solve(java.util.function.Supplier<MagicWand.Result> work) {
        busy = true;
        busySpinner.setVisible(true);
        updateHud();
        Thread t = new Thread(() -> {
            MagicWand.Result r;
            try {
                r = work.get();
            } catch (RuntimeException ex) {
                r = null;
                ex.printStackTrace();
            }
            MagicWand.Result done = r;
            Platform.runLater(() -> {
                busy = false;
                busySpinner.setVisible(false);
                clearStrokeTrail();
                if (done != null) {
                    lastResult = done;
                    boxed = true;
                    showPreview(done);
                }
                captureBtn.setDisable(lastResult == null || lastResult.isEmpty());
                updateHud();
            });
        }, "grabcut-solve");
        t.setDaemon(true);
        t.start();
    }

    private void paintAt(double paneX, double paneY) {
        int ix = clamp((int) Math.round(paneX * scaleX), frame.getWidth() - 1);
        int iy = clamp((int) Math.round(paneY * scaleY), frame.getHeight() - 1);
        session.paint(ix, iy, BRUSH_RADIUS, paintingForeground);
        GraphicsContext g = strokeLayer.getGraphicsContext2D();
        g.setFill(paintingForeground ? Color.web("#48e06a", 0.85) : Color.web("#ff5f6d", 0.85));
        double r = BRUSH_RADIUS / scaleX;
        g.fillOval(paneX - r, paneY - r, r * 2, r * 2);
    }

    private void beginStrokeTrail() { clearStrokeTrail(); }

    private void clearStrokeTrail() {
        strokeLayer.getGraphicsContext2D().clearRect(0, 0, strokeLayer.getWidth(), strokeLayer.getHeight());
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(v, max)); }

    private void showPreview(MagicWand.Result r) {
        if (r.isEmpty()) { preview.setImage(null); return; }
        preview.setImage(tintMask(r));
        preview.setLayoutX(r.minX() / scaleX);
        preview.setLayoutY(r.minY() / scaleY);
        preview.setFitWidth(r.boxWidth() / scaleX);
        preview.setFitHeight(r.boxHeight() / scaleY);
    }

    /** A box-sized translucent-blue image marking the selected pixels (for the on-surface preview). */
    private WritableImage tintMask(MagicWand.Result r) {
        WritableImage img = new WritableImage(r.boxWidth(), r.boxHeight());
        PixelWriter pw = img.getPixelWriter();
        Color tint = Color.color(0.18, 0.5, 1.0, 0.45);
        int w = r.imgWidth();
        for (int y = 0; y < r.boxHeight(); y++) {
            for (int x = 0; x < r.boxWidth(); x++) {
                if (r.mask()[(r.minY() + y) * w + (r.minX() + x)]) pw.setColor(x, y, tint);
            }
        }
        return img;
    }

    private void updateHud() {
        if (busy) {
            hud.setText("Capture object · segmenting…");
            return;
        }
        if (!boxed) {
            hud.setText("Capture object   —   drag a box around the object, Esc to cancel");
            return;
        }
        String size = (lastResult == null || lastResult.isEmpty()) ? "—"
                : lastResult.boxWidth() + "×" + lastResult.boxHeight() + " (" + lastResult.count() + " px)";
        hud.setText("Capture object · " + size
                + "   —   drag to add, right-drag to remove, Ctrl+Z/Y to undo/redo, ✓ to capture, Esc to cancel");
    }

    /** Esc cancels; Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) step the mask-snapshot history — ignored while solving. */
    private void onKeyPressed(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) { cancel(); return; }
        if (busy || !e.isControlDown()) return;
        boolean redo = e.getCode() == KeyCode.Y || (e.getCode() == KeyCode.Z && e.isShiftDown());
        if (redo) {
            if (session.canRedo()) { applyHistory(session.redo()); e.consume(); }
        } else if (e.getCode() == KeyCode.Z) {
            if (session.canUndo()) { applyHistory(session.undo()); e.consume(); }
        }
    }

    /** Republishes the preview after an undo/redo restored a mask state (an empty state clears the preview). */
    private void applyHistory(MagicWand.Result r) {
        lastResult = r;
        if (r == null || r.isEmpty()) {
            preview.setImage(null);
        } else {
            showPreview(r);
        }
        captureBtn.setDisable(r == null || r.isEmpty());
        updateHud();
    }

    private void finalizeSelection() {
        if (lastResult == null || lastResult.isEmpty()) return;
        BufferedImage cut = MagicWand.extract(frame, lastResult);
        onExtract.accept(cut);
    }

    private void cancel() {
        if (onCancel != null) onCancel.run();
    }
}
