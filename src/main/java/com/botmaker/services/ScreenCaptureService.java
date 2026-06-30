package com.botmaker.services;

import com.botmaker.project.ProjectPreferences;
import javafx.geometry.Insets;
import javafx.scene.Scene;
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
     * Runs the interactive crop on the FX thread. With multiple monitors the user first picks which
     * screen to capture; the whole desktop is grabbed and then cropped to that screen so the overlay
     * shows it 1:1 (no distortion). Calls {@code onCaptured} with the cropped image, or does nothing if
     * the user cancels (Esc / empty selection / screen chooser) or capture is unavailable.
     */
    public void captureRegion(Window owner, Consumer<BufferedImage> onCaptured) {
        // Grab the whole desktop first so the chooser can show a live preview of each screen.
        BufferedImage desktop;
        try {
            desktop = grabVirtualDesktop();
        } catch (Exception e) {
            System.err.println("Screen capture failed: " + e.getMessage());
            return;
        }
        if (desktop == null) return;
        if (looksBlank(desktop)) {
            System.err.println("Screen capture looks blank — on Linux this usually means a Wayland session; "
                    + "X11 is required for Robot capture.");
        }

        List<Screen> screens = Screen.getScreens();
        Screen target = (screens.size() > 1) ? chooseScreen(owner, screens, desktop) : Screen.getPrimary();
        if (target == null) return; // chooser cancelled

        BufferedImage screenshot = cropToScreen(desktop, screens, target);
        showOverlay(owner, target, screenshot, onCaptured);
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

    /** Writes {@code image} to {@code target} as PNG, creating parent directories. */
    public void savePng(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        ImageIO.write(image, "png", target.toFile());
    }

    /**
     * Captures the whole desktop into a single image. Under Wayland (where {@link Robot} is blocked and
     * returns black) this delegates to an installed screenshot CLI; otherwise it uses {@link Robot} over
     * the union of all screen devices. Returns {@code null} if no Wayland capture tool is available.
     */
    private BufferedImage grabVirtualDesktop() throws Exception {
        if (isWayland()) return grabViaCli();

        java.awt.Rectangle bounds = new java.awt.Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return new Robot().createScreenCapture(bounds);
    }

    /** True if we appear to be running under a Wayland session. */
    private static boolean isWayland() {
        return "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                || System.getenv("WAYLAND_DISPLAY") != null;
    }

    /**
     * Full-screen grab via an external screenshot tool (Wayland path). Tries each known tool in order
     * and returns the first successful capture; returns {@code null} (with a logged hint) if none work.
     */
    private static BufferedImage grabViaCli() throws IOException {
        // Each entry is the argv with a trailing placeholder for the output PNG path.
        String[][] tools = {
                {"grim"},                                // wlroots / Sway / Hyprland
                {"gnome-screenshot", "-f"},              // GNOME
                {"spectacle", "-b", "-n", "-f", "-o"},   // KDE Plasma
        };
        Path tmp = Files.createTempFile("botmaker-shot-", ".png");
        try {
            for (String[] tool : tools) {
                if (!toolExists(tool[0])) continue;
                String[] argv = new String[tool.length + 1];
                System.arraycopy(tool, 0, argv, 0, tool.length);
                argv[tool.length] = tmp.toString();
                if (runQuietly(argv) && Files.size(tmp) > 0) {
                    BufferedImage img = ImageIO.read(tmp.toFile());
                    if (img != null) return img;
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.err.println("No working Wayland screenshot tool found. Install one of: grim "
                + "(wlroots/Sway/Hyprland), gnome-screenshot (GNOME), or spectacle (KDE).");
        return null;
    }

    /** True if {@code name} resolves on PATH (via {@code which}). */
    private static boolean toolExists(String name) {
        try {
            return new ProcessBuilder("which", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs {@code argv}, discarding its output, and returns true if it exits 0 within the timeout. */
    private static boolean runQuietly(String[] argv) {
        try {
            Process p = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True if every sampled pixel is pure black (heuristic for a failed/Wayland capture). */
    private static boolean looksBlank(BufferedImage img) {
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 20);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    private void showOverlay(Window owner, Screen target, BufferedImage screenshot, Consumer<BufferedImage> onCaptured) {
        Image fxImage = toFxImage(screenshot);

        ImageView background = new ImageView(fxImage);
        // Display at the desktop's logical size; the selection is mapped back to image pixels by ratio,
        // which keeps things correct under HiDPI scaling.
        Pane pane = new Pane(background);

        Rectangle selection = new Rectangle();
        selection.setFill(Color.color(0.3, 0.6, 1.0, 0.25));
        selection.setStroke(Color.web("#2f80ed"));
        selection.setStrokeWidth(1.5);
        selection.setVisible(false);
        pane.getChildren().add(selection);

        Stage stage = new Stage(StageStyle.UNDECORATED);
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        // Place the stage on the chosen monitor so fullscreen opens there.
        stage.setX(target.getBounds().getMinX());
        stage.setY(target.getBounds().getMinY());
        stage.setFullScreen(true);
        stage.setFullScreenExitHint("Drag to select a region to capture. Press Esc to cancel.");
        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);

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
