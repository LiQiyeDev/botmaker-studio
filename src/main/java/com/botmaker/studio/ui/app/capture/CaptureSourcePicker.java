package com.botmaker.studio.ui.app.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import com.botmaker.studio.project.capture.CaptureRegion;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.capture.DesktopGrab;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.EmulatorTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A Steam-library-style visual chooser for a capture source: two categories — <b>Screens</b> and
 * <b>Windows</b> — each rendered as a tile with a live thumbnail and a name, plus an optional
 * <b>Project default</b> tile. It is the single reusable picker behind both the toolbar's Capture Targets
 * button (choosing the project default) and the in-block {@code CaptureSource} selection.
 *
 * <p>Thumbnails are grabbed off the FX thread and best-effort: windows via the shared native controller
 * (no focus, no OS prompt); monitors by cropping a full-desktop grab. Returns the user's {@link Selection}
 * or {@link Optional#empty()} on cancel.
 */
public final class CaptureSourcePicker {

    /** What the user chose: either "track the project default", or a concrete, frozen {@link CaptureTarget}. */
    public sealed interface Selection permits Selection.ProjectDefault, Selection.Concrete {
        record ProjectDefault() implements Selection {}

        /**
         * A concrete source, optionally narrowed to a {@link CaptureRegion} of it (a rect in the source's own
         * pixel space). {@code region} is {@code null} for the whole source.
         */
        record Concrete(CaptureTarget target, CaptureRegion region) implements Selection {
            public Concrete(CaptureTarget target) {
                this(target, null);
            }
        }
    }

    private static final double TILE_W = 220;
    private static final double THUMB_H = 124;

    private final Window owner;
    private final boolean includeProjectDefault;

    private Selection selected;
    private VBox selectedTile;
    private Stage stage;
    private ScheduledExecutorService thumbExec;

    public CaptureSourcePicker(Window owner, boolean includeProjectDefault) {
        this.owner = owner;
        this.includeProjectDefault = includeProjectDefault;
    }

    /** Shows the picker modally and returns the chosen source, or empty if cancelled. */
    public Optional<Selection> showAndWait() {
        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Choose capture source");

        FlowPane windows = category();
        FlowPane monitors = category();
        FlowPane desktop = category();
        FlowPane emulators = category();

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));
        if (includeProjectDefault) {
            content.getChildren().add(projectDefaultTile());
        }
        // Desktop and monitors first: they are the common picks and were previously buried below the long
        // (100+) window list, forcing a scroll to the bottom to reach them.
        content.getChildren().addAll(
                sectionLabel("Desktop"), desktop,
                sectionLabel("Monitors"), monitors,
                sectionLabel("Emulators"), emulators,
                sectionLabel("Windows"), windows);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        // Optional region: a rectangle WITHIN the chosen source (its own pixel coords; 0,0 = source top-left),
        // emitted as CaptureSource.<source>.region(new Rect(x,y,w,h)). Left blank = the whole source.
        TextField rx = regionField("x");
        TextField ry = regionField("y");
        TextField rw = regionField("w");
        TextField rh = regionField("h");
        Label regionLabel = new Label("Region of source (optional):");
        regionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        HBox regionRow = new HBox(6, regionLabel, rx, ry, rw, rh);
        regionRow.setAlignment(Pos.CENTER_LEFT);

        Button refresh = new Button("↻ Refresh");
        refresh.setOnAction(e -> {
            windows.getChildren().clear();
            loadWindows(windows);
            emulators.getChildren().clear();
            loadEmulators(emulators);
        });
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> { selected = null; close(); });
        Button ok = new Button("Select");
        ok.setDefaultButton(true);
        ok.setOnAction(e -> { applyRegion(rx, ry, rw, rh); close(); });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, refresh, spacer, regionRow, cancel, ok);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 12, 14));

        VBox rootBox = new VBox(scroll, bar);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        loadWindows(windows);
        loadScreens(monitors);
        loadDesktop(desktop);
        loadEmulators(emulators);

        stage.setScene(new Scene(rootBox, 760, 560));
        stage.setOnHidden(e -> stopThumbs());
        stage.showAndWait();
        return Optional.ofNullable(selected);
    }

    private void close() {
        stopThumbs();
        if (stage != null) stage.close();
    }

    /** A narrow numeric field for one region coordinate (prompt = x/y/w/h). */
    private static TextField regionField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setPrefColumnCount(3);
        f.setStyle("-fx-font-size: 11px;");
        return f;
    }

    /**
     * If a concrete source is selected and all four fields parse to a positive-area rectangle, narrow the
     * selection to that {@link CaptureRegion} (a rect in the source's own pixel space). Blank/partial/invalid
     * input leaves the selection as the whole source. Region on "Project default" is not supported.
     */
    private void applyRegion(TextField rx, TextField ry, TextField rw, TextField rh) {
        if (!(selected instanceof Selection.Concrete c)) return;
        Integer x = parseInt(rx.getText()), y = parseInt(ry.getText());
        Integer w = parseInt(rw.getText()), h = parseInt(rh.getText());
        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) return;
        selected = new Selection.Concrete(c.target(), new CaptureRegion(x, y, w, h));
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 6 0 0 2;");
        return l;
    }

    private static FlowPane category() {
        FlowPane pane = new FlowPane(12, 12);
        pane.setPadding(new Insets(2));
        return pane;
    }

    // --- Tiles ---

    private VBox projectDefaultTile() {
        VBox tile = tile("Project default", "Tracks the project's default capture target");
        select(tile, new Selection.ProjectDefault()); // preselect
        tile.setOnMouseClicked(e -> {
            select(tile, new Selection.ProjectDefault());
            if (e.getClickCount() == 2) close();
        });
        return tile;
    }

    private void loadScreens(FlowPane into) {
        List<Screen> screens = Screen.getScreens();
        java.util.List<VBox> tiles = new java.util.ArrayList<>();
        for (int i = 0; i < screens.size(); i++) {
            int index = i;
            Screen s = screens.get(i);
            javafx.geometry.Rectangle2D b = s.getBounds();
            String name = String.format("Screen %d — %d×%d", i + 1,
                    (int) b.getWidth(), (int) b.getHeight());
            VBox tile = tile(name, s.equals(Screen.getPrimary()) ? "Primary monitor" : "Monitor");
            CaptureTarget target = new ScreenTarget(index);
            tile.setOnMouseClicked(e -> {
                select(tile, new Selection.Concrete(target));
                if (e.getClickCount() == 2) close();
            });
            into.getChildren().add(tile);
            tiles.add(tile);
        }
        // Thumbnails: on Wayland grab the whole desktop ONCE (prompt-free CLI) and crop per monitor — using
        // an AWT Robot per tile would re-trigger the portal share picker for every monitor. On X11 the
        // per-monitor Robot grab is fine and needs no external tool.
        thumbs().submit(() -> {
            java.awt.GraphicsDevice[] devices =
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            BufferedImage desktop = DesktopGrab.isWayland() ? DesktopGrab.grabVirtualDesktop() : null;
            for (int i = 0; i < tiles.size() && i < devices.length; i++) {
                java.awt.Rectangle bounds = devices[i].getDefaultConfiguration().getBounds();
                BufferedImage shot = (desktop != null)
                        ? DesktopGrab.cropToBounds(desktop, bounds)
                        : captureMonitorRobot(bounds);
                Image img = toFxImage(shot);
                VBox tile = tiles.get(i);
                if (img != null) Platform.runLater(() -> setThumb(tile, img));
            }
        });
    }

    /** A single "Whole desktop" tile (all monitors combined) → {@link DesktopTarget}, with a live thumbnail. */
    private void loadDesktop(FlowPane into) {
        VBox tile = tile("Whole desktop", "All monitors combined");
        CaptureTarget target = new DesktopTarget();
        tile.setOnMouseClicked(e -> {
            select(tile, new Selection.Concrete(target));
            if (e.getClickCount() == 2) close();
        });
        // Whole desktop is the default preselection when nothing else claimed it: the project-default tile
        // (when shown) already preselected, so this only fires in the "add source" flow — desktop over screen 1.
        if (selected == null) select(tile, new Selection.Concrete(target));
        into.getChildren().add(tile);
        thumbs().submit(() -> {
            Image img = toFxImage(DesktopGrab.grabVirtualDesktop());
            if (img != null) Platform.runLater(() -> setThumb(tile, img));
        });
    }

    /**
     * One tile per configured Android emulator instance (across every product), each with a live ADB
     * {@code screencap} thumbnail when the instance is running. Discovery + liveness probe + screencap all run
     * off the FX thread; a stopped instance still gets a (placeholder) tile so it can be selected before boot.
     */
    private void loadEmulators(FlowPane into) {
        thumbs().submit(() -> {
            List<EmulatorInstance> instances;
            try {
                instances = new EmulatorInstanceScanner().instances();
            } catch (Throwable t) {
                instances = List.of();
            }
            if (instances.isEmpty()) {
                Platform.runLater(() -> into.getChildren().add(emptyEmulatorsHint()));
                return;
            }
            for (EmulatorInstance instance : instances) {
                String name = instance.name();
                boolean running = isEmulatorRunning(instance);
                Image img = running ? toFxImage(emulatorScreencap(instance)) : null;
                Platform.runLater(() -> {
                    VBox tile = tile(name, running ? "Emulator · running" : "Emulator · stopped");
                    CaptureTarget target = new EmulatorTarget(name);
                    tile.setOnMouseClicked(e -> {
                        select(tile, new Selection.Concrete(target));
                        if (e.getClickCount() == 2) close();
                    });
                    if (img != null) setThumb(tile, img);
                    into.getChildren().add(tile);
                });
            }
        });
    }

    /** Shown when no emulators are configured/installed. */
    private static Node emptyEmulatorsHint() {
        Label l = new Label("No emulators found. Install/start BlueStacks, LDPlayer, MEmu, MuMu or Gameloop "
                + "with ADB enabled, then press ↻ Refresh.");
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: gray; -fx-font-size: 11px; -fx-padding: 6 2 2 2;");
        return l;
    }

    /** A quick TCP liveness probe of the instance's ADB port (mirrors {@code EmulatorPickerDialog.isRunning}). */
    private static boolean isEmulatorRunning(EmulatorInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.host(), instance.adbPort()), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One ADB {@code screencap} of a running instance via a short-lived connection; null on any failure. */
    private static BufferedImage emulatorScreencap(EmulatorInstance instance) {
        AdbDevice device = null;
        try {
            device = AdbDevice.connect(instance.host(), instance.adbPort());
            return device.screencap();
        } catch (Throwable t) {
            return null;
        } finally {
            if (device != null) {
                try { device.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
    }

    private void loadWindows(FlowPane into) {
        thumbs().submit(() -> {
            List<GenericWindow> wins;
            try {
                wins = NativeControllerFactory.get().getAllWindows();
            } catch (Throwable t) {
                wins = List.of();
            }
            long named = wins.stream().filter(w -> w.getTitle() != null && !w.getTitle().isBlank()).count();
            if (named == 0) {
                Platform.runLater(() -> into.getChildren().add(emptyWindowsHint()));
                return;
            }
            for (GenericWindow w : wins) {
                String title = w.getTitle();
                if (title == null || title.isBlank()) continue;
                BufferedImage shot;
                try {
                    shot = NativeControllerFactory.get().captureWindow(w);
                } catch (Throwable t) {
                    shot = null;
                }
                Image img = toFxImage(shot);
                Platform.runLater(() -> {
                    VBox tile = tile(title, "Window");
                    CaptureTarget target = new WindowTarget(title);
                    tile.setOnMouseClicked(e -> {
                        select(tile, new Selection.Concrete(target));
                        if (e.getClickCount() == 2) close();
                    });
                    if (img != null) setThumb(tile, img);
                    into.getChildren().add(tile);
                });
            }
        });
    }

    /**
     * Shown when no titled windows are enumerable. On GNOME/Wayland the X11 client list only sees XWayland
     * (X11) apps — Wayland-native windows are invisible to us — so the grid can be legitimately empty.
     */
    private static Node emptyWindowsHint() {
        boolean wayland = System.getenv("WAYLAND_DISPLAY") != null;
        Label l = new Label(wayland
                ? "No windows detected. On Wayland only X11/XWayland apps (e.g. many games via Proton) are\n"
                        + "listed; native Wayland windows can't be enumerated. Use a Screen or the project default."
                : "No windows detected. Open the app you want to capture, then press ↻ Refresh.");
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: gray; -fx-font-size: 11px; -fx-padding: 6 2 2 2;");
        return l;
    }

    private VBox tile(String name, String subtitle) {
        StackPane thumbHolder = new StackPane();
        thumbHolder.setMinSize(TILE_W, THUMB_H);
        thumbHolder.setPrefSize(TILE_W, THUMB_H);
        thumbHolder.setMaxSize(TILE_W, THUMB_H);
        thumbHolder.setStyle("-fx-background-color: #101216; -fx-background-radius: 6;");
        Label loading = new Label("…");
        loading.setStyle("-fx-text-fill: #6b7280;");
        thumbHolder.getChildren().add(loading);

        Label nameLabel = new Label(name);
        nameLabel.setMaxWidth(TILE_W);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        Label subLabel = new Label(subtitle);
        subLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        VBox tile = new VBox(4, thumbHolder, nameLabel, subLabel);
        tile.setPadding(new Insets(6));
        tile.setMaxWidth(TILE_W + 12);
        tile.getStyleClass().add("capture-tile");
        tile.setStyle(tileStyle(false));
        return tile;
    }

    private void setThumb(VBox tile, Image img) {
        if (tile.getChildren().isEmpty() || !(tile.getChildren().get(0) instanceof StackPane holder)) return;
        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(TILE_W);
        iv.setFitHeight(THUMB_H);
        holder.getChildren().setAll(iv);
    }

    private void select(VBox tile, Selection sel) {
        if (selectedTile != null) selectedTile.setStyle(tileStyle(false));
        selectedTile = tile;
        selected = sel;
        tile.setStyle(tileStyle(true));
    }

    private static String tileStyle(boolean sel) {
        String border = sel ? "#3498db" : "transparent";
        return "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 2; -fx-cursor: hand;"
                + " -fx-border-color: " + border + ";"
                + " -fx-background-color: " + (sel ? "rgba(52,152,219,0.10)" : "transparent") + ";";
    }

    // --- Capture helpers (best-effort) ---

    /** Per-monitor grab via AWT {@link Robot} (X11/Windows only — on Wayland this would prompt per call). */
    private static BufferedImage captureMonitorRobot(Rectangle bounds) {
        try {
            return new Robot().createScreenCapture(bounds);
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized ScheduledExecutorService thumbs() {
        if (thumbExec == null || thumbExec.isShutdown()) {
            thumbExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "capture-picker-thumbs");
                t.setDaemon(true);
                return t;
            });
        }
        return thumbExec;
    }

    private synchronized void stopThumbs() {
        if (thumbExec != null) {
            thumbExec.shutdownNow();
            thumbExec = null;
        }
    }

    private static Image toFxImage(BufferedImage image) {
        if (image == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return new Image(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }
}
