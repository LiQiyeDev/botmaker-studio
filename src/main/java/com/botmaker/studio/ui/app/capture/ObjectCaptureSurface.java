package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * The "Capture object" surface: shows a <em>frozen</em> snapshot of the target window and lets the user point
 * at an object to extract it with a transparent background (via {@link MagicWand} flood-fill). Freezing the
 * frame (rather than hovering the live window) keeps what the user sees exactly aligned with the pixels being
 * flood-filled, and lets the mouse wheel grow/shrink the selection deterministically.
 *
 * <ul>
 *   <li><b>Move</b> — flood-fills from the cursor and previews the matched region (blue tint).</li>
 *   <li><b>Wheel</b> — raises/lowers the colour tolerance (bigger ⇒ larger object, smaller ⇒ tighter).</li>
 *   <li><b>Click</b> — finalises: fires {@code onExtract} with the transparent-background crop.</li>
 *   <li><b>Esc / Cancel</b> — fires {@code onCancel} (returns to the mini-toolbar).</li>
 * </ul>
 */
public final class ObjectCaptureSurface {

    private static final int TOLERANCE_MIN = 2;
    private static final int TOLERANCE_MAX = 200;
    private static final int TOLERANCE_STEP = 6;
    /** Cap the flood so a loose tolerance on a big frame stays responsive (partial region still usable). */
    private static final int MAX_PIXELS = 400_000;

    private final Stage stage;
    private final BufferedImage frame;      // the frozen window snapshot, physical pixels
    private final int[] edges;              // Sobel gradient magnitude of frame, computed once (shape gate)
    private final double scaleX, scaleY;    // image px per surface-logical px
    private final Consumer<BufferedImage> onExtract;
    private final Runnable onCancel;

    private final Pane pane = new Pane();
    private final ImageView preview = new ImageView();  // blue-tinted mask overlay
    private final Label hud = new Label();

    private int tolerance = 28;
    private int lastImgX = -1, lastImgY = -1;
    private MagicWand.Result lastResult;

    private ObjectCaptureSurface(Window owner, java.awt.Rectangle bounds, BufferedImage frame,
                                 Consumer<BufferedImage> onExtract, Runnable onCancel) {
        this.frame = frame;
        this.edges = MagicWand.sobel(frame);   // one-time edge map so hover/wheel stay responsive
        this.onExtract = onExtract;
        this.onCancel = onCancel;
        this.scaleX = frame.getWidth() / (double) bounds.width;
        this.scaleY = frame.getHeight() / (double) bounds.height;

        // Show the frozen frame as the surface body so the user points at exactly what will be captured.
        ImageView background = new ImageView(ScreenCaptureService.toFxImage(frame));
        background.setFitWidth(bounds.width);
        background.setFitHeight(bounds.height);
        preview.setMouseTransparent(true);

        pane.getChildren().addAll(background, preview, buildControlBar(bounds));
        pane.setStyle("-fx-background-color: rgba(10,14,20,0.15);");
        installHandlers();

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setX(bounds.x);
        stage.setY(bounds.y);
        stage.setWidth(bounds.width);
        stage.setHeight(bounds.height);
        Scene scene = new Scene(pane, bounds.width, bounds.height, Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancel(); });
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

    public void close() { stage.close(); }

    private HBox buildControlBar(java.awt.Rectangle bounds) {
        hud.setTextFill(Color.web("#e8eefb"));
        Button cancel = new Button("✕ Cancel");
        cancel.setOnAction(e -> cancel());
        HBox bar = new HBox(10, hud, cancel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;");
        bar.setLayoutX(12);
        bar.setLayoutY(12);
        updateHud();
        return bar;
    }

    private void installHandlers() {
        pane.setOnMouseMoved(e -> { updateSeed(e.getX(), e.getY()); recompute(); });
        pane.setOnScroll(e -> {
            int delta = e.getDeltaY() > 0 ? TOLERANCE_STEP : -TOLERANCE_STEP;
            tolerance = Math.max(TOLERANCE_MIN, Math.min(TOLERANCE_MAX, tolerance + delta));
            recompute();
            e.consume();
        });
        pane.setOnMouseClicked(e -> finalizeSelection());
    }

    private void updateSeed(double paneX, double paneY) {
        lastImgX = (int) Math.round(paneX * scaleX);
        lastImgY = (int) Math.round(paneY * scaleY);
        lastImgX = Math.max(0, Math.min(lastImgX, frame.getWidth() - 1));
        lastImgY = Math.max(0, Math.min(lastImgY, frame.getHeight() - 1));
    }

    /** Re-runs the flood from the last cursor position and refreshes the preview tint + HUD. */
    private void recompute() {
        if (lastImgX < 0) return;
        lastResult = MagicWand.flood(frame, edges, lastImgX, lastImgY, tolerance, MAX_PIXELS);
        if (lastResult == null) { preview.setImage(null); updateHud(); return; }
        preview.setImage(tintMask(lastResult));
        preview.setLayoutX(lastResult.minX() / scaleX);
        preview.setLayoutY(lastResult.minY() / scaleY);
        preview.setFitWidth(lastResult.boxWidth() / scaleX);
        preview.setFitHeight(lastResult.boxHeight() / scaleY);
        updateHud();
    }

    /** A box-sized translucent-blue image marking the matched pixels (for the on-surface preview). */
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
        String size = (lastResult == null) ? "—"
                : lastResult.boxWidth() + "×" + lastResult.boxHeight() + " (" + lastResult.count() + " px)";
        hud.setText("Capture object · tolerance " + tolerance + " · " + size
                + "   —   move to aim, scroll to resize, click to capture, Esc to cancel");
    }

    private void finalizeSelection() {
        if (lastResult == null || lastResult.count() == 0) return;
        BufferedImage cut = MagicWand.extract(frame, lastResult);
        onExtract.accept(cut);
    }

    private void cancel() {
        if (onCancel != null) onCancel.run();
    }
}
