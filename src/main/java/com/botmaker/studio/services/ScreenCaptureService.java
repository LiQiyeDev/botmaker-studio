package com.botmaker.studio.services;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.studio.emulator.EmulatorProbe;
import com.botmaker.studio.services.capture.DesktopGrab;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.EmulatorTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Editor-time screen capture for cropping image templates. Uses pure-Java {@link Robot} (no SDK/native
 * coupling) so it works on Windows and Linux/X11. The capture flow:
 * <ol>
 *   <li>grab the whole virtual desktop (all monitors) into one {@link BufferedImage};</li>
 *   <li>show a borderless full-screen overlay of that screenshot and let the user rubber-band a region;</li>
 *   <li>crop the screenshot to the selected region and hand it back.</li>
 * </ol>
 * Capturing the screenshot up front (rather than after hiding an overlay) avoids any overlay-in-shot
 * timing issues and lets the selection map 1:1 against real pixels.
 *
 * <p><b>Wayland:</b> {@code Robot} screen capture is blocked under Wayland and returns an all-black
 * image, so on a Wayland session we instead shell out to whichever screenshot CLI is installed
 * ({@code grim} for wlroots/Sway/Hyprland, {@code gnome-screenshot} for GNOME, {@code spectacle} for
 * KDE), grabbing the full screen to a temp PNG and feeding it into the same crop overlay. X11 and
 * Windows keep using {@code Robot}. If no Wayland tool is available we log a clear message and abort.
 */
public final class ScreenCaptureService {

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

    public ScreenCaptureService() {
        this((Supplier<CaptureTarget>) null);
    }

    public ScreenCaptureService(ProjectSettingsService settings) {
        this(settings == null ? null : settings::defaultTarget);
    }

    private ScreenCaptureService(Supplier<CaptureTarget> defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    /**
     * A capture service for a caller that has the project's files but not its services — every editor built
     * from {@link com.botmaker.studio.ui.app.params.ValueEditors}. Without this they constructed the bare
     * service, whose target is always null, so a screen pick asked which screen every single time even though
     * the project had a default recorded.
     */
    public static ScreenCaptureService forProjectFiles(ProjectConfig config) {
        if (config == null) return new ScreenCaptureService();
        return new ScreenCaptureService(
                () -> StudioProjectSettings.read(config.resourcesRoot()).defaultTarget());
    }

    /** The project's default capture target, or {@code null} — asked afresh at each pick. */
    CaptureTarget defaultTarget() {
        return defaultTarget == null ? null : defaultTarget.get();
    }

    /**
     * A capture service bound to {@code context}'s project settings, so it honors the configured default
     * capture target. The single place the argument pickers and the "Pick all" session construct their
     * settings-bound service (previously duplicated as a private {@code screenCapture(context)} helper in
     * each picker).
     */
    public static ScreenCaptureService forProject(CodeEditorService context) {
        return new ScreenCaptureService(new ProjectSettingsService(
                context.getConfig(), context.getState(), context.getEventBus()));
    }

    /**
     * Runs the interactive crop on the FX thread. The capture target is resolved from the project default
     * (a screen or a window); with multiple monitors and no default set the user first picks which screen.
     * The frame is shown 1:1 and the user rubber-bands a region. Calls {@code onCaptured} with the cropped
     * image, or does nothing if the user cancels (Esc / empty selection / chooser) or capture is unavailable.
     */
    public void captureRegion(Window owner, Consumer<BufferedImage> onCaptured) {
        captureRegion(owner, (img, w, h) -> onCaptured.accept(img));
    }

    /**
     * As {@link #captureRegion(Window, Consumer)} but also reports the capture source's physical resolution
     * (the full window/screen pixel size the region was cropped from) so the caller can record it as the
     * template's authored resolution.
     */
    public void captureRegion(Window owner, RegionCapture onCaptured) {
        grabAsync(owner, shot -> showOverlay(owner, shot, onCaptured));
    }

    /** Receives a cropped region plus the physical resolution of the source it was cropped from. */
    @FunctionalInterface
    public interface RegionCapture {
        void onRegion(BufferedImage cropped, int sourceWidth, int sourceHeight);
    }

    /**
     * A captured frame ready to overlay: the pixels, the logical {@link Rectangle2D} bounds to place the
     * overlay over (a whole screen/desktop, or a window's rectangle) and map coordinates against, whether
     * the overlay should go true-fullscreen (single-screen targets) or be positioned at those bounds (window
     * / whole-desktop targets), and whether the grab looked blank (a Wayland black-frame — don't overlay it).
     */
    private record ScreenShot(BufferedImage image, Rectangle2D bounds, boolean fullScreen, boolean blank) {}

    /**
     * Grabs the target's pixels <b>off the FX thread</b> (native focus + {@code Thread.sleep} + Robot/CLI
     * grab all block), then hops back to the FX thread to (optionally) show the screen chooser and hand the
     * finished {@link ScreenShot} to {@code onShot}. Running the grab on the FX thread is what froze the whole
     * machine (the modal overlay was shown before the slow grab returned); this keeps the UI responsive.
     */
    private void grabAsync(Window owner, Consumer<ScreenShot> onShot) {
        Thread t = new Thread(() -> {
            Grab grab;
            try {
                grab = grabOffThread(owner);
            } catch (Throwable ex) {
                System.err.println("Screen capture failed: " + ex.getMessage());
                grab = new Grab(null, null);
            }
            Grab result = grab;
            Platform.runLater(() -> finishGrab(owner, result, onShot));
        }, "screen-capture-grab");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Off-thread grab result: either a finished {@code shot}, or a raw {@code desktopForChooser} image that
     * still needs the FX-thread screen chooser (no default + multiple monitors). Both null means failed.
     */
    private record Grab(ScreenShot shot, BufferedImage desktopForChooser) {}

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
            desktop = grabVirtualDesktop();
        } catch (Exception e) {
            System.err.println("Screen capture failed: " + e.getMessage());
            return new Grab(null, null);
        }
        if (desktop == null) return new Grab(null, null);
        boolean blank = looksBlank(desktop);

        List<Screen> screens = Screen.getScreens();
        if (target instanceof ScreenTarget st && st.index() >= 0 && st.index() < screens.size()) {
            Screen screen = screens.get(st.index()); // remembered default → no dialog
            return new Grab(new ScreenShot(cropToScreen(desktop, screens, screen), screen.getBounds(), true, blank), null);
        }
        if (target instanceof DesktopTarget) {
            // Whole virtual desktop: overlay spans every monitor (positioned, not single-screen fullscreen).
            return new Grab(new ScreenShot(desktop, virtualScreenBounds(screens), false, blank), null);
        }
        if (screens.size() > 1) {
            return new Grab(null, desktop); // unset default → FX-thread chooser
        }
        Screen screen = Screen.getPrimary();
        return new Grab(new ScreenShot(cropToScreen(desktop, screens, screen), screen.getBounds(), true, blank), null);
    }

    /**
     * FX-thread completion of {@link #grabAsync}: runs the screen chooser if one is pending, guards against a
     * blank (Wayland) grab so the user is never trapped behind a black full-screen overlay, and finally hands
     * the finished shot to {@code onShot}.
     */
    private void finishGrab(Window owner, Grab grab, Consumer<ScreenShot> onShot) {
        ScreenShot shot = grab.shot();
        if (shot == null && grab.desktopForChooser() != null) {
            BufferedImage desktop = grab.desktopForChooser();
            List<Screen> screens = Screen.getScreens();
            Screen screen = chooseScreen(owner, screens, desktop);
            if (screen == null) return; // chooser cancelled
            shot = new ScreenShot(cropToScreen(desktop, screens, screen), screen.getBounds(), true, looksBlank(desktop));
        }
        if (shot == null) return;
        if (shot.blank()) {
            showBlankWarning(owner);
            return;
        }
        onShot.accept(shot);
    }

    /** The whole virtual-desktop bounds in JavaFX logical coordinates (union of every screen). */
    private static Rectangle2D virtualScreenBounds(List<Screen> screens) {
        double minX = screens.stream().mapToDouble(s -> s.getBounds().getMinX()).min().orElse(0);
        double minY = screens.stream().mapToDouble(s -> s.getBounds().getMinY()).min().orElse(0);
        double maxX = screens.stream().mapToDouble(s -> s.getBounds().getMaxX()).max().orElse(0);
        double maxY = screens.stream().mapToDouble(s -> s.getBounds().getMaxY()).max().orElse(0);
        return new Rectangle2D(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    /**
     * Shown (instead of a black full-screen overlay) when the grab came back blank — almost always a Wayland
     * session, where {@code Robot} capture is blocked. Dismissible; ties into the force-X11 guidance.
     */
    private void showBlankWarning(Window owner) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("Screen capture unavailable");
        alert.setHeaderText("Couldn't capture the screen");
        alert.setContentText("The capture came back blank. On Linux this almost always means a Wayland "
                + "session — BotMaker needs an X11 (Xorg) session to capture and control the screen. Log out "
                + "and choose the \"Xorg\" / X11 session at the login screen, then try again.");
        alert.showAndWait();
    }

    /**
     * Interactive rubber-band selection returning the chosen region as {@code [x, y, width, height]} in the
     * <b>capture source's</b> own pixel space — see {@link #sourcePoint} for why that, and not the desktop's.
     * Does nothing if the user cancels (Esc / empty selection) or capture is unavailable.
     */
    public void selectRegion(Window owner, Consumer<int[]> onSelected) {
        grabAsync(owner, shot -> showRegionOverlay(owner, shot, onSelected));
    }

    /**
     * Interactive point pick: an overlay with a magnified close-up that follows the cursor and a live
     * coordinate readout; left-click sets the point. Returns {@code [x, y]} in the <b>capture source's</b> own
     * pixel space — see {@link #sourcePoint}. Does nothing if the user cancels (Esc) or capture is
     * unavailable.
     */
    public void pickPoint(Window owner, Consumer<int[]> onPicked) {
        grabAsync(owner, shot -> showPointOverlay(owner, shot, false,
                pick -> onPicked.accept(new int[]{pick.x(), pick.y()})));
    }

    /** What a {@link #pickPoint}-style overlay reports: where the click landed, and the pixel that was under it. */
    public record ScreenPick(int x, int y, java.awt.Color color) {}

    /**
     * The same magnified overlay as {@link #pickPoint}, reporting the <em>colour</em> under the cursor rather
     * than the coordinate — the readout shows the hex, and the lens is what makes a one-pixel target hittable.
     *
     * <p>It reads the pixel out of the frozen screenshot rather than off the live screen, so the colour
     * reported is exactly the one the user was looking at when they clicked, even if the game repainted in
     * between.
     */
    public void pickColor(Window owner, Consumer<ScreenPick> onPicked) {
        grabAsync(owner, shot -> showPointOverlay(owner, shot, true, onPicked));
    }

    /**
     * Asks the user which screen to capture, showing each monitor's details and a live preview
     * thumbnail (cropped from {@code desktop}). The row matching the remembered default
     * ({@link ProjectPreferences#getCaptureScreen()}) is preselected; the chosen index is saved back so
     * the next capture defaults to it. Returns the chosen {@link Screen}, or {@code null} if cancelled.
     */
    private static Screen chooseScreen(Window owner, List<Screen> screens, BufferedImage desktop) {
        ToggleGroup group = new ToggleGroup();
        VBox rows = new VBox(8);
        rows.setPadding(new Insets(12));

        Integer saved = ProjectPreferences.getCaptureScreen();
        int preselect = (saved != null && saved >= 0 && saved < screens.size()) ? saved : 0;

        for (int i = 0; i < screens.size(); i++) {
            Screen screen = screens.get(i);
            javafx.geometry.Rectangle2D b = screen.getBounds();

            ImageView thumb = new ImageView(toFxImage(cropToScreen(desktop, screens, screen)));
            thumb.setPreserveRatio(true);
            thumb.setFitWidth(240);

            StringBuilder detail = new StringBuilder(String.format(
                    "Screen %d — %d×%d  @ (%d, %d)", i + 1,
                    (int) b.getWidth(), (int) b.getHeight(), (int) b.getMinX(), (int) b.getMinY()));
            if (screen.equals(Screen.getPrimary())) detail.append("  •  Primary");
            if (screen.getOutputScaleX() != 1.0) detail.append(String.format("  •  scale ×%.2f", screen.getOutputScaleX()));

            RadioButton radio = new RadioButton();
            radio.setToggleGroup(group);
            radio.setUserData(i);
            if (i == preselect) radio.setSelected(true);

            VBox cell = new VBox(4, thumb, new Label(detail.toString()));
            HBox row = new HBox(8, radio, cell);
            row.setOnMouseClicked(e -> radio.setSelected(true));
            rows.getChildren().add(row);
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Capture screen");
        dialog.setHeaderText("Which screen do you want to capture?");
        dialog.getDialogPane().setContent(new ScrollPane(rows));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        int chosen = (group.getSelectedToggle() != null)
                ? (Integer) group.getSelectedToggle().getUserData() : preselect;
        ProjectPreferences.updateCaptureScreen(chosen);
        return screens.get(chosen);
    }

    /**
     * Crops the full-desktop {@code desktop} image to the pixel region of {@code target}. Maps JavaFX
     * logical screen bounds to source pixels via each screen's output scale; assumes monitors share a
     * scale factor (true for the common case — mixed-DPI layouts may be slightly off).
     */
    private static BufferedImage cropToScreen(BufferedImage desktop, List<Screen> screens, Screen target) {
        double unionMinX = screens.stream().mapToDouble(s -> s.getBounds().getMinX()).min().orElse(0);
        double unionMinY = screens.stream().mapToDouble(s -> s.getBounds().getMinY()).min().orElse(0);
        javafx.geometry.Rectangle2D b = target.getBounds();
        int x = (int) Math.round((b.getMinX() - unionMinX) * target.getOutputScaleX());
        int y = (int) Math.round((b.getMinY() - unionMinY) * target.getOutputScaleY());
        int w = (int) Math.round(b.getWidth() * target.getOutputScaleX());
        int h = (int) Math.round(b.getHeight() * target.getOutputScaleY());
        x = Math.max(0, Math.min(x, desktop.getWidth() - 1));
        y = Math.max(0, Math.min(y, desktop.getHeight() - 1));
        w = Math.max(1, Math.min(w, desktop.getWidth() - x));
        h = Math.max(1, Math.min(h, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }

    // =========================================================================
    // Window capture (via the shared cross-platform native controller)
    // =========================================================================

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

        if (img == null || looksBlank(img)) {
            // Fallback: full-desktop grab (handles Wayland via CLI tools) cropped to the window bounds.
            try {
                BufferedImage desktop = grabVirtualDesktop();
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

    /** Writes {@code image} to {@code target} as PNG, creating parent directories. */
    public void savePng(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        ImageIO.write(image, "png", target.toFile());
    }

    /**
     * Captures the whole desktop into a single image, without ever showing a share picker. Delegates to the
     * shared {@link DesktopGrab} (screenshot CLI under Wayland where {@link Robot} is blocked/prompts,
     * {@code Robot} otherwise). Returns {@code null} if no capture path is available.
     */
    private BufferedImage grabVirtualDesktop() {
        return DesktopGrab.grabVirtualDesktop();
    }

    /** True if every sampled pixel is pure black (heuristic for a failed/Wayland capture). */
    private static boolean looksBlank(BufferedImage img) {
        return DesktopGrab.looksBlank(img);
    }

    private void showOverlay(Window owner, ScreenShot shot, RegionCapture onCaptured) {
        BufferedImage screenshot = shot.image();
        Image fxImage = toFxImage(screenshot);

        ImageView background = new ImageView(fxImage);
        // Display at the target's logical size; the selection is mapped back to image pixels by ratio,
        // which keeps things correct under HiDPI scaling.
        Pane pane = new Pane(background);

        Rectangle selection = new Rectangle();
        selection.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
        selection.setStroke(Color.web("#2f80ed"));
        selection.setStrokeWidth(1.5);
        selection.setVisible(false);
        pane.getChildren().add(selection);

        Stage stage = overlayStage(owner, shot.bounds(), shot.fullScreen(),
                "Drag to select a region to capture. Press Esc to cancel.");

        final double[] origin = new double[2];
        pane.setOnMousePressed(e -> {
            origin[0] = e.getX();
            origin[1] = e.getY();
            selection.setX(e.getX());
            selection.setY(e.getY());
            selection.setWidth(0);
            selection.setHeight(0);
            selection.setVisible(true);
        });
        pane.setOnMouseDragged(e -> {
            double x = Math.min(origin[0], e.getX());
            double y = Math.min(origin[1], e.getY());
            selection.setX(x);
            selection.setY(y);
            selection.setWidth(Math.abs(e.getX() - origin[0]));
            selection.setHeight(Math.abs(e.getY() - origin[1]));
        });
        pane.setOnMouseReleased(e -> {
            BufferedImage cropped = crop(screenshot, pane, selection);
            stage.close();
            if (cropped != null) onCaptured.onRegion(cropped, screenshot.getWidth(), screenshot.getHeight());
        });

        Scene scene = new Scene(pane);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        // Fit the background to the scene so coordinates map predictably.
        background.fitWidthProperty().bind(scene.widthProperty());
        background.fitHeightProperty().bind(scene.heightProperty());
        background.setPreserveRatio(false);

        stage.setScene(scene);
        stage.show();
    }

    /**
     * Rubber-band overlay that reports the selected region in the capture source's pixels. Mirrors
     * {@link #showOverlay} but, instead of cropping the image, maps the selection rectangle through the same
     * pane-to-image scale the crop uses — see {@link #sourcePoint} for why the source's origin, and not the
     * desktop's, is what a picked coordinate is relative to.
     */
    private void showRegionOverlay(Window owner, ScreenShot shot, Consumer<int[]> onSelected) {
        BufferedImage screenshot = shot.image();
        ImageView background = new ImageView(toFxImage(screenshot));
        Pane pane = new Pane(background);

        Rectangle selection = new Rectangle();
        selection.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
        selection.setStroke(Color.web("#2f80ed"));
        selection.setStrokeWidth(1.5);
        selection.setVisible(false);
        pane.getChildren().add(selection);

        Stage stage = overlayStage(owner, shot.bounds(), shot.fullScreen(),
                "Drag to select a region. Press Esc to cancel.");

        final double[] origin = new double[2];
        pane.setOnMousePressed(e -> {
            origin[0] = e.getX();
            origin[1] = e.getY();
            selection.setX(e.getX());
            selection.setY(e.getY());
            selection.setWidth(0);
            selection.setHeight(0);
            selection.setVisible(true);
        });
        pane.setOnMouseDragged(e -> {
            selection.setX(Math.min(origin[0], e.getX()));
            selection.setY(Math.min(origin[1], e.getY()));
            selection.setWidth(Math.abs(e.getX() - origin[0]));
            selection.setHeight(Math.abs(e.getY() - origin[1]));
        });
        pane.setOnMouseReleased(e -> {
            stage.close();
            if (selection.getWidth() < 3 || selection.getHeight() < 3) return;
            onSelected.accept(sourceRect(shot, pane, selection));
        });

        Scene scene = new Scene(pane);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        background.fitWidthProperty().bind(scene.widthProperty());
        background.fitHeightProperty().bind(scene.heightProperty());
        background.setPreserveRatio(false);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Point-pick overlay: a zoomed close-up follows the cursor with a crosshair and a live readout; left-click
     * reports the point as logical desktop coordinates, together with the pixel that was under it. Esc cancels.
     *
     * <p>{@code showColor} only changes what the readout says — coordinates for a point pick, the hex value
     * for a colour pick. Both picks want the same lens, and one overlay is what keeps them from drifting.
     */
    private void showPointOverlay(Window owner, ScreenShot shot, boolean showColor, Consumer<ScreenPick> onPicked) {
        BufferedImage screenshot = shot.image();
        Image fxImage = toFxImage(screenshot);
        ImageView background = new ImageView(fxImage);
        Pane pane = new Pane(background);

        // Mutable so the scroll wheel can change it: an array rather than a field, since the overlay is a
        // local scene that lives only as long as the pick.
        final double[] zoom = {16.0};
        final double lensSize = 220;
        ImageView lens = new ImageView(fxImage);
        lens.setManaged(false);
        lens.setFitWidth(lensSize);
        lens.setFitHeight(lensSize);
        lens.setPreserveRatio(false);
        // The whole point of a loupe is to see *one* pixel. Smoothing blends it into its neighbours, which is
        // exactly the question being asked — so off, and the pixels read as the squares they are.
        lens.setSmooth(false);
        lens.setVisible(false);
        Rectangle lensBorder = new Rectangle(lensSize, lensSize);
        lensBorder.setManaged(false);
        lensBorder.setFill(Color.TRANSPARENT);
        lensBorder.setStroke(Color.web("#2f80ed"));
        lensBorder.setStrokeWidth(2);
        lensBorder.setVisible(false);
        // The one pixel under the cursor, boxed at the lens centre — without it the magnified field is all
        // equally in focus and "which square am I about to take" is a guess.
        Rectangle crosshair = new Rectangle();
        crosshair.setManaged(false);
        crosshair.setFill(Color.TRANSPARENT);
        crosshair.setStroke(Color.WHITE);
        crosshair.setStrokeWidth(1);
        crosshair.setVisible(false);
        Rectangle crosshairOutline = new Rectangle();
        crosshairOutline.setManaged(false);
        crosshairOutline.setFill(Color.TRANSPARENT);
        crosshairOutline.setStroke(Color.BLACK);
        crosshairOutline.setStrokeWidth(1);
        crosshairOutline.setVisible(false);
        Label readout = new Label();
        readout.setManaged(false);
        readout.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; -fx-padding: 2 6 2 6; -fx-font-family: monospace;");
        readout.setVisible(false);
        Rectangle swatch = new Rectangle(14, 14);
        swatch.setStroke(Color.web("#ffffff", 0.6));
        if (showColor) readout.setGraphic(swatch);
        pane.getChildren().addAll(lens, lensBorder, crosshairOutline, crosshair, readout);

        Stage stage = overlayStage(owner, shot.bounds(), shot.fullScreen(), showColor
                ? "Move over the colour you want and click to take it. Scroll to zoom. Press Esc to cancel."
                : "Move to a spot and click to set the point. Scroll to zoom. Press Esc to cancel.");

        // The last cursor position, so a scroll can re-draw the lens where the pointer already is instead of
        // waiting for the next move.
        final double[] at = {-1, -1};
        Runnable place = () -> {
            if (at[0] < 0) return;
            double mx = at[0], my = at[1];
            double sx = screenshot.getWidth() / pane.getWidth();
            double sy = screenshot.getHeight() / pane.getHeight();
            double px = mx * sx, py = my * sy;
            double viewW = lensSize / zoom[0], viewH = lensSize / zoom[0];
            double vx = clamp(px - viewW / 2, 0, Math.max(0, screenshot.getWidth() - viewW));
            double vy = clamp(py - viewH / 2, 0, Math.max(0, screenshot.getHeight() - viewH));
            lens.setViewport(new javafx.geometry.Rectangle2D(vx, vy, viewW, viewH));
            // Place lens near the cursor without covering it.
            double lx = mx + 16, ly = my + 16;
            if (lx + lensSize > pane.getWidth()) lx = mx - lensSize - 16;
            if (ly + lensSize > pane.getHeight()) ly = my - lensSize - 16;
            lens.relocate(lx, ly);
            lensBorder.relocate(lx, ly);

            // The square the cursor is over, in lens coordinates. Derived from the viewport rather than
            // assumed to be the centre, which it is not once the lens is clamped against an edge.
            double cell = zoom[0];
            double cx = lx + (Math.floor(px) - vx) * cell;
            double cy = ly + (Math.floor(py) - vy) * cell;
            crosshair.setWidth(cell);
            crosshair.setHeight(cell);
            crosshair.relocate(cx, cy);
            crosshairOutline.setWidth(cell + 2);
            crosshairOutline.setHeight(cell + 2);
            crosshairOutline.relocate(cx - 1, cy - 1);

            if (showColor) {
                java.awt.Color c = pixelAt(screenshot, pane, mx, my);
                readout.setText("#%02X%02X%02X".formatted(c.getRed(), c.getGreen(), c.getBlue()));
                swatch.setFill(Color.rgb(c.getRed(), c.getGreen(), c.getBlue()));
            } else {
                // The number the pick will report, not the desktop one — the readout is a preview of the
                // value, and a readout that said something else was how the offset went unnoticed.
                int[] under = sourcePoint(shot, pane, mx, my);
                readout.setText(under[0] + ", " + under[1]);
            }
            readout.relocate(lx, ly + lensSize + 2);
            lens.setVisible(true);
            lensBorder.setVisible(true);
            crosshair.setVisible(true);
            crosshairOutline.setVisible(true);
            readout.setVisible(true);
        };
        pane.setOnMouseMoved(e -> {
            at[0] = e.getX();
            at[1] = e.getY();
            place.run();
        });
        // Scroll to magnify. 4× still shows context, 64× is one pixel filling a third of the lens — a range
        // the fixed 8× could not cover, and the reason picking a colour off an anti-aliased edge was a lottery.
        pane.setOnScroll(e -> {
            double next = zoom[0] * (e.getDeltaY() > 0 ? 1.5 : 1 / 1.5);
            zoom[0] = clamp(next, 4, 64);
            place.run();
        });
        pane.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            stage.close();
            int[] picked = sourcePoint(shot, pane, e.getX(), e.getY());
            onPicked.accept(new ScreenPick(picked[0], picked[1], pixelAt(screenshot, pane, e.getX(), e.getY())));
        });

        Scene scene = new Scene(pane);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
        background.fitWidthProperty().bind(scene.widthProperty());
        background.fitHeightProperty().bind(scene.heightProperty());
        background.setPreserveRatio(false);
        stage.setScene(scene);
        stage.show();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }

    // =========================================================================
    // What a pick reports: the capture source's own pixel space, never the desktop's
    // =========================================================================

    /**
     * A point on an overlay, as a coordinate <b>inside the frame that was captured</b> — the top-left of the
     * chosen screen, window or virtual desktop being {@code 0,0}.
     *
     * <p><b>This is the fix for a point picked on the second monitor.</b> Every pick used to add
     * {@code shot.bounds().getMin*()}, the source's position on the desktop, and hand back a desktop-absolute
     * number. But a coordinate leaves this dialog to be read by a <em>bot</em>, and a bot never sees the
     * desktop: it reads one capture source and works in that source's space, which is the contract
     * {@code docs/display-pipeline.md} §7 states for the pilot and §6 for the bot's own lossless read. A point
     * picked on a screen whose origin is {@code 1920,0} was therefore a whole monitor to the right of where it
     * was clicked, the moment anything used it.
     *
     * <p>The same overlay already proved the point: an {@link PickStep.ImageStep} crop is cut with
     * {@link #crop}, in source pixels, so within one "Pick all" pass the template was source-relative while
     * the {@code Point} beside it was desktop-absolute. They cannot both have been right.
     *
     * <p>Whole-desktop targets are unaffected in practice — their origin is the virtual desktop's, normally
     * {@code 0,0} — so a bot driving the real desktop keeps the numbers it had.
     *
     * <p>Scaled through the image rather than taken as logical pixels, for the same reason {@link #crop} is:
     * on a HiDPI screen the grab is in device pixels and the overlay is laid out in logical ones, and it is
     * the grab a template is matched against.
     */
    private static int[] sourcePoint(ScreenShot shot, Pane pane, double x, double y) {
        return new int[]{
                (int) Math.round(x * scaleX(shot, pane)),
                (int) Math.round(y * scaleY(shot, pane))};
    }

    /** A selection rectangle in the same space {@link #sourcePoint} reports — {@code [x, y, w, h]}. */
    private static int[] sourceRect(ScreenShot shot, Pane pane, Rectangle selection) {
        double sx = scaleX(shot, pane);
        double sy = scaleY(shot, pane);
        return new int[]{
                (int) Math.round(selection.getX() * sx),
                (int) Math.round(selection.getY() * sy),
                (int) Math.round(selection.getWidth() * sx),
                (int) Math.round(selection.getHeight() * sy)};
    }

    /** Source pixels per pane pixel; 1.0 before the pane has been laid out, which is the honest fallback. */
    private static double scaleX(ScreenShot shot, Pane pane) {
        return pane.getWidth() <= 0 ? 1 : shot.image().getWidth() / pane.getWidth();
    }

    private static double scaleY(ScreenShot shot, Pane pane) {
        return pane.getHeight() <= 0 ? 1 : shot.image().getHeight() / pane.getHeight();
    }

    /**
     * The pixel of {@code screenshot} under a mouse position given in {@code pane}'s coordinates.
     *
     * <p>Read out of the frozen screenshot rather than off the live screen, so what the readout previewed and
     * what the click commits are the same colour even if the game repainted in between.
     */
    private static java.awt.Color pixelAt(BufferedImage screenshot, Pane pane, double mouseX, double mouseY) {
        int px = (int) clamp(mouseX * screenshot.getWidth() / pane.getWidth(), 0, screenshot.getWidth() - 1);
        int py = (int) clamp(mouseY * screenshot.getHeight() / pane.getHeight(), 0, screenshot.getHeight() - 1);
        return new java.awt.Color(screenshot.getRGB(px, py), false);
    }

    // =========================================================================
    // Multi-argument capture session (one frame + one overlay for a whole call)
    // =========================================================================

    /** One argument to pick during a {@link #runSession} pass. The label names the block + arg + type. */
    public sealed interface PickStep {
        String label();

        /** A {@code Rect} region → {@code [x, y, w, h]} in the capture source's pixels ({@link #sourcePoint}). */
        record RegionStep(String label, Consumer<int[]> onResult) implements PickStep {}

        /** A {@code Point} → {@code [x, y]} in the capture source's pixels ({@link #sourcePoint}). */
        record PointStep(String label, Consumer<int[]> onResult) implements PickStep {}

        /** An {@code ImageTemplate} → the crop and the frame it came from (caller names + saves it). */
        record ImageStep(String label, Consumer<CapturedCrop> onResult) implements PickStep {}
    }

    /**
     * A crop taken during a {@link #runSession} pass, together with the frame it was cut out of.
     *
     * <p>The frame size and target title are what a template's sidecar records as its <em>reference
     * resolution</em>, so a match can be scaled when the bot runs against a differently-sized surface. The
     * crop alone cannot answer that — it is a sub-rectangle, and the numbers that matter belong to the whole
     * grab — which is why they travel with it rather than being re-derived by the caller.
     */
    public record CapturedCrop(BufferedImage image, int frameWidth, int frameHeight, String targetTitle) {}

    /**
     * Captures the target once and drives a single reusable overlay through {@code steps} in order — for a
     * whole method call's on-screen arguments. Each step shows which block/arg/type is being picked plus
     * live coordinates; the overlay stays open until every step is done (or the user quits with the button
     * or Esc). Applies each result via the step's consumer on the FX thread.
     */
    public void runSession(Window owner, List<PickStep> steps, Runnable onDone) {
        if (steps == null || steps.isEmpty()) return;
        grabAsync(owner, shot -> new SessionOverlay(owner, shot, steps, onDone).start());
    }

    /** Height of the top instruction/Quit band; presses inside it don't start a selection. */
    private static final double SESSION_HEADER_H = 44;

    /**
     * The title a template captured through this service records as the window it came from, or {@code null}
     * for a screen/desktop grab (there is no window to name). Same rule as the capture toolbar's own.
     */
    private String targetTitle() {
        CaptureTarget target = defaultTarget();
        return (target instanceof WindowTarget wt) ? wt.titleSubstring() : null;
    }

    /** A single overlay reused across a call's pick steps: swaps mouse handlers + header per step. */
    private final class SessionOverlay {
        private final Window owner;
        private final ScreenShot shot;
        private final List<PickStep> steps;
        private final Runnable onDone;

        private final Stage stage;
        private final Pane pane;
        private final Rectangle selection = new Rectangle();
        private final ImageView lens;
        private final Rectangle lensBorder = new Rectangle(140, 140);
        private final Label readout = new Label();
        private final Label header = new Label();
        private int index = 0;
        private final double[] origin = new double[2];
        /** One-shot guard: {@link #onDone} runs exactly once, whichever way the overlay closes. */
        private boolean finished;

        SessionOverlay(Window owner, ScreenShot shot, List<PickStep> steps, Runnable onDone) {
            this.owner = owner;
            this.shot = shot;
            this.steps = steps;
            this.onDone = onDone;
            // Assigned before the Quit/Esc handlers below capture it (a blank final field read inside a
            // lambda must be definitely assigned at the point the lambda is created).
            this.stage = overlayStage(owner, shot.bounds(), shot.fullScreen(), null);

            Image fx = toFxImage(shot.image());
            ImageView background = new ImageView(fx);
            this.pane = new Pane(background);

            selection.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
            selection.setStroke(Color.web("#2f80ed"));
            selection.setStrokeWidth(1.5);
            selection.setVisible(false);

            lens = new ImageView(fx);
            lens.setManaged(false);
            lens.setFitWidth(140);
            lens.setFitHeight(140);
            lens.setPreserveRatio(false);
            lens.setVisible(false);
            lensBorder.setManaged(false);
            lensBorder.setFill(Color.TRANSPARENT);
            lensBorder.setStroke(Color.web("#2f80ed"));
            lensBorder.setStrokeWidth(2);
            lensBorder.setVisible(false);
            readout.setManaged(false);
            readout.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; -fx-padding: 2 6 2 6; -fx-font-family: monospace;");
            readout.setVisible(false);

            header.setManaged(false);
            header.setStyle("-fx-background-color: rgba(0,0,0,0.8); -fx-text-fill: white; -fx-padding: 8 12 8 12; -fx-font-size: 13px;");
            header.setMouseTransparent(true);

            Button quit = new Button("Quit");
            quit.setManaged(false);
            quit.setOnAction(e -> stage.close());

            pane.getChildren().addAll(selection, lens, lensBorder, readout, header, quit);

            Scene scene = new Scene(pane);
            scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.close(); });
            background.fitWidthProperty().bind(scene.widthProperty());
            background.fitHeightProperty().bind(scene.heightProperty());
            background.setPreserveRatio(false);
            header.prefWidthProperty().bind(scene.widthProperty());
            quit.setLayoutY(6);
            quit.layoutXProperty().bind(scene.widthProperty().subtract(72));
            stage.setScene(scene);

            // The callback hangs off the stage closing rather than off the last step completing, so that all
            // three exits — the final pick, the Quit button and Esc — apply what the user picked. Quitting
            // half-way through a call's arguments keeps the picks already made, which is what "Capture many"
            // does with a partial selection; dropping them would be the more surprising of the two.
            stage.setOnHidden(e -> {
                if (finished) return;
                finished = true;
                if (onDone != null) onDone.run();
            });
        }

        void start() {
            activate(0);
            stage.show();
        }

        private void activate(int i) {
            this.index = i;
            if (i >= steps.size()) { stage.close(); return; }
            PickStep step = steps.get(i);
            header.setText(String.format("(%d/%d)  %s", i + 1, steps.size(), step.label()));
            header.relocate(0, 0);
            selection.setVisible(false);
            lens.setVisible(false);
            lensBorder.setVisible(false);
            readout.setVisible(false);

            if (step instanceof PickStep.PointStep) {
                installPointHandlers();
            } else {
                installRegionHandlers(); // region + image both rubber-band
            }
        }

        private void installPointHandlers() {
            pane.setOnMousePressed(null);
            pane.setOnMouseDragged(null);
            pane.setOnMouseReleased(null);
            pane.setOnMouseMoved(e -> {
                if (e.getY() < SESSION_HEADER_H) { lens.setVisible(false); lensBorder.setVisible(false); readout.setVisible(false); return; }
                double zoom = 8.0, lensSize = 140;
                double sx = shot.image().getWidth() / pane.getWidth();
                double sy = shot.image().getHeight() / pane.getHeight();
                double px = e.getX() * sx, py = e.getY() * sy;
                double viewW = lensSize / zoom, viewH = lensSize / zoom;
                double vx = clamp(px - viewW / 2, 0, shot.image().getWidth() - viewW);
                double vy = clamp(py - viewH / 2, 0, shot.image().getHeight() - viewH);
                lens.setViewport(new Rectangle2D(vx, vy, viewW, viewH));
                double lx = e.getX() + 16, ly = e.getY() + 16;
                if (lx + lensSize > pane.getWidth()) lx = e.getX() - lensSize - 16;
                if (ly + lensSize > pane.getHeight()) ly = e.getY() - lensSize - 16;
                lens.relocate(lx, ly);
                lensBorder.relocate(lx, ly);
                int[] over = sourcePoint(shot, pane, e.getX(), e.getY());
                readout.setText(over[0] + ", " + over[1]);
                readout.relocate(lx, ly + lensSize + 2);
                lens.setVisible(true); lensBorder.setVisible(true); readout.setVisible(true);
            });
            pane.setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getY() < SESSION_HEADER_H) return;
                PickStep step = steps.get(index);
                if (step instanceof PickStep.PointStep ps) {
                    ps.onResult().accept(sourcePoint(shot, pane, e.getX(), e.getY()));
                }
                pane.setOnMouseClicked(null);
                activate(index + 1);
            });
        }

        private void installRegionHandlers() {
            pane.setOnMouseMoved(null);
            pane.setOnMouseClicked(null);
            pane.setOnMousePressed(e -> {
                if (e.getY() < SESSION_HEADER_H) return;
                origin[0] = e.getX(); origin[1] = e.getY();
                selection.setX(e.getX()); selection.setY(e.getY());
                selection.setWidth(0); selection.setHeight(0); selection.setVisible(true);
            });
            pane.setOnMouseDragged(e -> {
                if (!selection.isVisible()) return;
                selection.setX(Math.min(origin[0], e.getX()));
                selection.setY(Math.min(origin[1], e.getY()));
                selection.setWidth(Math.abs(e.getX() - origin[0]));
                selection.setHeight(Math.abs(e.getY() - origin[1]));
            });
            pane.setOnMouseReleased(e -> {
                if (!selection.isVisible() || selection.getWidth() < 3 || selection.getHeight() < 3) return;
                PickStep step = steps.get(index);
                if (step instanceof PickStep.RegionStep rs) {
                    rs.onResult().accept(sourceRect(shot, pane, selection));
                } else if (step instanceof PickStep.ImageStep is) {
                    BufferedImage cropped = crop(shot.image(), pane, selection);
                    if (cropped != null) {
                        is.onResult().accept(new CapturedCrop(cropped,
                                shot.image().getWidth(), shot.image().getHeight(), targetTitle()));
                    }
                }
                pane.setOnMousePressed(null);
                pane.setOnMouseDragged(null);
                pane.setOnMouseReleased(null);
                activate(index + 1);
            });
        }
    }

    /**
     * A borderless, modal overlay stage covering {@code bounds} (logical coordinates) — shared overlay
     * chrome. For a screen target it opens true-fullscreen on that monitor; for a window target it is
     * positioned and sized exactly over the window's rectangle, so the captured frame shows 1:1 and pane
     * coordinates map directly to {@code bounds.min + offset} (window-relative before the origin is added).
     */
    private static Stage overlayStage(Window owner, Rectangle2D bounds, boolean fullScreen, String hint) {
        Stage stage = new Stage(StageStyle.UNDECORATED);
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        // Let always-on-top HUDs (e.g. the Overlay Editor) hide themselves while this draw surface is up so
        // they don't obscure it; restored when the overlay closes.
        stage.setOnShowing(e -> fireCaptureOverlayShown());
        stage.setOnHidden(e -> fireCaptureOverlayHidden());
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        if (fullScreen) {
            stage.setFullScreen(true);
            stage.setFullScreenExitHint(hint);
            stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
        } else {
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            stage.setAlwaysOnTop(true);
        }
        return stage;
    }

    /** Maps the on-screen selection rectangle back to source-image pixels and returns the sub-image. */
    private static BufferedImage crop(BufferedImage source, Pane pane, Rectangle selection) {
        if (selection.getWidth() < 3 || selection.getHeight() < 3) return null;
        double scaleX = source.getWidth() / pane.getWidth();
        double scaleY = source.getHeight() / pane.getHeight();
        int x = (int) Math.round(selection.getX() * scaleX);
        int y = (int) Math.round(selection.getY() * scaleY);
        int w = (int) Math.round(selection.getWidth() * scaleX);
        int h = (int) Math.round(selection.getHeight() * scaleY);
        x = Math.max(0, Math.min(x, source.getWidth() - 1));
        y = Math.max(0, Math.min(y, source.getHeight() - 1));
        w = Math.max(1, Math.min(w, source.getWidth() - x));
        h = Math.max(1, Math.min(h, source.getHeight() - y));
        return source.getSubimage(x, y, w, h);
    }

    // ── Capture-overlay visibility hooks ────────────────────────────────────────────────────────────────
    // A single, process-wide notification when any interactive capture overlay (region / point / template
    // draw surface, all built via overlayStage) is shown or hidden. Always-on-top HUDs like the Overlay
    // Editor subscribe so they can hide themselves for the duration and not sit over the draw surface.

    /** Notified when a capture overlay is shown / hidden. */
    public interface CaptureOverlayListener {
        void onShown();
        void onHidden();
    }

    private static final java.util.List<CaptureOverlayListener> OVERLAY_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers {@code listener}; returns a handle that unregisters it when closed. */
    public static AutoCloseable addCaptureOverlayListener(CaptureOverlayListener listener) {
        OVERLAY_LISTENERS.add(listener);
        return () -> OVERLAY_LISTENERS.remove(listener);
    }

    private static void fireCaptureOverlayShown() {
        for (CaptureOverlayListener l : OVERLAY_LISTENERS) l.onShown();
    }

    private static void fireCaptureOverlayHidden() {
        for (CaptureOverlayListener l : OVERLAY_LISTENERS) l.onHidden();
    }

    /**
     * Converts a {@link BufferedImage} to a JavaFX {@link Image} via in-memory PNG (no javafx.swing dep).
     * Returns {@code null} for a {@code null} image, so callers feeding it a best-effort grab (a window that
     * couldn't be captured, a stopped emulator) can pass the result straight through.
     */
    public static Image toFxImage(BufferedImage image) {
        if (image == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to render screenshot", e);
        }
    }
}
