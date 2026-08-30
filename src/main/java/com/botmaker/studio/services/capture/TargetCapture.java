package com.botmaker.studio.services.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorProbe;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.EmulatorTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.services.ProjectSettingsService;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <b>Which pixels</b> — the half of editor-time capture that knows what a capture target is.
 *
 * <p>It resolves the project's default target and grabs it: a window it raises and focuses first, a monitor
 * cropped out of a desktop grab, the whole virtual desktop, or an emulator's frame pulled over ADB. It also
 * owns everything else that needs a native window handle — bounds probes, raise, resize, the title list the
 * target chooser offers.
 *
 * <p><b>This class is on its way out of Studio, and the split that isolates it is the point.</b> A capture
 * target is the SDK's vocabulary rather than an editor's — a window to look at is what a bot's own
 * {@code CaptureSource} names — so everything here belongs to the plugin that owns that vocabulary. What
 * stays behind is {@link ScreenOverlay}, which consumes a {@link ScreenShot} and never asks where it came
 * from. Split, the move becomes a move; unsplit, it was 1,324 lines with no seam in them.
 *
 * <p>It implements {@link ShotSource}, which is the whole of what the overlay needs from it — and therefore
 * the whole of what has to exist on the far side afterwards.
 *
 * <h2>Two things worth knowing before changing anything here</h2>
 *
 * <p><b>An emulator is captured the way the bot captures it, not the way it looks.</b> {@link #emulatorShot}
 * pulls a frame over ADB rather than grabbing the host window the emulator is drawn in. Without that branch a
 * template was cropped out of the host window while the bot matched it against the ADB frame — on Waydroid
 * those two are a scale factor apart, so nothing ever matched and the crop looked perfectly accurate, because
 * it was accurate in the space it was taken in.
 *
 * <p><b>A window capture that comes back blank falls through to a desktop crop.</b> Native per-window capture
 * returns black under Wayland; the fallback grabs the whole desktop (which has a CLI path there) and crops it
 * to the window's bounds.
 */
public final class TargetCapture implements ShotSource {

    /**
     * Where the project's default capture target comes from, or {@code null} when the caller has no project
     * context. When a default is set the pickers use it directly and skip the chooser.
     *
     * <p>A supplier rather than the {@link ProjectSettingsService} itself, because the two callers want it
     * from different places: a picker inside the editor has the live service, while a picker in a dialog that
     * was handed nothing but a {@link ProjectConfig} — the Parameters dialog, the Runner, the Variables
     * screen — can still read the same {@code settings.json} off disk. Asking for the target at pick time
     * rather than at construction is also what lets a target chosen in one window be honoured in another
     * without anybody re-wiring a service.
     */
    private final Supplier<CaptureTarget> defaultTarget;

    public TargetCapture() {
        this((Supplier<CaptureTarget>) null);
    }

    public TargetCapture(ProjectSettingsService settings) {
        this(settings == null ? null : settings::defaultTarget);
    }

    private TargetCapture(Supplier<CaptureTarget> defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    /**
     * A capture source for a caller that has the project's files but not its services — every editor built
     * from {@code ui.app.params.ValueEditors}. Without this they constructed the bare service, whose target
     * is always null, so a screen pick asked which screen every single time even though the project had a
     * default recorded.
     */
    public static TargetCapture forProjectFiles(ProjectConfig config) {
        if (config == null) return new TargetCapture();
        return new TargetCapture(() -> StudioProjectSettings.read(config.resourcesRoot()).defaultTarget());
    }

    /** The project's default capture target, or {@code null} — asked afresh at each pick. */
    public CaptureTarget defaultTarget() {
        return defaultTarget == null ? null : defaultTarget.get();
    }

    @Override
    public Grab grab(Window owner) {
        return grabOffThread(owner);
    }

    /**
     * The title a template captured through this source records as the window it came from, or {@code null}
     * for a screen/desktop grab (there is no window to name). Same rule as the capture toolbar's own.
     */
    @Override
    public String title() {
        CaptureTarget target = defaultTarget();
        return (target instanceof WindowTarget wt) ? wt.titleSubstring() : null;
    }

    /**
     * Resolves the capture target and grabs its pixels (blocking — call off the FX thread only).
     *
     * <ul>
     *   <li>default is a window → focus + capture that window ({@link #captureWindow});</li>
     *   <li>default is a screen → grab the desktop and crop to that monitor (no dialog);</li>
     *   <li>default is the whole desktop (or unset on a single monitor) → the whole virtual desktop;</li>
     *   <li>unset default + multiple monitors → return the desktop image for the FX-thread chooser.</li>
     * </ul>
     */
    private Grab grabOffThread(Window owner) {
        CaptureTarget target = defaultTarget();

        if (target instanceof WindowTarget wt) {
            WindowShot ws = captureWindow(wt);
            if (ws == null) return new Grab(null, null);
            java.awt.Rectangle b = ws.bounds();
            Rectangle2D bounds = new Rectangle2D(b.x, b.y, b.width, b.height);
            return new Grab(new ScreenShot(ws.image(), bounds, false, false), null);
        }

        BufferedImage desktop;
        try {
            desktop = DesktopGrab.grabVirtualDesktop();
        } catch (Exception e) {
            System.err.println("Screen capture failed: " + e.getMessage());
            return new Grab(null, null);
        }
        if (desktop == null) return new Grab(null, null);
        boolean blank = DesktopGrab.looksBlank(desktop);

        List<Screen> screens = Screen.getScreens();
        if (target instanceof ScreenTarget st && st.index() >= 0 && st.index() < screens.size()) {
            Screen screen = screens.get(st.index()); // remembered default → no dialog
            return new Grab(new ScreenShot(Screens.cropToScreen(desktop, screens, screen), screen.getBounds(), true, blank), null);
        }
        if (target instanceof DesktopTarget) {
            // Whole virtual desktop: overlay spans every monitor (positioned, not single-screen fullscreen).
            return new Grab(new ScreenShot(desktop, Screens.virtualScreenBounds(screens), false, blank), null);
        }
        if (screens.size() > 1) {
            return new Grab(null, desktop); // unset default → FX-thread chooser
        }
        Screen screen = Screen.getPrimary();
        return new Grab(new ScreenShot(Screens.cropToScreen(desktop, screens, screen), screen.getBounds(), true, blank), null);
    }


    /** A captured window frame plus its absolute screen bounds (for overlay placement + coordinate mapping). */
    public record WindowShot(BufferedImage image, java.awt.Rectangle bounds) {}

    /**
     * Brings the window matching {@code target} to the front and captures its pixels. Returns
     * {@code null} if no window matches or capture fails. The window is focused first — both to satisfy
     * "move the chosen window to front" and because the Robot-based capture paths need it visible. If the
     * native per-window capture yields a blank frame (e.g. native Wayland, where Robot returns black),
     * this falls back to a full-desktop grab cropped to the window bounds (Wayland-capable, lossless).
     */
    public WindowShot captureWindow(WindowTarget target) {
        GenericWindow win = resolveWindow(target);
        if (win == null) {
            System.err.println("No window matching \"" + target.titleSubstring() + "\" was found.");
            return null;
        }
        NativeController controller = NativeControllerFactory.get();
        // Restore (de-iconify if minimized) + raise + focus, then let the compositor settle before grabbing.
        try {
            controller.restoreWindow(win);
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("Could not focus window: " + t.getMessage());
        }
        // Re-resolve after focus (bounds may change when a minimized window is restored/raised).
        GenericWindow refreshed = resolveWindow(target);
        if (refreshed != null) win = refreshed;
        java.awt.Rectangle bounds = win.getRect();

        BufferedImage img = null;
        try {
            img = controller.captureWindow(win);
        } catch (Throwable t) {
            System.err.println("Native window capture failed: " + t.getMessage());
        }

        if (img == null || DesktopGrab.looksBlank(img)) {
            // Fallback: full-desktop grab (handles Wayland via CLI tools) cropped to the window bounds.
            try {
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                BufferedImage cropped = (desktop == null) ? null : cropToBounds(desktop, bounds);
                if (cropped != null) img = cropped;
            } catch (Exception e) {
                System.err.println("Desktop-crop fallback failed: " + e.getMessage());
            }
        }
        return img == null ? null : new WindowShot(img, bounds);
    }

    /**
     * A target-agnostic captured frame: the pixels, the absolute logical {@code bounds} to place an overlay
     * over and map coordinates against, a human {@code label}, whether the source is a window (so callers can
     * skip window-only steps like resize for a screen/desktop target), and whether those pixels are actually
     * <em>on the desktop</em> at {@code bounds}.
     *
     * <p>{@code onScreen} is false for exactly one target kind today — an emulator, whose frame comes over ADB
     * rather than off the desktop. That distinction is load-bearing: a transparent rubber-band surface shows
     * the user whatever is behind it, so over an emulator it would show the host window (gamescope's scaled
     * output, or nothing at all if the emulator is minimised) while the crop is taken from the ADB frame.
     * Callers must draw the frame themselves when this is false — see {@code CaptureSurface}'s backdrop.
     */
    public record TargetShot(BufferedImage image, java.awt.Rectangle bounds, String label, boolean isWindow,
                             boolean onScreen) {}

    /**
     * Off-thread grab of the project's current <b>default</b> capture target — a window, a monitor, the whole
     * desktop, or an emulator — as a {@link TargetShot}, delivered back on the FX thread ({@code null} on
     * failure or a blank Wayland grab). Unlike {@link #captureWindow}, this works for any target type, so
     * overlay tools (Capture Templates / Overlay Editor) can operate over a screen/desktop and not only a
     * window. Requires a default to be set (there is always one after project creation); it does not run the
     * screen chooser.
     */
    public void captureDefaultTargetAsync(Window owner, Consumer<TargetShot> onFx) {
        Thread t = new Thread(() -> {
            TargetShot result = null;
            try {
                TargetShot adb = emulatorShot();
                if (adb != null) {
                    Platform.runLater(() -> onFx.accept(adb));
                    return;
                }
                Grab grab = grabOffThread(owner);
                ScreenShot shot = grab.shot();
                if (shot != null && !shot.blank()) {
                    Rectangle2D b = shot.bounds();
                    java.awt.Rectangle awt = new java.awt.Rectangle(
                            (int) Math.round(b.getMinX()), (int) Math.round(b.getMinY()),
                            (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()));
                    CaptureTarget def = defaultTarget();
                    String label = (def != null)
                            ? com.botmaker.studio.project.capture.CaptureTargetNames.shortLabel(def) : "Screen";
                    result = new TargetShot(shot.image(), awt, label, def instanceof WindowTarget, true);
                }
            } catch (Throwable ex) {
                System.err.println("Default-target capture failed: " + ex.getMessage());
            }
            TargetShot r = result;
            Platform.runLater(() -> onFx.accept(r));
        }, "capture-default-target");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One ADB {@code screencap} of the default target when that target is an <b>emulator</b>, or {@code null}
     * when it isn't one (so the caller falls through to the desktop grab). Blocking — off the FX thread only.
     *
     * <p><b>Why this branch has to exist.</b> Without it an emulator target fell all the way through
     * {@link #grabOffThread} to "grab the virtual desktop", so a template was cropped out of the <em>host
     * window</em> the emulator happens to be drawn in, while the bot matches that template against the frame it
     * pulls over ADB. On Waydroid those two were a scale factor apart and nothing ever matched — the crop
     * looked perfectly accurate, because it was accurate in the space it was taken in. Capturing the same way
     * the bot does makes the two spaces the same one by construction rather than by luck.
     *
     * <p>The bounds are a placement, not a location: these pixels are not on the desktop anywhere (see
     * {@link TargetShot#onScreen()}), so the frame is centred on the primary screen at its own aspect ratio and
     * the surface draws it. Aspect ratio is what matters — every crop is mapped back by the width/height ratio.
     */
    private TargetShot emulatorShot() {
        CaptureTarget target = defaultTarget();
        if (!(target instanceof EmulatorTarget(String instanceName))) {
            return null;
        }
        EmulatorInstance instance = EmulatorInstances.byName(instanceName).orElse(null);
        BufferedImage frame = (instance == null) ? null : EmulatorProbe.screencap(instance);
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return null;   // not running, or the grab failed — the caller reports "couldn't capture the target"
        }
        return new TargetShot(frame, fitToPrimaryScreen(frame.getWidth(), frame.getHeight()),
                com.botmaker.studio.project.capture.CaptureTargetNames.shortLabel(target), false, false);
    }

    /** A {@code w}×{@code h}-shaped rectangle centred on the primary screen, at most 80% of its visual area. */
    private static java.awt.Rectangle fitToPrimaryScreen(int w, int h) {
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        double scale = Math.min(1.0, Math.min(visual.getWidth() * 0.8 / w, visual.getHeight() * 0.8 / h));
        int width = Math.max(1, (int) Math.round(w * scale));
        int height = Math.max(1, (int) Math.round(h * scale));
        return new java.awt.Rectangle(
                (int) Math.round(visual.getMinX() + (visual.getWidth() - width) / 2),
                (int) Math.round(visual.getMinY() + (visual.getHeight() - height) / 2),
                width, height);
    }

    /**
     * The window {@code target} names: its exact {@link WindowTarget#windowId() windowId} when it carries one and
     * that window still exists, otherwise its title substring.
     *
     * <p>The fallback is the whole point of the id being optional. An id belongs to one live process — a session
     * that has since been closed, or a settings file written yesterday, leaves an id that resolves to nothing, and
     * a target that silently captures nothing is the failure mode this service is worst at reporting. Falling back
     * to the title makes a stale id no worse than not having one.
     */
    private static GenericWindow resolveWindow(WindowTarget target) {
        if (target == null) return null;
        Long id = target.windowId();
        if (id != null) {
            GenericWindow byId = resolveWindowById(id);
            if (byId != null) return byId;
        }
        return resolveWindow(target.titleSubstring());
    }

    /** The window whose native handle is {@code windowId}, or {@code null} when no live window has that id. */
    private static GenericWindow resolveWindowById(long windowId) {
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                if (nativeIdOf(w) == windowId) return w;
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return null;
    }

    /** A window's platform handle as a plain long — a JNA {@code Pointer} on X11, a {@code Number} elsewhere. */
    private static long nativeIdOf(GenericWindow w) {
        Object handle = w.getNativeHandle();
        if (handle instanceof com.sun.jna.Pointer p) return com.sun.jna.Pointer.nativeValue(p);
        return handle instanceof Number n ? n.longValue() : 0;
    }

    /**
     * First window (case-insensitive) whose title contains {@code titleSubstring}, or {@code null}. Includes
     * currently-minimized windows so a minimized target can be found and then de-iconified/raised.
     */
    private static GenericWindow resolveWindow(String titleSubstring) {
        if (titleSubstring == null) return null;
        String needle = titleSubstring.toLowerCase();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                String t = w.getTitle();
                if (t != null && t.toLowerCase().contains(needle)) return w;
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * The current absolute bounds of the window matching {@code target}, or {@code null} when no window
     * matches. A bounds-only probe: unlike {@link #captureWindow} it neither raises the window, nor sleeps for
     * the compositor, nor grabs any pixels, so it is cheap enough to call from the FX thread. That is what the
     * overlay recorder needs when a session starts — the origin its coordinates are relative to, nothing else.
     */
    public static java.awt.Rectangle windowBounds(WindowTarget target) {
        GenericWindow win = resolveWindow(target);
        return win == null ? null : win.getRect();
    }

    /**
     * Brings the window matching {@code target} to the front (de-iconifying if minimized) without capturing.
     * Best-effort; used by the macro recorder so the target is raised when recording begins.
     */
    public void raiseWindow(WindowTarget target) {
        GenericWindow win = resolveWindow(target);
        if (win == null) return;
        try {
            NativeControllerFactory.get().restoreWindow(win);
        } catch (Throwable t) {
            System.err.println("Could not raise window: " + t.getMessage());
        }
    }

    /**
     * Resizes the window matching {@code target} to {@code width}×{@code height} (logical px) and lets the
     * compositor settle, so a template is captured at the project's canonical resolution rather than whatever
     * size the window happens to be. Best-effort no-op when the window can't be found or is already that size.
     * Runs synchronously; call it off the FX thread (it sleeps briefly).
     */
    public void resizeTarget(WindowTarget target, int width, int height) {
        if (width <= 0 || height <= 0) return;
        GenericWindow win = resolveWindow(target);
        if (win == null) return;
        java.awt.Rectangle r = win.getRect();
        if (r != null && r.width == width && r.height == height) return; // already canonical
        try {
            NativeControllerFactory.get().resizeWindow(win, width, height);
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("Could not resize window: " + t.getMessage());
        }
    }

    /** Enumerates the titles of the currently open windows (for the target chooser). Best-effort. */
    public static List<String> listWindowTitles() {
        List<String> titles = new java.util.ArrayList<>();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
                String t = w.getTitle();
                if (t != null && !t.isBlank() && !titles.contains(t)) titles.add(t);
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return titles;
    }

    /**
     * Crops the full-desktop {@code desktop} image to absolute-screen {@code bounds}. Maps absolute
     * coordinates to desktop-image pixels via the AWT virtual-screen origin (union of all devices).
     * Assumes scale 1.0 (same caveat as {@link #cropToScreen}); this is only the blank-frame fallback.
     */
    private static BufferedImage cropToBounds(BufferedImage desktop, java.awt.Rectangle bounds) {
        java.awt.Rectangle virtual = new java.awt.Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            virtual = virtual.union(gd.getDefaultConfiguration().getBounds());
        }
        int x = Math.max(0, Math.min(bounds.x - virtual.x, desktop.getWidth() - 1));
        int y = Math.max(0, Math.min(bounds.y - virtual.y, desktop.getHeight() - 1));
        int w = Math.max(1, Math.min(bounds.width, desktop.getWidth() - x));
        int h = Math.max(1, Math.min(bounds.height, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }
}
