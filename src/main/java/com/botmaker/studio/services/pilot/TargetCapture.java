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
 * <p>Target resolution starts with the {@link PilotRoute}: a nested session's {@code :N} screen or an
 * emulator's framebuffer <em>is</em> the surface, and needs no picking. Only on the {@code :0} desktop route is
 * there a choice to make — a live window from telemetry wins; else the project default (window / monitor /
 * whole desktop); else a whole-screen telemetry target; else the primary screen. Window targets use shared JNA
 * {@code captureWindow} (no focus, prompt-free on X11/XWayland); screen targets fall back to AWT {@link Robot}
 * (limited on Wayland — see the module ROADMAP).
 */
public final class TargetCapture {

    /** A captured frame plus the absolute surface origin/size its pixel (0,0) maps to. */
    public record Capture(BufferedImage img, int sx, int sy, int sw, int sh) {}

    /**
     * A frame together with <b>the route it was actually taken on</b> — the two halves of one fact, returned as
     * one value so they cannot disagree.
     *
     * <p>They used to be computed apart: the caller asked for a frame on the route it intended, and published
     * that intended route beside whatever bounds came back. A session grab that failed then fell through to a
     * {@code :0} desktop capture, and the client was shown the user's desktop while being told it was looking at
     * a session — so a tap was clamped to host multi-monitor coordinates and replayed through the session's
     * {@code :N} controller, which is the "Interact teleports the cursor to the other screen" report. Returning
     * both from the same resolution makes that state unrepresentable.
     */
    public record Resolved(PilotRoute route, Capture cap) {}

    private final ProjectSettingsService settings;

    public TargetCapture(ProjectSettingsService settings) {
        this.settings = settings;
    }

    /**
     * Grabs the frame to preview this tick, on the surface {@code route} names, and reports which route it came
     * from — see {@link Resolved}. {@code null} means "no frame this tick"; the client shows its last one and
     * the pilot's published bounds are left untouched.
     *
     * <p><b>A non-desktop route never falls back to the desktop.</b> A {@link PilotRoute.Session} or
     * {@link PilotRoute.Emulator} exists precisely because the bot is not on the user's desktop, so a failed
     * grab there resolves to nothing rather than to {@code :0}. Serving the user's screen under a session route
     * was both the cursor-teleport bug and a privacy leak — a pilot session is reachable over a public Funnel
     * URL, and "the emulator hiccuped" is not consent to stream a desktop.
     *
     * <p>On the {@link PilotRoute.Desktop} route, {@code lastTarget} is the most recent telemetry target (may be
     * {@code null} when idle) and a live window target wins over the project default.
     */
    public Resolved resolve(PilotRoute route, TelemetryEvent.Target lastTarget) {
        PilotRoute r = route == null ? PilotRoute.DESKTOP : route;
        switch (r) {
            case PilotRoute.Session(DesktopSession s) -> {
                return wrap(r, captureSession(s));
            }
            case PilotRoute.Emulator(EmulatorSurface surface) -> {
                return wrap(r, captureEmulator(surface));
            }
            case PilotRoute.Desktop ignored -> { /* the :0 resolution below */ }
        }
        return wrap(r, captureDesktop(lastTarget));
    }

    private static Resolved wrap(PilotRoute route, Capture cap) {
        return cap == null ? null : new Resolved(route, cap);
    }

    private Capture captureDesktop(TelemetryEvent.Target lastTarget) {
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
     * Grab the nested session's <b>screen</b> — the whole {@code :N} root, tagged with {@link
     * DesktopSession#screen()}. Because {@link PilotInputService} drives the same {@code :N} controller in root
     * coordinates, that rect is the one space both capture and Interact live in: a tap on the streamed frame
     * lands on the same pixel the bot sees.
     *
     * <p><b>Why the screen and not the attached window.</b> This used to grab {@code s.attached()}, one specific
     * window, and return {@code null} the moment there wasn't one — which is exactly what a launcher chain does:
     * Heroic comes up and is attached, Firestone replaces it, and for the seconds in between (and after, if the
     * swap isn't observed) there is no window to grab. That produced the reported "cannot capture the session"
     * and, through the old desktop fallback, the cursor teleport. The screen has no such dependency. Under
     * gamescope the client is forced fullscreen, so these are the same pixels anyway.
     *
     * <p>The attached window is still the fallback: a session backend that can't hand over a root frame keeps
     * streaming exactly what it did before, tagged with that window's rect.
     */
    private Capture captureSession(DesktopSession s) {
        try {
            BufferedImage img = s.captureScreen(); // the :N root — no :0 focus, non-intrusive
            Rectangle screen = s.screen();
            if (img != null && screen != null && !screen.isEmpty()) {
                return new Capture(img, screen.x, screen.y, screen.width, screen.height);
            }
            GenericWindow win = s.attached();
            if (win == null) return null;
            BufferedImage windowImg = s.capture();
            if (windowImg == null) return null;
            Rectangle b = win.getRect();
            return new Capture(windowImg, b.x, b.y, b.width, b.height);
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
