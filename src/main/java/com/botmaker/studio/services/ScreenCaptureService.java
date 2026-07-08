package com.botmaker.studio.services;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.studio.services.capture.DesktopGrab;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
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
     * Per-project settings (the saved capture targets + default), or {@code null} when the caller has no
     * project context. When a default target is set the pickers use it directly and skip the chooser.
     */
    private final ProjectSettingsService settings;

    public ScreenCaptureService() {
        this(null);
    }

    public ScreenCaptureService(ProjectSettingsService settings) {
        this.settings = settings;
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
        grabAsync(owner, shot -> showOverlay(owner, shot, onCaptured));
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
        CaptureTarget target = (settings != null) ? settings.defaultTarget() : null;

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
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("Screen capture unavailable");
        alert.setHeaderText("Couldn't capture the screen");
        alert.setContentText("The capture came back blank. On Linux this almost always means a Wayland "
                + "session — BotMaker needs an X11 (Xorg) session to capture and control the screen. Log out "
                + "and choose the \"Xorg\" / X11 session at the login screen, then try again.");
        alert.showAndWait();
    }

    /**
     * Interactive rubber-band selection returning the chosen region as {@code [x, y, width, height]} in
     * logical desktop coordinates (the coordinate space of the SDK's {@code Rect}). Does nothing if the user
     * cancels (Esc / empty selection) or capture is unavailable.
     */
    public void selectRegion(Window owner, Consumer<int[]> onSelected) {
        grabAsync(owner, shot -> showRegionOverlay(owner, shot, onSelected));
    }

    /**
     * Interactive point pick: an overlay with a magnified close-up that follows the cursor and a live
     * coordinate readout; left-click sets the point. Returns {@code [x, y]} in logical desktop
     * coordinates. Does nothing if the user cancels (Esc) or capture is unavailable.
     */
    public void pickPoint(Window owner, Consumer<int[]> onPicked) {
        grabAsync(owner, shot -> showPointOverlay(owner, shot, onPicked));
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
        GenericWindow win = resolveWindow(target.titleSubstring());
        if (win == null) {
            System.err.println("No window matching \"" + target.titleSubstring() + "\" was found.");
            return null;
        }
        NativeController controller = NativeControllerFactory.get();
        // Raise + focus, then let the compositor settle before grabbing pixels.
        try {
            controller.focusWindow(win);
            Thread.sleep(180);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("Could not focus window: " + t.getMessage());
        }
        // Re-resolve after focus (bounds may change when a minimized window is restored/raised).
        GenericWindow refreshed = resolveWindow(target.titleSubstring());
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

    /** First window (case-insensitive) whose title contains {@code titleSubstring}, or {@code null}. */
    private static GenericWindow resolveWindow(String titleSubstring) {
        if (titleSubstring == null) return null;
        String needle = titleSubstring.toLowerCase();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
                String t = w.getTitle();
                if (t != null && t.toLowerCase().contains(needle)) return w;
            }
        } catch (Throwable t) {
            System.err.println("Window enumeration failed: " + t.getMessage());
        }
        return null;
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

    private void showOverlay(Window owner, ScreenShot shot, Consumer<BufferedImage> onCaptured) {
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
            if (cropped != null) onCaptured.accept(cropped);
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
     * Rubber-band overlay that reports the selected region as logical desktop coordinates. Mirrors
     * {@link #showOverlay} but, instead of cropping the image, maps the selection rectangle (pane-logical
     * pixels) to the screen's logical origin: {@code global = target.min + selection}. Correct at scale 1.0;
     * mixed-DPI layouts may be slightly off (same caveat as the image crop path).
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
            double ox = shot.bounds().getMinX();
            double oy = shot.bounds().getMinY();
            onSelected.accept(new int[]{
                    (int) Math.round(ox + selection.getX()),
                    (int) Math.round(oy + selection.getY()),
                    (int) Math.round(selection.getWidth()),
                    (int) Math.round(selection.getHeight())});
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
     * Point-pick overlay: a zoomed close-up follows the cursor with a crosshair and a live "x, y" readout;
     * left-click reports the point as logical desktop coordinates. Esc cancels.
     */
    private void showPointOverlay(Window owner, ScreenShot shot, Consumer<int[]> onPicked) {
        BufferedImage screenshot = shot.image();
        Image fxImage = toFxImage(screenshot);
        ImageView background = new ImageView(fxImage);
        Pane pane = new Pane(background);

        final double zoom = 8.0;
        final double lensSize = 140;
        ImageView lens = new ImageView(fxImage);
        lens.setManaged(false);
        lens.setFitWidth(lensSize);
        lens.setFitHeight(lensSize);
        lens.setPreserveRatio(false);
        lens.setVisible(false);
        Rectangle lensBorder = new Rectangle(lensSize, lensSize);
        lensBorder.setManaged(false);
        lensBorder.setFill(Color.TRANSPARENT);
        lensBorder.setStroke(Color.web("#2f80ed"));
        lensBorder.setStrokeWidth(2);
        lensBorder.setVisible(false);
        Label readout = new Label();
        readout.setManaged(false);
        readout.setStyle("-fx-background-color: rgba(0,0,0,0.75); -fx-text-fill: white; -fx-padding: 2 6 2 6; -fx-font-family: monospace;");
        readout.setVisible(false);
        pane.getChildren().addAll(lens, lensBorder, readout);

        double ox = shot.bounds().getMinX();
        double oy = shot.bounds().getMinY();

        Stage stage = overlayStage(owner, shot.bounds(), shot.fullScreen(),
                "Move to a spot and click to set the point. Press Esc to cancel.");

        pane.setOnMouseMoved(e -> {
            double sx = screenshot.getWidth() / pane.getWidth();
            double sy = screenshot.getHeight() / pane.getHeight();
            double px = e.getX() * sx, py = e.getY() * sy;
            double viewW = lensSize / zoom, viewH = lensSize / zoom;
            double vx = clamp(px - viewW / 2, 0, screenshot.getWidth() - viewW);
            double vy = clamp(py - viewH / 2, 0, screenshot.getHeight() - viewH);
            lens.setViewport(new javafx.geometry.Rectangle2D(vx, vy, viewW, viewH));
            // Place lens near the cursor without covering it.
            double lx = e.getX() + 16, ly = e.getY() + 16;
            if (lx + lensSize > pane.getWidth()) lx = e.getX() - lensSize - 16;
            if (ly + lensSize > pane.getHeight()) ly = e.getY() - lensSize - 16;
            lens.relocate(lx, ly);
            lensBorder.relocate(lx, ly);
            readout.setText((int) Math.round(ox + e.getX()) + ", " + (int) Math.round(oy + e.getY()));
            readout.relocate(lx, ly + lensSize + 2);
            lens.setVisible(true);
            lensBorder.setVisible(true);
            readout.setVisible(true);
        });
        pane.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            stage.close();
            onPicked.accept(new int[]{(int) Math.round(ox + e.getX()), (int) Math.round(oy + e.getY())});
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
    // Multi-argument capture session (one frame + one overlay for a whole call)
    // =========================================================================

    /** One argument to pick during a {@link #runSession} pass. The label names the block + arg + type. */
    public sealed interface PickStep {
        String label();

        /** A {@code Rect} region → {@code [x, y, w, h]} absolute logical coordinates. */
        record RegionStep(String label, Consumer<int[]> onResult) implements PickStep {}

        /** A {@code Point} → {@code [x, y]} absolute logical coordinates. */
        record PointStep(String label, Consumer<int[]> onResult) implements PickStep {}

        /** An {@code ImageTemplate} → the cropped image (caller names + saves it). */
        record ImageStep(String label, Consumer<BufferedImage> onResult) implements PickStep {}
    }

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

    /** A single overlay reused across a call's pick steps: swaps mouse handlers + header per step. */
    private final class SessionOverlay {
        private final Window owner;
        private final ScreenShot shot;
        private final List<PickStep> steps;
        private final Runnable onDone;
        private final double ox, oy;

        private final Stage stage;
        private final Pane pane;
        private final Rectangle selection = new Rectangle();
        private final ImageView lens;
        private final Rectangle lensBorder = new Rectangle(140, 140);
        private final Label readout = new Label();
        private final Label header = new Label();
        private int index = 0;
        private final double[] origin = new double[2];

        SessionOverlay(Window owner, ScreenShot shot, List<PickStep> steps, Runnable onDone) {
            this.owner = owner;
            this.shot = shot;
            this.steps = steps;
            this.onDone = onDone;
            this.ox = shot.bounds().getMinX();
            this.oy = shot.bounds().getMinY();
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
                readout.setText((int) Math.round(ox + e.getX()) + ", " + (int) Math.round(oy + e.getY()));
                readout.relocate(lx, ly + lensSize + 2);
                lens.setVisible(true); lensBorder.setVisible(true); readout.setVisible(true);
            });
            pane.setOnMouseClicked(e -> {
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getY() < SESSION_HEADER_H) return;
                PickStep step = steps.get(index);
                if (step instanceof PickStep.PointStep ps) {
                    ps.onResult().accept(new int[]{(int) Math.round(ox + e.getX()), (int) Math.round(oy + e.getY())});
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
                    rs.onResult().accept(new int[]{
                            (int) Math.round(ox + selection.getX()),
                            (int) Math.round(oy + selection.getY()),
                            (int) Math.round(selection.getWidth()),
                            (int) Math.round(selection.getHeight())});
                } else if (step instanceof PickStep.ImageStep is) {
                    BufferedImage cropped = crop(shot.image(), pane, selection);
                    if (cropped != null) is.onResult().accept(cropped);
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

    /** Converts a {@link BufferedImage} to a JavaFX {@link Image} via in-memory PNG (no javafx.swing dep). */
    private static Image toFxImage(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to render screenshot", e);
        }
    }
}
