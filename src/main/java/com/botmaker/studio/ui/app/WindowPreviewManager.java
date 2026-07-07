package com.botmaker.studio.ui.app;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live preview of the window/screen a running bot is targeting, with overlays drawn where the vision /
 * interaction functions acted (matched rect, click point, search region). Fed by {@link
 * CoreApplicationEvents.ViewFeedbackEvent}s (decoded telemetry frames from the {@code shared} IPC server).
 *
 * <p>Capture is deliberately <b>non-intrusive</b>: it grabs the target window's pixels via the shared native
 * controller <em>without</em> focusing/raising it (so it never disturbs the running bot), on a low-rate
 * timer while a run is active. Overlays linger ~1.2s then fade. Follows the {@code *Manager} panel pattern.
 */
public final class WindowPreviewManager {

    private static final int CAPTURE_FPS = 6;
    private static final long OVERLAY_LINGER_NANOS = 1_200_000_000L; // ~1.2s
    private static final int MAX_OVERLAYS = 24;

    private final StackPane root = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Canvas overlayCanvas = new Canvas();
    private final Label placeholder = new Label("Run a bot to preview its target window here");

    /** Latest frame + the absolute (logical) surface bounds it maps to. Read/written on the FX thread. */
    private Frame frame;
    /** Latest target the bot acted on; drives what the capture timer grabs. */
    private volatile Target target;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Deque<TimedOverlay> overlays = new ArrayDeque<>(); // FX-thread only
    private final AnimationTimer overlayTimer;
    private ScheduledExecutorService captureExec;

    private record Target(String title, int x, int y, int width, int height, boolean window) {}
    private record Frame(Image image, int sx, int sy, int sw, int sh) {}
    private record TimedOverlay(TelemetryEvent event, long deadlineNanos) {}

    public WindowPreviewManager(EventBus eventBus) {
        placeholder.getStyleClass().add("preview-placeholder");
        placeholder.setWrapText(true);
        imageView.setPreserveRatio(true);
        overlayCanvas.setMouseTransparent(true);
        root.getStyleClass().add("window-preview");
        root.setMinSize(160, 160);
        root.getChildren().addAll(placeholder, imageView, overlayCanvas);

        // Keep the image fit to the panel; redraw overlays when the panel resizes.
        root.widthProperty().addListener((o, a, b) -> { fitImage(); redrawOverlays(); });
        root.heightProperty().addListener((o, a, b) -> { fitImage(); redrawOverlays(); });

        overlayTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                pruneExpired(now);
                redrawOverlays();
                if (overlays.isEmpty()) stop();
            }
        };

        eventBus.subscribe(CoreApplicationEvents.ViewFeedbackEvent.class, this::onFeedback, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> startCapture(), true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> stopCapture(), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> startCapture(), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class, e -> stopCapture(), true);
    }

    /** The panel node to place in the layout. */
    public Region getView() {
        return root;
    }

    // --- Feedback intake (FX thread) ---

    private void onFeedback(CoreApplicationEvents.ViewFeedbackEvent event) {
        TelemetryEvent te = event.feedback();
        this.target = toTarget(te.target());
        overlays.addLast(new TimedOverlay(te, System.nanoTime() + OVERLAY_LINGER_NANOS));
        while (overlays.size() > MAX_OVERLAYS) overlays.removeFirst();
        overlayTimer.start(); // idempotent
    }

    private static Target toTarget(TelemetryEvent.Target t) {
        boolean window = t.title() != null && t.width() > 0 && t.height() > 0;
        return new Target(t.title(), t.x(), t.y(), t.width(), t.height(), window);
    }

    // --- Capture loop (background thread → FX thread) ---

    private void startCapture() {
        if (!running.compareAndSet(false, true)) return;
        placeholder.setVisible(false);
        captureExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "preview-capture");
            t.setDaemon(true);
            return t;
        });
        captureExec.scheduleAtFixedRate(this::captureTick, 0, 1000 / CAPTURE_FPS, TimeUnit.MILLISECONDS);
    }

    private void stopCapture() {
        if (!running.compareAndSet(true, false)) return;
        if (captureExec != null) {
            captureExec.shutdownNow();
            captureExec = null;
        }
        Platform.runLater(() -> placeholder.setVisible(frame == null));
    }

    /** Grabs the current target's pixels off the FX thread, then hands the frame to the FX thread. */
    private void captureTick() {
        Target t = this.target;
        if (t == null) return;
        Frame captured = t.window() ? captureWindow(t) : captureScreen(t);
        if (captured == null) return;
        Platform.runLater(() -> {
            this.frame = captured;
            placeholder.setVisible(false);
            imageView.setImage(captured.image());
            fitImage();
            redrawOverlays();
        });
    }

    private Frame captureWindow(Target t) {
        GenericWindow win = resolveWindow(t.title());
        if (win == null) return null;
        BufferedImage img;
        try {
            img = NativeControllerFactory.get().captureWindow(win); // NO focus — non-intrusive
        } catch (Throwable ex) {
            return null;
        }
        if (img == null) return null;
        Rectangle b = win.getRect();
        Image fx = toFxImage(img);
        return fx == null ? null : new Frame(fx, b.x, b.y, b.width, b.height);
    }

    private Frame captureScreen(Target t) {
        try {
            Rectangle virtual = virtualBounds();
            BufferedImage img = new Robot().createScreenCapture(virtual);
            Image fx = toFxImage(img);
            return fx == null ? null : new Frame(fx, virtual.x, virtual.y, virtual.width, virtual.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static GenericWindow resolveWindow(String titleSubstring) {
        if (titleSubstring == null) return null;
        String needle = titleSubstring.toLowerCase();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
                String title = w.getTitle();
                if (title != null && title.toLowerCase().contains(needle)) return w;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Rectangle virtualBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds.isEmpty() ? new Rectangle(0, 0, 1920, 1080) : bounds;
    }

    // --- Rendering (FX thread) ---

    private void fitImage() {
        double w = root.getWidth();
        double h = root.getHeight();
        if (w > 0 && h > 0) {
            imageView.setFitWidth(w);
            imageView.setFitHeight(h);
        }
    }

    /** Displayed image size (letterboxed within the panel) for the current frame, or null if none. */
    private double[] displayedRect() {
        if (frame == null) return null;
        Image img = frame.image();
        double iw = img.getWidth(), ih = img.getHeight();
        double pw = root.getWidth(), ph = root.getHeight();
        if (iw <= 0 || ih <= 0 || pw <= 0 || ph <= 0) return null;
        double scale = Math.min(pw / iw, ph / ih);
        double dw = iw * scale, dh = ih * scale;
        return new double[]{dw, dh, scale};
    }

    private void pruneExpired(long now) {
        overlays.removeIf(o -> o.deadlineNanos() <= now);
    }

    private void redrawOverlays() {
        double[] disp = displayedRect();
        GraphicsContext g = overlayCanvas.getGraphicsContext2D();
        if (disp == null) {
            overlayCanvas.setWidth(0);
            overlayCanvas.setHeight(0);
            return;
        }
        double dw = disp[0], dh = disp[1];
        overlayCanvas.setWidth(dw);
        overlayCanvas.setHeight(dh);
        g.clearRect(0, 0, dw, dh);

        long now = System.nanoTime();
        for (TimedOverlay o : overlays) {
            double remaining = (o.deadlineNanos() - now) / (double) OVERLAY_LINGER_NANOS;
            double alpha = Math.max(0, Math.min(1, remaining));
            drawOverlay(g, o.event(), alpha, disp[2]);
        }
    }

    private void drawOverlay(GraphicsContext g, TelemetryEvent event, double alpha, double scale) {
        switch (event) {
            case TelemetryEvent.Match m -> {
                if (m.region() != null) strokeRect(g, m.region(), Color.web("#f1c40f", alpha * 0.8), 1.0, scale);
                Color color = m.found() ? Color.web("#2ecc71", alpha) : Color.web("#e74c3c", alpha);
                if (m.rect() != null) strokeRect(g, m.rect(), color, 2.0, scale);
            }
            case TelemetryEvent.Region r -> strokeRect(g, r.rect(), Color.web("#f1c40f", alpha * 0.8), 1.0, scale);
            case TelemetryEvent.Click c -> {
                double[] p = mapPoint(c.x(), c.y(), scale);
                if (p == null) return;
                g.setStroke(Color.web("#3498db", alpha));
                g.setLineWidth(2.0);
                double rad = 8;
                g.strokeOval(p[0] - rad, p[1] - rad, rad * 2, rad * 2);
                g.strokeLine(p[0] - rad - 3, p[1], p[0] + rad + 3, p[1]);
                g.strokeLine(p[0], p[1] - rad - 3, p[0], p[1] + rad + 3);
            }
        }
    }

    private void strokeRect(GraphicsContext g, TelemetryEvent.Rect rect, Color color, double lineWidth, double scale) {
        if (frame == null || rect == null) return;
        double x = (rect.x() - frame.sx()) * scale;
        double y = (rect.y() - frame.sy()) * scale;
        g.setStroke(color);
        g.setLineWidth(lineWidth);
        g.strokeRect(x, y, rect.width() * scale, rect.height() * scale);
    }

    private double[] mapPoint(int ax, int ay, double scale) {
        if (frame == null) return null;
        return new double[]{(ax - frame.sx()) * scale, (ay - frame.sy()) * scale};
    }

    /** Converts a {@link BufferedImage} to a JavaFX {@link Image} via in-memory PNG (no javafx.swing dep). */
    private static Image toFxImage(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }
}
