package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.WindowMatch;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.session.DesktopSession;
import com.botmaker.studio.emulator.EmulatorSurface;
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

/**
 * Resolves and grabs a single non-intrusive frame of "the surface the bot is acting on", plus the JPEG
 * encoding used to ship it. Split out from the server so the capture/scale/encode path is testable and
 * reusable on its own; {@code PilotServer} (raw JPEG bytes over WebSocket) is its one
 * capture pipeline.
 *
 * <p>Target resolution starts with the {@link PilotRoute}: a nested session's {@code :N} window or an
 * emulator's framebuffer <em>is</em> the surface, and needs no picking. Only on the {@code :0} desktop route is
 * there a choice to make — a live window from telemetry wins; else the project default (window / monitor /
 * whole desktop); else a whole-screen telemetry target; else the primary screen. Window targets use shared JNA
 * {@code captureWindow} (no focus, prompt-free on X11/XWayland); screen targets fall back to AWT {@link Robot}
 * (limited on Wayland — see the module ROADMAP).
 */
public final class TargetCapture {

    /** A captured frame plus the absolute surface origin/size its pixel (0,0) maps to. */
    public record Capture(BufferedImage img, int sx, int sy, int sw, int sh) {}

    private final ProjectSettingsService settings;

    public TargetCapture(ProjectSettingsService settings) {
        this.settings = settings;
    }

    /**
     * Grabs the frame to preview this tick, on the surface {@code route} names.
     *
     * <p>A {@link PilotRoute.Session} or {@link PilotRoute.Emulator} <em>is</em> the answer — those routes exist
     * precisely because the bot is not on the user's desktop — and each falls back to the {@code :0} path only
     * if its own grab fails, so a momentarily unreachable emulator shows something rather than nothing. On the
     * {@link PilotRoute.Desktop} route, {@code lastTarget} is the most recent telemetry target (may be
     * {@code null} when idle) and a live window target wins over the project default.
     */
    public Capture resolve(PilotRoute route, TelemetryEvent.Target lastTarget) {
        switch (route == null ? PilotRoute.DESKTOP : route) {
            case PilotRoute.Session(DesktopSession s) -> {
                Capture c = captureSession(s);
                if (c != null) return c;
            }
            case PilotRoute.Emulator(EmulatorSurface surface) -> {
                Capture c = captureEmulator(surface);
                if (c != null) return c;
            }
            case PilotRoute.Desktop ignored -> { /* the :0 resolution below */ }
        }
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

    /**
     * Grab the window the nested session launched into {@code :N}, tagged with that window's rect <em>on
     * {@code :N}</em>. Because {@link PilotInputService} drives the same {@code :N} controller, that rect is
     * the one coordinate space both capture and Interact live in — a tap on the streamed frame lands on the
     * same pixel the bot sees.
     */
    private Capture captureSession(DesktopSession s) {
        try {
            GenericWindow win = s.attached();
            if (win == null) return null;
            BufferedImage img = s.capture(); // the :N-bound controller's captureWindow — no :0 focus, non-intrusive
            if (img == null) return null;
            Rectangle b = win.getRect();
            return new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Grab the emulator's screen over ADB, tagged with its <em>own framebuffer</em> rect — origin {@code (0,0)},
     * because an emulator's pixels are not on any host screen. That rect is what {@link PilotInputService}
     * clamps a gesture to and what {@code input tap} expects, so capture and Interact share one coordinate
     * space here for the same reason a nested session's {@code :N} rect makes them share one there. It is also
     * the space the project's image templates were captured in, so what the phone sees is what the bot matches.
     */
    private Capture captureEmulator(EmulatorSurface surface) {
        try {
            BufferedImage img = surface.grab();
            return img == null ? null : new Capture(img, 0, 0, img.getWidth(), img.getHeight());
        } catch (Throwable ex) {
            return null;
        }
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
        try {
            return WindowMatch.best(NativeControllerFactory.get().getAllWindows(), titleSubstring);
        } catch (Throwable ignored) {
            return null;
        }
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

    private static BufferedImage toRgb(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
