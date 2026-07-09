package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ProjectSettingsService;

import javax.imageio.ImageIO;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Resolves and grabs a single non-intrusive frame of "the surface the bot is acting on", plus the JPEG
 * encoding used to ship it. Extracted from {@code TelemetryDashboardServer} so both the loopback debug
 * dashboard (base64-over-SSE) and the remote {@code PilotServer} (raw bytes over WebSocket) share one
 * capture pipeline.
 *
 * <p>Resolution mirrors {@code WindowPreviewManager.effectiveTarget()}: a live window from telemetry wins;
 * else the project default (window / monitor / whole desktop); else a whole-screen telemetry target; else the
 * primary screen. Window targets use shared JNA {@code captureWindow} (no focus, prompt-free on X11/XWayland);
 * screen targets fall back to AWT {@link Robot} (limited on Wayland — see the module ROADMAP).
 */
public final class TargetCapture {

    /** A captured frame plus the absolute surface origin/size its pixel (0,0) maps to. */
    public record Capture(BufferedImage img, int sx, int sy, int sw, int sh) {}

    private final ProjectSettingsService settings;

    public TargetCapture(ProjectSettingsService settings) {
        this.settings = settings;
    }

    /**
     * Grabs the frame to preview this tick. {@code lastTarget} is the most recent telemetry target (may be
     * {@code null} when idle); a live window target wins over the project default.
     */
    public Capture resolve(TelemetryEvent.Target lastTarget) {
        TelemetryEvent.Target t = lastTarget;
        if (t != null && t.title() != null && t.width() > 0 && t.height() > 0) {
            Capture c = captureWindowTarget(t.title());
            if (c != null) return c;
        }
        CaptureTarget def = safeDefault();
        if (def instanceof CaptureTarget.WindowTarget wt && wt.titleSubstring() != null) {
            Capture c = captureWindowTarget(wt.titleSubstring());
            if (c != null) return c;
        } else if (def instanceof CaptureTarget.ScreenTarget st) {
            return captureBounds(screenBounds(st.index()));
        } else if (def instanceof CaptureTarget.DesktopTarget) {
            return captureBounds(virtualBounds());
        }
        if (t != null) return captureBounds(virtualBounds()); // whole-screen telemetry target
        return captureBounds(primaryBounds());                // idle, no default → current primary screen
    }

    private Capture captureWindowTarget(String title) {
        try {
            GenericWindow win = resolveWindow(title);
            if (win == null) return null;
            BufferedImage img = NativeControllerFactory.get().captureWindow(win); // no focus — non-intrusive
            if (img == null) return null;
            Rectangle b = win.getRect();
            return new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    private Capture captureBounds(Rectangle b) {
        try {
            BufferedImage img = new Robot().createScreenCapture(b);
            return img == null ? null : new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    private CaptureTarget safeDefault() {
        try {
            return settings != null ? settings.defaultTarget() : null;
        } catch (Exception e) {
            return null;
        }
    }

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

    private static GenericWindow resolveWindow(String titleSubstring) {
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

    // --- Encoding ---

    /** Encodes as JPEG bytes (RGB, no alpha), or {@code null} on failure. */
    public static byte[] jpegBytes(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BufferedImage rgb = img.getType() == BufferedImage.TYPE_INT_RGB ? img : toRgb(img);
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Base64-encoded JPEG (for the SSE dashboard's data: URLs), or {@code null} on failure. */
    public static String base64Jpeg(BufferedImage img) {
        byte[] bytes = jpegBytes(img);
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    private static BufferedImage toRgb(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
