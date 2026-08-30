package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.WindowMatch;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.Preview;
import com.botmaker.session.PreviewFrame;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.shared.emulator.EmulatorSurface;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.session.launch.BackgroundLauncher;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.function.LongSupplier;

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

    /**
     * A captured frame plus the absolute surface origin/size its pixel (0,0) maps to.
     *
     * <p>{@code img} is {@code null} when the frame never existed as pixels on this side — a nested session
     * encodes its own preview in the process that holds the display (see {@link Resolved#jpeg()}), and decoding
     * it here purely to satisfy this field would reinstate the codec pass that change removed. The rect is the
     * half that every consumer needs, and it is present either way.
     */
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
    public record Resolved(PilotRoute route, Capture cap, byte[] jpeg) {

        /** A frame that is still pixels here; the encode happens when someone asks for {@link #bytes()}. */
        Resolved(PilotRoute route, Capture cap) {
            this(route, cap, null);
        }

        /**
         * The frame as JPEG bytes, encoding it now if it wasn't encoded at the source. {@code null} means the
         * encode failed, which the caller treats exactly like a missed grab.
         */
        public byte[] bytes() {
            return jpeg != null ? jpeg : Preview.jpeg(cap.img(), Preview.MAX_EDGE, Preview.QUALITY);
        }
    }

    private final ProjectSettingsService settings;

    /** The live session's host window on {@code :0}, or {@code 0} — see {@link #captureWindowTarget}. */
    private final LongSupplier sessionHostWindow;

    /** No session to recognise: every {@code :0} window target is an ordinary window. For tests. */
    public TargetCapture(ProjectSettingsService settings) {
        this(settings, () -> 0);
    }

    public TargetCapture(ProjectSettingsService settings, LongSupplier sessionHostWindow) {
        this.settings = settings;
        this.sessionHostWindow = sessionHostWindow;
    }

    /** The production wiring: the host window comes from the project's one {@link BackgroundLauncher}. */
    public static TargetCapture forProject(ProjectSettingsService settings, Path resourcesDir) {
        if (resourcesDir == null) {
            return new TargetCapture(settings);
        }
        BackgroundLauncher launcher = BackgroundLauncher.forProject(resourcesDir);
        return new TargetCapture(settings, launcher::hostWindowId);
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
                Resolved encoded = encodedSession(r, s);
                return encoded != null ? encoded : wrap(r, captureSession(s));
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
     * <p><b>The root is not always painted.</b> A compositing backend (gamescope) redirects every client and
     * paints it to its own output, leaving the X root permanently black — so this rung's blank test is not an
     * edge case there, it is the normal path, and the attached window below is what actually streams. Sessions
     * that answer {@link DesktopSession#previewFrame} never reach here at all: that path makes the same choice
     * one process earlier, where the pixels are.
     */
    /**
     * The fast path for a nested session: ask it to encode a frame of itself and take the bytes as they are.
     * The pixels are never decoded here; that is the saving.
     *
     * <p><b>The rect comes back with the bytes and is not assumed.</b> It used to be {@link DesktopSession#screen()},
     * on the reading that a session's preview is its whole root — but a compositing backend never paints its
     * root, so the frame that has pixels on it is a <em>window</em> (see {@link com.botmaker.session.PreviewFrame}).
     * Under gamescope's forced fullscreen the two rects coincide, which is exactly what makes assuming it the
     * kind of bug that surfaces later, on a windowed client, as every Interact tap landing somewhere else.
     *
     * <p>{@code null} means "no fast path" — the session doesn't offer one, or nothing on it had pixels — and
     * the caller falls back to {@link #captureSession}, which still has the attached-window rung to try.
     */
    private Resolved encodedSession(PilotRoute route, DesktopSession s) {
        try {
            PreviewFrame frame = s.previewFrame(Preview.MAX_EDGE, Preview.QUALITY);
            if (frame == null || frame.jpeg() == null || frame.jpeg().length == 0) return null;
            Rectangle r = frame.surface();
            if (r == null || r.isEmpty()) return null;
            return new Resolved(route, new Capture(null, r.x, r.y, r.width, r.height), frame.jpeg());
        } catch (Throwable ex) {
            return null;
        }
    }

    private Capture captureSession(DesktopSession s) {
        try {
            BufferedImage img = s.captureScreen(); // the :N root — no :0 focus, non-intrusive
            Rectangle screen = s.screen();
            if (isBlank(img)) img = null;
            if (img != null && screen != null && !screen.isEmpty()) {
                return new Capture(img, screen.x, screen.y, screen.width, screen.height);
            }
            GenericWindow win = s.attached();
            if (win == null) return null;
            BufferedImage windowImg = s.capture();
            if (isBlank(windowImg)) return null;
            Rectangle b = win.getRect();
            return new Capture(windowImg, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Whether a frame counts as "nothing was captured" — {@link Preview#isBlank}, which the agent applies to
     * the same frames on its side of the pipe, so the fast and slow session paths cannot disagree about what an
     * empty root is.
     */
    static boolean isBlank(BufferedImage img) {
        return Preview.isBlank(img);
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

    /**
     * A window on the real {@code :0}, matched by title — <b>unless it is the live session's own container</b>.
     *
     * <p>gamescope renames its output window after the app it hosts, so with a game running on {@code :N} the
     * best {@code :0} match for "Firestone" is gamescope itself. Streaming that looks right and is a trap: the
     * frame is tagged with a host rect, so Interact escalates the {@code :0} controller to real device input and
     * fires clicks <em>into the container</em> — the pointer ends up owned by gamescope or stranded on a held
     * button, and the desktop stops responding. The session route already wins over this one
     * ({@link PilotRoutes}); refusing the window here makes the bad path unreachable rather than merely
     * outranked.
     */
    private Capture captureWindowTarget(String title) {
        try {
            GenericWindow win = resolveWindow(title);
            if (win == null) return null;
            long host = safeHostWindow();
            if (host != 0 && WindowIds.of(win) == host) return null;
            BufferedImage img = NativeControllerFactory.get().captureWindow(win); // no focus — non-intrusive
            if (img == null) return null;
            Rectangle b = win.getRect();
            return new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * One {@link Robot} per thread, built once. Constructing one is not free — it goes through AWT's
     * headless/permission checks and allocates a peer — and the frame loop was paying that on <em>every</em>
     * screen-route frame. It is per-thread rather than shared because {@code Robot} is not documented
     * thread-safe, and null when this JVM has no display at all, which reads as "no frame this tick" like every
     * other failure here.
     */
    private static final ThreadLocal<Robot> ROBOTS = ThreadLocal.withInitial(() -> {
        try {
            return new Robot();
        } catch (Throwable ex) {
            return null;
        }
    });

    private Capture captureBounds(Rectangle b) {
        if (b == null) return null;
        try {
            Robot robot = ROBOTS.get();
            if (robot == null) return null;
            BufferedImage img = robot.createScreenCapture(b);
            return img == null ? null : new Capture(img, b.x, b.y, b.width, b.height);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** {@code 0} — "no session, so no window to refuse" — is the safe answer to every failure here. */
    private long safeHostWindow() {
        try {
            return sessionHostWindow == null ? 0 : sessionHostWindow.getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }

    private CaptureTarget safeDefault() {
        try {
            return settings != null ? settings.defaultTarget() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The screen rects are asked of AWT, which throws {@link java.awt.HeadlessException} where there is no
     * display at all — so each answers {@code null} instead, and {@link #captureBounds} reads that as "no frame
     * this tick" like every other failure here. The frame loop must not die because a screen went away.
     */
    private static Rectangle screenBounds(int index) {
        try {
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (index >= 0 && index < devices.length) {
                return devices[index].getDefaultConfiguration().getBounds();
            }
        } catch (Throwable ignored) {
            // fall through to the primary, which reports its own failure the same way
        }
        return primaryBounds();
    }

    private static Rectangle primaryBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static GenericWindow resolveWindow(String titleSubstring) {
        try {
            return WindowMatch.best(NativeControllerFactory.get().getAllWindows(), titleSubstring);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rectangle virtualBounds() {
        try {
            Rectangle bounds = new Rectangle();
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
            }
            return bounds.isEmpty() ? new Rectangle(0, 0, 1920, 1080) : bounds;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // --- Encoding ---

    /**
     * Encodes a preview JPEG at the shared {@link Preview} settings, or {@code null} on failure.
     *
     * <p>It used to be an {@code ImageIO.write(...,"jpg")} at default quality and full size, which allocated a
     * writer per frame and shipped a 4K desktop as a 4K JPEG. Downscaling is safe here because the pilot's
     * client fits the frame — and maps touches — through the {@code sw}/{@code sh} <em>surface</em> rect in the
     * header, never through the bitmap's own pixel size.
     */
    public static byte[] jpegBytes(BufferedImage img) {
        return Preview.jpeg(img, Preview.MAX_EDGE, Preview.QUALITY);
    }
}
