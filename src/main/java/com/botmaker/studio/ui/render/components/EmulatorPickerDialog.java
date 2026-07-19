package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

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
            List<EmulatorInstance> instances = new EmulatorInstanceScanner().instances();
            Platform.runLater(() -> {
                rows.getChildren().clear();
                if (instances.isEmpty()) {
                    rows.getChildren().add(new Label("No emulators found. Install/start BlueStacks, LDPlayer, "
                            + "MEmu, MuMu or Gameloop with ADB enabled."));
                    return;
                }
                for (EmulatorInstance instance : instances) rows.getChildren().add(buildRow(instance, dialog));
            });
        }, "emulator-picker-scan").start();

        dialog.setResultConverter(bt -> bt == ButtonType.CANCEL ? null : dialog.getResult());
        return Optional.ofNullable(dialog.showAndWait().orElse(null));
    }

    /** One instance row: a clickable header (dot + brand + name) plus a lazily-filled installed-apps list. */
    private static VBox buildRow(EmulatorInstance instance, Dialog<Selection> dialog) {
        Circle dot = new Circle(5, Color.web("#9aa0a6")); // neutral until the liveness probe resolves
        Label brand = new Label(brandOf(instance.platformId()));
        brand.getStyleClass().add("emulator-picker-brand");
        Label name = new Label(instance.name());
        name.getStyleClass().add("emulator-picker-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label("checking…");
        state.getStyleClass().add("emulator-picker-state");

        HBox header = new HBox(8, dot, brand, name, spacer, state);
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
        // and — if up — refresh the apps from the live device.
        renderApps(apps, instance, APP_CACHE.get(instance.name()), dialog);
        probeAndLoad(instance, dot, state, apps, dialog);
        return row;
    }

    /** Off-FX: TCP-probe the ADB port; if up, connect and list installed apps, caching + rendering the result. */
    private static void probeAndLoad(EmulatorInstance instance, Circle dot, Label state, VBox apps,
                                     Dialog<Selection> dialog) {
        new Thread(() -> {
            boolean running = isRunning(instance);
            List<String> live = running ? installedApps(instance) : null;
            Platform.runLater(() -> {
                dot.setFill(running ? Color.web("#34a853") : Color.web("#9aa0a6"));
                state.setText(running ? "running" : "stopped");
                if (live != null) {
                    APP_CACHE.put(instance.name(), live);
                    renderApps(apps, instance, live, dialog);
                }
            });
        }, "emulator-probe-" + instance.name()).start();
    }

    /** Rebuilds the app-button list under an instance row (empty {@code packages} clears it). */
    private static void renderApps(VBox apps, EmulatorInstance instance, List<String> packages,
                                   Dialog<Selection> dialog) {
        apps.getChildren().clear();
        if (packages == null || packages.isEmpty()) return;
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
