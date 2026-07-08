package com.botmaker.studio.ui.app;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.preview.PreviewScreenFeed;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
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
 * Live preview of the window/screen a bot is (or would be) targeting, with overlays drawn where the vision /
 * interaction functions acted (matched rect, click point, search region). It is fed both by the project's
 * default {@link CaptureTarget} (so the panel shows the target even while idle) and, during a run, by the
 * {@link CoreApplicationEvents.ViewFeedbackEvent}s decoded from the {@code shared} IPC telemetry.
 *
 * <p>Effective-target precedence per capture tick: a live <em>window</em> target reported by telemetry →
 * else the project default (window or a specific screen) → else a whole telemetry screen target → else the
 * primary screen. Capture is deliberately <b>non-intrusive</b>: window pixels are grabbed via the shared
 * native controller <em>without</em> focusing/raising the window, so a running bot is never disturbed.
 *
 * <p>The panel supports a source-image <b>viewport</b> (zoom + pan): hover-revealed {@code + / − / fit}
 * controls, and a <b>Follow found object</b> toggle that eases the viewport onto the last match's live
 * region. A <b>reload</b> control re-resolves the default target in case a settings change didn't propagate.
 */
public final class WindowPreviewManager {

    private static final int CAPTURE_FPS = 10;
    private static final long OVERLAY_LINGER_NANOS = 1_200_000_000L; // ~1.2s
    private static final int MAX_OVERLAYS = 24;
    private static final double MAX_ZOOM = 12.0;
    private static final double FOLLOW_PADDING = 1.8;   // matched-rect expansion when following
    private static final double VIEWPORT_EASE = 0.18;   // per-frame lerp toward the target viewport

    private final StackPane root = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Canvas overlayCanvas = new Canvas();
    private final Label placeholder = new Label("Set a capture target (🎯) or run a bot to preview it here");
    private final HBox controls = new HBox(6);
    private final ToggleButton followBtn = new ToggleButton("⌖");

    private final ProjectSettingsService settings;
    private final ProjectState state;

    /** Plain-run block highlighting: line → block (built on run start; JDI owns highlighting during debug). */
    private java.util.Map<Integer, CodeBlock> lineToBlock;
    private volatile boolean plainRun; // true only for a plain run (not a debug/trace session)

    /** Latest frame + the absolute (logical) surface bounds its pixel (0,0) maps to. FX thread only. */
    private Frame frame;
    /** Latest window target reported by telemetry (drives live-window capture). */
    private volatile Target telemetryTarget;
    /** The project's default capture target, or null. Updated on {@link CoreApplicationEvents.SettingsChangedEvent}. */
    private volatile CaptureTarget defaultTarget;
    /** True while a run/debug session is active (overlays are run-scoped). */
    private volatile boolean runActive;
    private final AtomicBoolean capturing = new AtomicBoolean(false);

    // Source-image viewport (in frame-image pixels). null width means "fit whole image".
    private double vpX, vpY, vpW, vpH;      // current (rendered)
    private double tvpX, tvpY, tvpW, tvpH;  // target (eased toward)
    private boolean viewportActive;         // false → show the whole image (fit)
    private boolean followMode;

    private final Deque<TimedOverlay> overlays = new ArrayDeque<>(); // FX-thread only
    private final AnimationTimer renderTimer;
    private ScheduledExecutorService captureExec;

    // Wayland live screen feed (xdg-desktop-portal ScreenCast → PipeWire), used instead of per-frame Robot.
    private PreviewScreenFeed screenFeed;
    private volatile boolean waylandFeedFailed;
    private long lastPortalFrameNanos;
    private static final long PORTAL_MIN_FRAME_GAP_NANOS = 55_000_000L; // ~18 FPS cap for the preview

    private record Target(String title, int x, int y, int width, int height, boolean window) {}
    private record Frame(Image image, int sx, int sy, int sw, int sh) {}
    private record TimedOverlay(TelemetryEvent event, long deadlineNanos) {}

    public WindowPreviewManager(EventBus eventBus, ProjectConfig config, ProjectState state) {
        this.settings = new ProjectSettingsService(config, state, eventBus);
        this.state = state;
        this.defaultTarget = safeDefaultTarget();

        placeholder.getStyleClass().add("preview-placeholder");
        placeholder.setWrapText(true);
        imageView.setPreserveRatio(true);
        overlayCanvas.setMouseTransparent(true);
        root.getStyleClass().add("window-preview");
        root.setMinSize(160, 160);

        buildControls();
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        root.getChildren().addAll(placeholder, imageView, overlayCanvas, controls);

        // Keep the image fit to the panel; redraw overlays when the panel resizes.
        root.widthProperty().addListener((o, a, b) -> { fitImage(); redrawOverlays(); });
        root.heightProperty().addListener((o, a, b) -> { fitImage(); redrawOverlays(); });
        // Controls only while hovering the panel.
        controls.setVisible(false);
        root.setOnMouseEntered(e -> controls.setVisible(true));
        root.setOnMouseExited(e -> controls.setVisible(false));
        // Drag to pan when zoomed in.
        installPanHandlers();

        renderTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                boolean animating = easeViewport();
                pruneExpired(now);
                if (animating) fitImage();
                redrawOverlays();
                // Keep running while overlays linger, while easing, or while following a live target.
                if (overlays.isEmpty() && !animating && !followMode) stop();
            }
        };

        eventBus.subscribe(CoreApplicationEvents.ViewFeedbackEvent.class, this::onFeedback, true);
        eventBus.subscribe(CoreApplicationEvents.SettingsChangedEvent.class, this::onSettingsChanged, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> { runActive = true; plainRun = true; buildLineMap(); refreshCaptureState(); }, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> { runActive = false; plainRun = false; clearRunHighlight(); refreshCaptureState(); }, true);
        // A debug/trace session already highlights the running block via JDI, so we don't drive it from telemetry.
        eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> { runActive = true; plainRun = false; refreshCaptureState(); }, true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionFinishedEvent.class, e -> { runActive = false; plainRun = false; refreshCaptureState(); }, true);

        // Start previewing the default target immediately (idle preview), if one is set.
        Platform.runLater(this::refreshCaptureState);
    }

    /** The panel node to place in the layout. */
    public Region getView() {
        return root;
    }

    private CaptureTarget safeDefaultTarget() {
        try {
            return settings.defaultTarget();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Controls ---

    private void buildControls() {
        Button zoomIn = iconButton("＋", "Zoom in", () -> zoomBy(1.25));
        Button zoomOut = iconButton("－", "Zoom out", () -> zoomBy(0.8));
        Button fit = iconButton("⤢", "Fit", this::resetViewport);
        Button reload = iconButton("⟳", "Reload target", this::reload);
        followBtn.setText("⌖");
        followBtn.setTooltip(new Tooltip("Follow found object"));
        followBtn.getStyleClass().add("preview-ctl");
        followBtn.setOnAction(e -> {
            followMode = followBtn.isSelected();
            if (!followMode) resetViewport();
            else renderTimer.start();
        });
        controls.getChildren().addAll(zoomOut, zoomIn, fit, followBtn, reload);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new javafx.geometry.Insets(4));
        controls.getStyleClass().add("preview-controls");
        controls.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setMargin(controls, new javafx.geometry.Insets(0, 0, 6, 0));
    }

    private Button iconButton(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.getStyleClass().add("preview-ctl");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Re-reads the project default and restarts the feed (covers a settings change that didn't propagate). */
    private void reload() {
        this.defaultTarget = safeDefaultTarget();
        this.frame = null;
        refreshCaptureState();
    }

    // --- Feedback intake (FX thread) ---

    private void onFeedback(CoreApplicationEvents.ViewFeedbackEvent event) {
        TelemetryEvent te = event.feedback();
        Target t = toTarget(te.target());
        if (t.window()) this.telemetryTarget = t; // only a real window overrides the default
        if (plainRun) highlightRunningBlock(te.line());
        overlays.addLast(new TimedOverlay(te, System.nanoTime() + OVERLAY_LINGER_NANOS));
        while (overlays.size() > MAX_OVERLAYS) overlays.removeFirst();
        if (followMode && te instanceof TelemetryEvent.Match m && m.found() && m.rect() != null) {
            followRect(m.rect());
        }
        renderTimer.start(); // idempotent
    }

    private void onSettingsChanged(CoreApplicationEvents.SettingsChangedEvent event) {
        StudioProjectSettings s = event.settings();
        this.defaultTarget = (s != null) ? s.defaultTarget() : null;
        this.telemetryTarget = null; // a fresh default takes precedence again
        this.frame = null;
        refreshCaptureState();
    }

    private static Target toTarget(TelemetryEvent.Target t) {
        boolean window = t.title() != null && t.width() > 0 && t.height() > 0;
        return new Target(t.title(), t.x(), t.y(), t.width(), t.height(), window);
    }

    // --- Running-block highlight (plain run only; FX thread) ---

    /** Builds the line → block map for the active file so telemetry can highlight the executing block. */
    private void buildLineMap() {
        java.util.Map<Integer, CodeBlock> map = new java.util.HashMap<>();
        org.eclipse.jdt.core.dom.CompilationUnit cu = state.getCompilationUnit().orElse(null);
        if (cu != null) {
            for (CodeBlock b : state.getNodeToBlockMap().values()) {
                int line = b.getBreakpointLine(cu);
                if (line > 0 && (!map.containsKey(line) || b instanceof StatementBlock)) {
                    map.put(line, b);
                }
            }
        }
        this.lineToBlock = map;
    }

    /** Highlights the block on {@code line} (the bot source line that emitted the event), if we can map it. */
    private void highlightRunningBlock(int line) {
        if (line <= 0 || lineToBlock == null) return;
        CodeBlock block = lineToBlock.get(line);
        if (block != null) state.setHighlightedBlock(block);
    }

    private void clearRunHighlight() {
        lineToBlock = null;
        state.clearHighlight();
    }

    // --- Capture loop (background thread → FX thread) ---

    /** True when there's a reason to capture: an active run, or a default target to preview while idle. */
    private boolean shouldCapture() {
        return runActive || defaultTarget != null || telemetryTarget != null;
    }

    private void refreshCaptureState() {
        if (shouldCapture()) startCapture();
        else stopCapture();
    }

    private void startCapture() {
        if (!capturing.compareAndSet(false, true)) return;
        placeholder.setVisible(false);
        captureExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "preview-capture");
            t.setDaemon(true);
            return t;
        });
        captureExec.scheduleAtFixedRate(this::captureTick, 0, 1000 / CAPTURE_FPS, TimeUnit.MILLISECONDS);
    }

    private void stopCapture() {
        if (!capturing.compareAndSet(true, false)) return;
        if (captureExec != null) {
            captureExec.shutdownNow();
            captureExec = null;
        }
        if (screenFeed != null) {           // stop the live PipeWire feed; the restore token reopens it silently
            screenFeed.close();
            screenFeed = null;
        }
        Platform.runLater(() -> placeholder.setVisible(frame == null));
    }

    /** Grabs the current effective target's pixels off the FX thread, then hands the frame to the FX thread. */
    private void captureTick() {
        Target t = effectiveTarget();
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

    /**
     * Resolves what to capture this tick. A live window from telemetry wins; otherwise the project default
     * (a window, or a specific screen); otherwise a whole telemetry screen target; otherwise the primary screen.
     */
    private Target effectiveTarget() {
        Target tel = this.telemetryTarget;
        if (tel != null && tel.window()) return tel;

        CaptureTarget def = this.defaultTarget;
        if (def instanceof WindowTarget wt) {
            return new Target(wt.titleSubstring(), 0, 0, 0, 0, true);
        }
        if (def instanceof ScreenTarget st) {
            Rectangle b = screenBounds(st.index());
            return new Target(null, b.x, b.y, b.width, b.height, false);
        }
        if (def instanceof DesktopTarget) {
            Rectangle b = virtualBounds();
            return new Target(null, b.x, b.y, b.width, b.height, false);
        }
        if (tel != null) return tel; // a whole-screen telemetry target (null title)
        Rectangle b = primaryBounds();
        return new Target(null, b.x, b.y, b.width, b.height, false);
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
        // On Wayland a per-frame Robot grab re-triggers the screen-share portal picker endlessly. Use the
        // persistent portal ScreenCast → PipeWire live feed instead; frames arrive via onPortalFrame(). We
        // never call Robot here on Wayland (that is the whole bug being fixed).
        if (PreviewScreenFeed.isWayland()) {
            if (!waylandFeedFailed) {
                ensureScreenFeed();
                if (screenFeed != null && screenFeed.hasFailed()) {
                    waylandFeedFailed = true;
                    Platform.runLater(this::showWaylandHint);
                }
            }
            return null;
        }
        try {
            Rectangle bounds = new Rectangle(t.x(), t.y(), t.width(), t.height());
            BufferedImage img = new Robot().createScreenCapture(bounds);
            Image fx = toFxImage(img);
            return fx == null ? null : new Frame(fx, bounds.x, bounds.y, bounds.width, bounds.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Lazily starts the Wayland live screen feed (non-blocking; the one-time consent prompt runs off-thread). */
    private void ensureScreenFeed() {
        if (screenFeed == null) screenFeed = new PreviewScreenFeed(this::onPortalFrame);
        screenFeed.ensureStarted();
    }

    /** A live PipeWire frame (ARGB) for the captured monitor at virtual origin {@code (ox, oy)}. FX thread bound. */
    private void onPortalFrame(int[] argb, int w, int h, int ox, int oy) {
        if (w <= 0 || h <= 0) return;
        long now = System.nanoTime();
        if (now - lastPortalFrameNanos < PORTAL_MIN_FRAME_GAP_NANOS) return; // throttle to the preview cap
        lastPortalFrameNanos = now;
        Platform.runLater(() -> {
            WritableImage wi = (imageView.getImage() instanceof WritableImage cur
                    && cur.getWidth() == w && cur.getHeight() == h) ? cur : new WritableImage(w, h);
            wi.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
            this.frame = new Frame(wi, ox, oy, w, h);
            placeholder.setVisible(false);
            if (imageView.getImage() != wi) imageView.setImage(wi);
            fitImage();
            redrawOverlays();
        });
    }

    /** Shown when the portal/GStreamer live feed can't be established, so the panel isn't silently blank. */
    private void showWaylandHint() {
        if (frame != null) return;
        placeholder.setText("Live screen preview needs the desktop screen-share portal + GStreamer with the "
                + "PipeWire plugin (Fedora: gstreamer1-plugins-good). Accept the one-time share prompt, or set "
                + "a window as the capture target.");
        placeholder.setVisible(true);
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

    /** AWT device bounds for {@code index} (matches Robot's coordinate space), falling back to the primary. */
    private static Rectangle screenBounds(int index) {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (index >= 0 && index < devices.length) {
            return devices[index].getDefaultConfiguration().getBounds();
        }
        return primaryBounds();
    }

    private static Rectangle primaryBounds() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    /** The whole virtual-desktop bounds (union of every AWT device) — matches Robot's coordinate space. */
    private static Rectangle virtualBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds.isEmpty() ? primaryBounds() : bounds;
    }

    // --- Viewport (zoom / pan / follow) ---

    private void zoomBy(double factor) {
        if (frame == null) return;
        double iw = frame.image().getWidth(), ih = frame.image().getHeight();
        if (!viewportActive) { vpX = 0; vpY = 0; vpW = iw; vpH = ih; }
        double cx = vpX + vpW / 2, cy = vpY + vpH / 2;
        double minW = iw / MAX_ZOOM, minH = ih / MAX_ZOOM;
        double nw = clamp(vpW / factor, minW, iw);
        double nh = clamp(vpH / factor, minH, ih);
        setViewportImmediate(cx - nw / 2, cy - nh / 2, nw, nh);
        viewportActive = nw < iw - 1 || nh < ih - 1;
        if (!viewportActive) resetViewport();
        else { fitImage(); redrawOverlays(); }
    }

    private void resetViewport() {
        viewportActive = false;
        followMode = false;
        followBtn.setSelected(false);
        if (frame != null) {
            vpX = 0; vpY = 0; vpW = frame.image().getWidth(); vpH = frame.image().getHeight();
            tvpX = vpX; tvpY = vpY; tvpW = vpW; tvpH = vpH;
        }
        imageView.setViewport(null);
        fitImage();
        redrawOverlays();
    }

    /** Eases the viewport toward a live matched rect (absolute coords → frame-image pixels). */
    private void followRect(TelemetryEvent.Rect r) {
        if (frame == null) return;
        double iw = frame.image().getWidth(), ih = frame.image().getHeight();
        double px = r.x() - frame.sx(), py = r.y() - frame.sy();
        double pw = Math.max(1, r.width()), ph = Math.max(1, r.height());
        double ew = pw * FOLLOW_PADDING, eh = ph * FOLLOW_PADDING;
        double minW = iw / MAX_ZOOM, minH = ih / MAX_ZOOM;
        ew = clamp(ew, minW, iw);
        eh = clamp(eh, minH, ih);
        double cx = px + pw / 2, cy = py + ph / 2;
        tvpX = clamp(cx - ew / 2, 0, iw - ew);
        tvpY = clamp(cy - eh / 2, 0, ih - eh);
        tvpW = ew; tvpH = eh;
        viewportActive = true;
        renderTimer.start();
    }

    private void setViewportImmediate(double x, double y, double w, double h) {
        if (frame == null) return;
        double iw = frame.image().getWidth(), ih = frame.image().getHeight();
        vpW = clamp(w, 1, iw); vpH = clamp(h, 1, ih);
        vpX = clamp(x, 0, iw - vpW); vpY = clamp(y, 0, ih - vpH);
        tvpX = vpX; tvpY = vpY; tvpW = vpW; tvpH = vpH;
    }

    /** Lerps the current viewport toward the target; returns true while still moving. */
    private boolean easeViewport() {
        if (!viewportActive || frame == null) return false;
        double dx = tvpX - vpX, dy = tvpY - vpY, dw = tvpW - vpW, dh = tvpH - vpH;
        double mag = Math.abs(dx) + Math.abs(dy) + Math.abs(dw) + Math.abs(dh);
        if (mag < 0.5) { vpX = tvpX; vpY = tvpY; vpW = tvpW; vpH = tvpH; return false; }
        vpX += dx * VIEWPORT_EASE; vpY += dy * VIEWPORT_EASE;
        vpW += dw * VIEWPORT_EASE; vpH += dh * VIEWPORT_EASE;
        return true;
    }

    private void installPanHandlers() {
        final double[] anchor = new double[4]; // mouseX, mouseY, vpX, vpY
        root.setOnMousePressed(e -> {
            if (!viewportActive) return;
            anchor[0] = e.getX(); anchor[1] = e.getY(); anchor[2] = vpX; anchor[3] = vpY;
        });
        root.setOnMouseDragged(e -> {
            if (!viewportActive || frame == null) return;
            double[] disp = displayedRect();
            if (disp == null) return;
            double scale = disp[2];
            double iw = frame.image().getWidth(), ih = frame.image().getHeight();
            double nx = clamp(anchor[2] - (e.getX() - anchor[0]) / scale, 0, iw - vpW);
            double ny = clamp(anchor[3] - (e.getY() - anchor[1]) / scale, 0, ih - vpH);
            setViewportImmediate(nx, ny, vpW, vpH);
            fitImage();
            redrawOverlays();
        });
    }

    // --- Rendering (FX thread) ---

    private void fitImage() {
        double w = root.getWidth();
        double h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        if (viewportActive && frame != null) {
            imageView.setViewport(new javafx.geometry.Rectangle2D(vpX, vpY, vpW, vpH));
        } else {
            imageView.setViewport(null);
        }
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
    }

    /** Displayed image size (letterboxed within the panel) for the current frame/viewport: {dw, dh, scale}. */
    private double[] displayedRect() {
        if (frame == null) return null;
        Image img = frame.image();
        double iw = viewportActive ? vpW : img.getWidth();
        double ih = viewportActive ? vpH : img.getHeight();
        double pw = root.getWidth(), ph = root.getHeight();
        if (iw <= 0 || ih <= 0 || pw <= 0 || ph <= 0) return null;
        double scale = Math.min(pw / iw, ph / ih);
        return new double[]{iw * scale, ih * scale, scale};
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
        double vx = viewportActive ? vpX : 0, vy = viewportActive ? vpY : 0;
        double x = ((rect.x() - frame.sx()) - vx) * scale;
        double y = ((rect.y() - frame.sy()) - vy) * scale;
        g.setStroke(color);
        g.setLineWidth(lineWidth);
        g.strokeRect(x, y, rect.width() * scale, rect.height() * scale);
    }

    private double[] mapPoint(int ax, int ay, double scale) {
        if (frame == null) return null;
        double vx = viewportActive ? vpX : 0, vy = viewportActive ? vpY : 0;
        return new double[]{((ax - frame.sx()) - vx) * scale, ((ay - frame.sy()) - vy) * scale};
    }

    private static double clamp(double v, double min, double max) {
        if (max < min) return min;
        return Math.max(min, Math.min(v, max));
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
