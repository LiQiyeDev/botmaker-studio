package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.Platforms.PlatformStatus;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The full "pick an emulator" dialog: every configured Android instance across all supported products, each
 * shown with its <em>brand</em> (BlueStacks / LDPlayer / MEmu / MuMu / Gameloop), a running dot, and — for a
 * running instance — its installed apps. Selecting an instance row picks the instance; drilling into an app
 * picks {@code (instance, app)} so the caller can point the launch target + capture source at that emulator app
 * (Phase 3 plumbing).
 *
 * <p>Discovery + liveness + app queries all run off the FX thread (registry/config reads, TCP probes, ADB). App
 * lists are {@link #APP_CACHE cached per instance name}, so a stopped instance still shows its last-known apps
 * even though we can't query it while it's down.
 */
public final class EmulatorPickerDialog {

    /** A picked emulator, optionally narrowed to one of its installed apps ({@code appPackage} null = instance only). */
    public record Selection(EmulatorInstance instance, String appPackage) {
        public boolean hasApp() {
            return appPackage != null && !appPackage.isBlank();
        }
    }

    /** Last-known installed apps per instance name — survives a stop so a down instance still lists its apps. */
    private static final Map<String, List<String>> APP_CACHE = new ConcurrentHashMap<>();

    private EmulatorPickerDialog() {}

    /** Shows the picker; resolves to the chosen instance (and optional app), or empty if cancelled. */
    public static Optional<Selection> show(Window owner) {
        Dialog<Selection> dialog = new Dialog<>();
        dialog.setTitle("Choose an emulator");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(8));

        Label status = new Label("Scanning for emulators…");
        status.setPadding(new Insets(8));
        rows.getChildren().add(status);

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(440);
        scroll.setPrefViewportWidth(420);
        dialog.getDialogPane().setContent(scroll);

        // Discover instances off the FX thread (registry + config reads), then build a row per instance.
        new Thread(() -> {
            EmulatorInstanceScanner.Scan scan = new EmulatorInstanceScanner().scan();
            Platform.runLater(() -> {
                rows.getChildren().clear();
                if (scan.instances().isEmpty()) {
                    // No instances — show what each product's discovery actually saw so the user can tell
                    // "not installed" from "installed but nothing running / ADB off".
                    rows.getChildren().add(buildStatusSummary(scan.statuses()));
                    return;
                }
                for (EmulatorInstance instance : scan.instances()) rows.getChildren().add(buildRow(instance, dialog));
            });
        }, "emulator-picker-scan").start();

        dialog.setResultConverter(bt -> bt == ButtonType.CANCEL ? null : dialog.getResult());
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** Thumbnail size for a row's live emulator screencap preview. */
    private static final double THUMB_W = 64;
    private static final double THUMB_H = 40;

    /** One instance row: a clickable header (preview + dot + brand + name) plus a lazily-filled installed-apps list. */
    private static VBox buildRow(EmulatorInstance instance, Dialog<Selection> dialog) {
        ImageView thumb = new ImageView();
        thumb.setPreserveRatio(true);
        thumb.setFitWidth(THUMB_W);
        thumb.setFitHeight(THUMB_H);
        StackPane thumbHolder = new StackPane(thumb);
        thumbHolder.setMinSize(THUMB_W, THUMB_H);
        thumbHolder.setPrefSize(THUMB_W, THUMB_H);
        thumbHolder.setMaxSize(THUMB_W, THUMB_H);
        thumbHolder.setStyle("-fx-background-color: #101216; -fx-background-radius: 4;");

        Circle dot = new Circle(5, Color.web("#9aa0a6")); // neutral until the liveness probe resolves
        Label brand = new Label(brandOf(instance.platformId()));
        brand.getStyleClass().add("emulator-picker-brand");
        Label name = new Label(instance.name());
        name.getStyleClass().add("emulator-picker-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label("checking…");
        state.getStyleClass().add("emulator-picker-state");

        HBox header = new HBox(8, thumbHolder, dot, brand, name, spacer, state);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 8, 6, 8));
        header.getStyleClass().add("emulator-picker-row");
        header.setStyle("-fx-cursor: hand;");
        header.setOnMouseClicked(e -> {
            dialog.setResult(new Selection(instance, null));
            dialog.close();
        });

        VBox apps = new VBox(2);
        apps.setPadding(new Insets(0, 8, 4, 26));

        VBox row = new VBox(2, header, apps);

        // Show any cached apps immediately (so a stopped instance still lists its last scan), then probe liveness
        // and — if up — refresh the apps from the live device and fill in the preview thumbnail.
        renderApps(apps, instance, APP_CACHE.get(cacheKey(instance)), null, dialog);
        probeAndLoad(instance, dot, state, apps, thumb, dialog);
        return row;
    }

    /**
     * Off-FX: TCP-probe the ADB port; if up, connect and list installed apps + grab one screencap for the row
     * preview, caching + rendering the result.
     */
    private static void probeAndLoad(EmulatorInstance instance, Circle dot, Label state, VBox apps,
                                     ImageView thumb, Dialog<Selection> dialog) {
        new Thread(() -> {
            boolean running = isRunning(instance);
            List<String> live = running ? installedApps(instance) : null;
            BufferedImage shot = running ? screencap(instance) : null;
            Image preview = shot != null ? ScreenCaptureService.toFxImage(shot) : null;
            Platform.runLater(() -> {
                dot.setFill(running ? Color.web("#34a853") : Color.web("#9aa0a6"));
                state.setText(running ? "running" : "stopped");
                if (preview != null) thumb.setImage(preview);
                if (running && live != null) APP_CACHE.put(cacheKey(instance), live);
                List<String> show = running ? live : APP_CACHE.get(cacheKey(instance));
                String emptyNote = running
                        ? "No third-party apps found on this instance."
                        : "Instance stopped — start it to list apps, or enter a package below.";
                renderApps(apps, instance, show, emptyNote, dialog);
            });
        }, "emulator-probe-" + instance.name()).start();
    }

    /**
     * Rebuilds the app list under an instance row: a button per discovered package, or {@code emptyNote} when
     * there are none, and always a "＋ Enter app package…" manual-entry fallback so a launch target is
     * achievable even when the live app list is empty (stopped instance, launcher-only app, or ADB blocked).
     * {@code emptyNote} of {@code null} suppresses the note (used for the initial cached render, before probing).
     */
    private static void renderApps(VBox apps, EmulatorInstance instance, List<String> packages, String emptyNote,
                                   Dialog<Selection> dialog) {
        apps.getChildren().clear();
        if (packages != null && !packages.isEmpty()) {
            for (String pkg : packages) {
                Button appButton = new Button(pkg);
                appButton.getStyleClass().add("emulator-picker-app");
                appButton.setMaxWidth(Double.MAX_VALUE);
                appButton.setAlignment(Pos.CENTER_LEFT);
                appButton.setOnAction(e -> {
                    dialog.setResult(new Selection(instance, pkg));
                    dialog.close();
                });
                apps.getChildren().add(appButton);
            }
        } else if (emptyNote != null) {
            Label note = new Label(emptyNote);
            note.getStyleClass().add("emulator-picker-state");
            note.setWrapText(true);
            apps.getChildren().add(note);
        }
        Button manual = new Button("＋ Enter app package…");
        manual.getStyleClass().add("emulator-picker-app");
        manual.setMaxWidth(Double.MAX_VALUE);
        manual.setAlignment(Pos.CENTER_LEFT);
        manual.setOnAction(e -> promptForPackage(instance, dialog));
        apps.getChildren().add(manual);
    }

    /** Prompts for a package name and, if given, resolves the dialog to {@code (instance, package)}. */
    private static void promptForPackage(EmulatorInstance instance, Dialog<Selection> dialog) {
        TextInputDialog input = new TextInputDialog();
        input.setTitle("Enter app package");
        input.setHeaderText("App package to launch on " + instance.name());
        input.setContentText("Package (e.g. com.supercell.clashofclans):");
        if (dialog.getDialogPane().getScene() != null) {
            input.initOwner(dialog.getDialogPane().getScene().getWindow());
        }
        input.showAndWait().ifPresent(pkg -> {
            String trimmed = pkg == null ? "" : pkg.trim();
            if (!trimmed.isBlank()) {
                dialog.setResult(new Selection(instance, trimmed));
                dialog.close();
            }
        });
    }

    /**
     * When no instance was found, a per-product summary so the user can see what discovery detected — "MuMu:
     * installed", "BlueStacks: not installed", "LDPlayer: scan error" — rather than a bare "nothing found".
     */
    private static VBox buildStatusSummary(List<PlatformStatus> statuses) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8));
        Label title = new Label("No emulator instances found.");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);
        for (PlatformStatus s : statuses) {
            box.getChildren().add(new Label("• " + statusLine(s)));
        }
        Label hint = new Label("Start an instance with ADB enabled, then reopen this picker.");
        hint.setWrapText(true);
        hint.getStyleClass().add("emulator-picker-state");
        box.getChildren().add(hint);
        return box;
    }

    /** A one-line detection summary for a product. */
    private static String statusLine(PlatformStatus s) {
        if (!s.ok()) return s.displayName() + ": scan error (" + s.error() + ")";
        if (!s.installed()) return s.displayName() + ": not installed";
        int n = s.instanceCount();
        return s.displayName() + ": installed · " + n + (n == 1 ? " instance" : " instances") + " configured";
    }

    /** App-cache key: the instance's identity (platform + endpoint), so two same-named instances don't collide. */
    private static String cacheKey(EmulatorInstance instance) {
        return instance.platformId() + "@" + instance.endpoint();
    }

    /** A quick TCP liveness probe of the instance's ADB port (mirrors the SDK's {@code EmulatorRef.running}). */
    private static boolean isRunning(EmulatorInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.host(), instance.adbPort()), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Installed third-party apps of a running instance via a short-lived ADB connection; empty on any failure. */
    private static List<String> installedApps(EmulatorInstance instance) {
        AdbDevice device = null;
        try {
            device = AdbDevice.connect(instance.host(), instance.adbPort());
            return device.installedApps();
        } catch (Exception e) {
            return List.of();
        } finally {
            if (device != null) {
                try { device.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
    }

    /** One ADB {@code screencap} of a running instance via a short-lived connection; null on any failure. */
    private static BufferedImage screencap(EmulatorInstance instance) {
        AdbDevice device = null;
        try {
            device = AdbDevice.connect(instance.host(), instance.adbPort());
            return device.screencap();
        } catch (Exception e) {
            return null;
        } finally {
            if (device != null) {
                try { device.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
    }

    /** Human-facing product brand for a {@code platformId} (falls back to the raw id for unknown products). */
    private static String brandOf(String platformId) {
        if (platformId == null) return "Emulator";
        return switch (platformId) {
            case "bluestacks" -> "BlueStacks";
            case "ldplayer" -> "LDPlayer";
            case "memu" -> "MEmu";
            case "mumu" -> "MuMu";
            case "gameloop" -> "Gameloop";
            default -> platformId;
        };
    }
}
