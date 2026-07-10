package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
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

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * A live, transparent overlay drawn directly over the project's default <b>window</b> capture target (not a
 * screenshot of it) for quickly cropping image templates. The user clicks <em>Draw region</em> to enter
 * capture mode, rubber-bands a rectangle over the live window, names it (unique, non-blank), and it is saved
 * as a template with its resolution sidecar. Multiple templates can be captured in one session; pressing
 * Escape or the <em>Finish</em> button (where the Draw button was) closes the overlay.
 *
 * <p>At save time the overlay is briefly hidden and the window's pixels are re-captured fresh via
 * {@link ScreenCaptureService#captureWindow} (occlusion-safe), so the overlay chrome never ends up in the
 * saved template. The selection (overlay-logical pixels) is scaled to the captured image (physical pixels)
 * by the width/height ratio, which keeps the crop correct under HiDPI scaling.
 */
public final class OverlayTemplateCapture {

    private final Window owner;
    private final ProjectConfig config;
    private final ScreenCaptureService capture;
    private final EventBus eventBus;
    private final CaptureTarget.WindowTarget target;

    private Stage stage;
    private Pane pane;
    private Rectangle selection;
    private Button actionButton;
    private boolean capturing;
    /** True while a grab/name round-trip is in flight, to ignore further draws until it settles. */
    private boolean busy;

    private OverlayTemplateCapture(Window owner, ProjectConfig config, ScreenCaptureService capture,
                                   EventBus eventBus, CaptureTarget.WindowTarget target) {
        this.owner = owner;
        this.config = config;
        this.capture = capture;
        this.eventBus = eventBus;
        this.target = target;
    }

    /**
     * Opens the overlay for the project's default window target. Shows an explanatory alert (and does nothing
     * else) when the default target isn't a window, or the window can't be found. Must be called on the FX thread.
     */
    public static void open(Window owner, ProjectConfig config, ProjectSettingsService settings,
                            ScreenCaptureService capture, EventBus eventBus) {
        CaptureTarget target = null;
        try {
            target = settings.defaultTarget();
        } catch (Exception ignored) {
            // no default configured
        }
        if (!(target instanceof CaptureTarget.WindowTarget wt)) {
            warn(owner, "Overlay capture needs a window capture target.\n\nOpen \"Capture Targets\" and set a "
                    + "window as the default first.");
            return;
        }
        new OverlayTemplateCapture(owner, config, capture, eventBus, wt).start();
    }

    private void start() {
        // Resolve the live window once to get its on-screen bounds to place the overlay over.
        WindowShot shot = capture.captureWindow(target);
        if (shot == null) {
            warn(owner, "Couldn't find the window \"" + target.titleSubstring() + "\". Is it open?");
            return;
        }
        java.awt.Rectangle b = shot.bounds();

        selection = new Rectangle();
        selection.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
        selection.setStroke(Color.web("#2f80ed"));
        selection.setStrokeWidth(1.5);
        selection.setVisible(false);

        pane = new Pane(selection);
        // A faint tint gives the transparent overlay a pickable surface (so drags register) and signals
        // capture mode, while keeping the live window clearly visible underneath.
        pane.setStyle("-fx-background-color: rgba(20,110,220,0.06);");

        HBox bar = buildControlBar();
        pane.getChildren().add(bar);

        installDrawHandlers();

        stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setX(b.x);
        stage.setY(b.y);
        stage.setWidth(b.width);
        stage.setHeight(b.height);

        Scene scene = new Scene(pane, b.width, b.height, Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        stage.setScene(scene);
        stage.show();
    }

    private HBox buildControlBar() {
        actionButton = new Button("▢ Draw region");
        actionButton.setOnAction(e -> {
            if (!capturing) {
                capturing = true;
                actionButton.setText("✓ Finish");
            } else {
                stage.close();
            }
        });

        Label hint = new Label("Draw rectangles over the window to save templates · Esc to finish");
        hint.setTextFill(Color.WHITE);

        HBox bar = new HBox(10, actionButton, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        bar.setStyle("-fx-background-color: rgba(0,0,0,0.72); -fx-background-radius: 6;");
        bar.setLayoutX(12);
        bar.setLayoutY(12);
        // Keep the bar out of the way of drag-selection so clicking it never starts a rubber-band.
        bar.setMouseTransparent(false);
        return bar;
    }

    private void installDrawHandlers() {
        final double[] origin = new double[2];
        pane.setOnMousePressed(e -> {
            if (!capturing || busy || inControlBar(e.getX(), e.getY())) return;
            origin[0] = e.getX();
            origin[1] = e.getY();
            selection.setX(e.getX());
            selection.setY(e.getY());
            selection.setWidth(0);
            selection.setHeight(0);
            selection.setVisible(true);
        });
        pane.setOnMouseDragged(e -> {
            if (!capturing || busy || !selection.isVisible()) return;
            double x = Math.min(origin[0], e.getX());
            double y = Math.min(origin[1], e.getY());
            selection.setX(x);
            selection.setY(y);
            selection.setWidth(Math.abs(e.getX() - origin[0]));
            selection.setHeight(Math.abs(e.getY() - origin[1]));
        });
        pane.setOnMouseReleased(e -> {
            if (!capturing || busy || !selection.isVisible()) return;
            double selX = selection.getX(), selY = selection.getY();
            double selW = selection.getWidth(), selH = selection.getHeight();
            selection.setVisible(false);
            if (selW < 3 || selH < 3) return;
            captureRegion(selX, selY, selW, selH, pane.getWidth(), pane.getHeight());
        });
    }

    /** True when ({@code x},{@code y}) falls within the control bar so a click there isn't a rubber-band. */
    private boolean inControlBar(double x, double y) {
        for (var node : pane.getChildren()) {
            if (node instanceof HBox bar) {
                return bar.getBoundsInParent().contains(x, y);
            }
        }
        return false;
    }

    /**
     * Hides the overlay, re-captures the live window off the FX thread (so focus + settle don't freeze the
     * UI), crops to the selection, then prompts for a unique name and saves on the FX thread.
     */
    private void captureRegion(double selX, double selY, double selW, double selH,
                               double paneW, double paneH) {
        busy = true;
        stage.hide();
        Thread t = new Thread(() -> {
            WindowShot shot = capture.captureWindow(target);
            Platform.runLater(() -> {
                if (shot == null) {
                    busy = false;
                    stage.show();
                    warn(owner, "Capture failed — the window may have closed.");
                    return;
                }
                BufferedImage full = shot.image();
                BufferedImage cropped = cropToImage(full, selX, selY, selW, selH, paneW, paneH);
                try {
                    if (cropped != null) {
                        Optional<String> name = ImageTemplatePicker.promptTemplateName(owner, config, null);
                        if (name.isPresent()) {
                            ImageTemplateLibrary.saveTemplate(config, cropped, name.get(),
                                    full.getWidth(), full.getHeight(), target.titleSubstring());
                            eventBus.publish(new ResourcesChangedEvent());
                        }
                    }
                } catch (Exception ex) {
                    warn(owner, "Failed to save template: " + ex.getMessage());
                } finally {
                    busy = false;
                    stage.show();
                }
            });
        }, "overlay-template-capture");
        t.setDaemon(true);
        t.start();
    }

    /** Maps a selection rectangle (overlay-logical pixels) onto {@code full} (physical pixels) and crops it. */
    private static BufferedImage cropToImage(BufferedImage full, double selX, double selY, double selW,
                                             double selH, double paneW, double paneH) {
        if (paneW <= 0 || paneH <= 0) return null;
        double scaleX = full.getWidth() / paneW;
        double scaleY = full.getHeight() / paneH;
        int x = (int) Math.round(selX * scaleX);
        int y = (int) Math.round(selY * scaleY);
        int w = (int) Math.round(selW * scaleX);
        int h = (int) Math.round(selH * scaleY);
        x = Math.max(0, Math.min(x, full.getWidth() - 1));
        y = Math.max(0, Math.min(y, full.getHeight() - 1));
        w = Math.max(1, Math.min(w, full.getWidth() - x));
        h = Math.max(1, Math.min(h, full.getHeight() - y));
        return full.getSubimage(x, y, w, h);
    }

    private static void warn(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
    }
}
