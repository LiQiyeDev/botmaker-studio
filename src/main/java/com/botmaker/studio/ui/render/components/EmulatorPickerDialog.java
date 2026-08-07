package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms.PlatformStatus;
import com.botmaker.studio.emulator.EmulatorAppCache;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import com.botmaker.studio.emulator.EmulatorProbe;
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
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
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
 *
 * <p><b>A stopped instance is startable from its own row.</b> Every discovered instance carries the host
 * console command that brings it up ({@link EmulatorInstance#launchCommand()}), and the row already knew it was
 * down — it just had nothing to offer but the words "start it to list apps". Now it offers Start / Stop, and
 * because {@link EmulatorLauncher} is fire-and-forget by contract (a {@code true} means "dispatched", not
 * "up"), establishing readiness is this dialog's job: {@link #waitFor} polls the ADB port to a bounded ceiling
 * and the row re-probes itself when it settles, so the apps appear without reopening the picker.
 */
public final class EmulatorPickerDialog {

    /** A picked emulator, optionally narrowed to one of its installed apps ({@code appPackage} null = instance only). */
    public record Selection(EmulatorInstance instance, String appPackage) {
        public boolean hasApp() {
            return appPackage != null && !appPackage.isBlank();
        }
    }

    /**
     * Last-known installed apps per instance — survives a stop so a down instance still lists its apps, and
     * (through {@link #DISK}) survives a Studio restart, which this map alone did not.
     */
    private static final Map<String, List<EmulatorProbe.InstalledApp>> APP_CACHE = new ConcurrentHashMap<>();

    /** The on-disk half of both caches; the maps above are only the in-process layer over it. */
    private static final EmulatorAppCache DISK = EmulatorAppCache.shared();

    /** The remembered app list for {@code instance}: the in-process map, falling back to disk. */
    private static List<EmulatorProbe.InstalledApp> cachedApps(EmulatorInstance instance) {
        return APP_CACHE.computeIfAbsent(instance.identity(), key -> DISK.packages(instance));
    }

    /** Records a live app-list query in both layers. */
    private static void cacheApps(EmulatorInstance instance, List<EmulatorProbe.InstalledApp> apps) {
        APP_CACHE.put(instance.identity(), apps);
        DISK.putPackages(instance, apps);
    }

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
                    rows.getChildren().add(buildStatusSummary(scan.statuses(), dialog));
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

    /** How often {@link #waitFor} re-probes while an instance is coming up or going down. */
    private static final long POLL_INTERVAL_MS = 1_500;

    /** Stopping only has to tear a process down, so it never wants the launch ceiling. */
    private static final long STOP_TIMEOUT_MS = 60_000;

    /** The mutable widgets of one instance row, so the probe and the start/stop poll can refresh them in place. */
    private record RowUi(Circle dot, Label state, Button action, ImageView thumb, VBox apps) {}

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
        thumbHolder.getStyleClass().add("emulator-thumb-holder");

        Circle dot = new Circle(5, Color.web("#9aa0a6")); // neutral until the liveness probe resolves
        Label brand = new Label(instance.brand());
        brand.getStyleClass().add("emulator-picker-brand");
        Label name = new Label(instance.name());
        name.getStyleClass().add("emulator-picker-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label("checking…");
        state.getStyleClass().add("emulator-picker-state");

        // Start / Stop. Hidden until the liveness probe says which of the two this row is offering — and left
        // hidden for an instance whose product ships no console tool we can drive. Like Diagnose below, it has
        // to swallow its own click or starting an instance would also select it and close the dialog.
        Button action = new Button();
        action.getStyleClass().add("emulator-picker-state");
        action.setVisible(false);
        action.setManaged(false);
        action.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);

        HBox header = new HBox(8, thumbHolder, dot, brand, name, spacer, state, action);
        if (instance.platformId() == PlatformId.WAYDROID) {
            // Waydroid is the one platform whose common failures are host configuration rather than "it isn't
            // started" — no NAT, no ARM translation layer. Offer the explanation from the row itself; the
            // button consumes the click so it doesn't also pick the instance.
            Button diagnose = new Button("Diagnose…");
            diagnose.getStyleClass().add("emulator-picker-state");
            diagnose.setOnAction(e -> WaydroidDiagnosticsDialog.show(windowOf(dialog)));
            // The row itself is the "pick this instance" click target, so the button has to swallow the click
            // that reaches it — otherwise asking why Waydroid is broken would also select it and close.
            diagnose.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
            header.getChildren().add(diagnose);
        }
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
        renderApps(apps, instance, cachedApps(instance), null, dialog);
        probeAndLoad(instance, new RowUi(dot, state, action, thumb, apps), dialog);
        return row;
    }

    /**
     * Off-FX: TCP-probe the ADB port; if up, connect and list installed apps + grab one screencap for the row
     * preview, caching + rendering the result. Also the row's way back to the truth after a start or a stop —
     * {@link #transition} re-runs it once the poll settles rather than assuming what it asked for happened.
     */
    private static void probeAndLoad(EmulatorInstance instance, RowUi ui, Dialog<Selection> dialog) {
        new Thread(() -> {
            boolean running = EmulatorProbe.isRunning(instance);
            List<EmulatorProbe.InstalledApp> live = running ? EmulatorProbe.installedAppsDetailed(instance) : null;
            BufferedImage shot = running ? EmulatorProbe.screencap(instance) : null;
            Image preview = shot != null ? ScreenCaptureService.toFxImage(shot) : null;
            Platform.runLater(() -> {
                ui.dot().setFill(running ? Color.web("#34a853") : Color.web("#9aa0a6"));
                ui.state().setText(running ? "running" : "stopped");
                if (preview != null) ui.thumb().setImage(preview);
                if (running && live != null) cacheApps(instance, live);
                List<EmulatorProbe.InstalledApp> show = running && live != null ? live : cachedApps(instance);
                renderApps(ui.apps(), instance, show, emptyNote(running, live), dialog);
                showAction(instance, ui, dialog, running);
            });
        }, "emulator-probe-" + instance.name()).start();
    }

    /**
     * What to say when a row lists no apps — three different situations that used to share one sentence.
     *
     * <p>The one worth naming is the middle case. Android's {@code adbd} prompts the user to trust a new host
     * key, and a <em>refused</em> prompt is invisible from here: the ADB port still accepts a connection, so
     * the instance reads as running while every query fails. Reporting that as "no third-party apps found"
     * blames the device for the user's own dismissed dialog, and leaves the one action that fixes it unsaid.
     */
    private static String emptyNote(boolean running, List<EmulatorProbe.InstalledApp> live) {
        if (!running) return "Instance stopped — start it to list apps, or enter a package below.";
        if (live == null) {
            return "The instance is up but ADB wouldn't answer — usually the \"Allow USB debugging?\" prompt "
                    + "inside Android was declined or is still waiting. Accept it there, then reopen this picker.";
        }
        return "No third-party apps found on this instance.";
    }

    /**
     * Puts Start (on a stopped row) or Stop (on a running one) in place, and hides the control entirely when
     * the product has no command for that direction — {@link EmulatorInstance#canLaunch()} /
     * {@link EmulatorInstance#canStop()} are false when discovery couldn't locate the console tool, and a
     * button that can only ever fail is worse than no button.
     */
    private static void showAction(EmulatorInstance instance, RowUi ui, Dialog<Selection> dialog, boolean running) {
        boolean available = running ? instance.canStop() : instance.canLaunch();
        ui.action().setVisible(available);
        ui.action().setManaged(available);
        ui.action().setDisable(false);
        if (!available) return;
        ui.action().setText(running ? "Stop" : "Start");
        ui.action().setOnAction(e -> transition(instance, ui, dialog, !running));
    }

    /**
     * Dispatches the host start/stop command and waits for the ADB port to agree, then re-probes the row.
     *
     * <p>The waiting is the point. {@link EmulatorLauncher} returns as soon as the console tool is spawned, so
     * without the poll the row would flip back to "stopped" a fraction of a second later and the user would
     * conclude the button did nothing. The poll expiring is a real outcome too, and for Waydroid it is the
     * exact moment {@link WaydroidDiagnosticsDialog} was written for: the command dispatched fine and the
     * container still never appeared, which is host configuration the diagnostics can name.
     */
    private static void transition(EmulatorInstance instance, RowUi ui, Dialog<Selection> dialog, boolean start) {
        ui.action().setDisable(true);
        ui.state().setText(start ? "starting…" : "stopping…");
        ui.dot().setFill(Color.web("#fbbc04"));
        long timeout = start ? instance.platformId().bootTimeout().toMillis() : STOP_TIMEOUT_MS;
        new Thread(() -> {
            boolean dispatched = start ? EmulatorLauncher.launch(instance) : EmulatorLauncher.stop(instance);
            boolean settled = dispatched && waitFor(instance, start, timeout);
            Platform.runLater(() -> {
                if (!dispatched) {
                    // Nothing was spawned, so there is nothing to re-probe — leave the row as it was.
                    ui.state().setText(start ? "couldn't start" : "couldn't stop");
                    ui.action().setDisable(false);
                    ui.dot().setFill(Color.web("#ea4335"));
                    return;
                }
                if (!settled && start && instance.platformId() == PlatformId.WAYDROID) {
                    WaydroidDiagnosticsDialog.show(windowOf(dialog));
                }
                probeAndLoad(instance, ui, dialog);
            });
        }, "emulator-" + (start ? "start-" : "stop-") + instance.name()).start();
    }

    /**
     * Polls until the instance reports {@code wantRunning}, or the ceiling elapses. Off-FX (each probe blocks),
     * and it stops early on an interrupt so a closed dialog doesn't leave a thread counting out four minutes.
     *
     * <p><b>Starting waits for readiness, stopping only for the port.</b> They are different questions:
     * a started instance is only useful once Android has booted far enough to answer {@code pm list packages}
     * ({@link EmulatorReadiness#isReady}) — polling the port alone is what made a freshly-started row come
     * back with an empty app list — while a stopped one is gone the moment nothing is listening.
     */
    private static boolean waitFor(EmulatorInstance instance, boolean wantRunning, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (settled(instance, wantRunning)) return true;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return settled(instance, wantRunning);
    }

    private static boolean settled(EmulatorInstance instance, boolean wantRunning) {
        return wantRunning ? EmulatorReadiness.isReady(instance) : !EmulatorProbe.isRunning(instance);
    }

    /**
     * Rebuilds the app list under an instance row: a button per discovered package, or {@code emptyNote} when
     * there are none, and always a "＋ Enter app package…" manual-entry fallback so a launch target is
     * achievable even when the live app list is empty (stopped instance, launcher-only app, or ADB blocked).
     * {@code emptyNote} of {@code null} suppresses the note (used for the initial cached render, before probing).
     */
    private static void renderApps(VBox apps, EmulatorInstance instance, List<EmulatorProbe.InstalledApp> packages,
                                   String emptyNote, Dialog<Selection> dialog) {
        apps.getChildren().clear();
        if (packages != null && !packages.isEmpty()) {
            for (EmulatorProbe.InstalledApp app : packages) {
                // The app's own name where the platform gave us one — `com.HolydayStudios.Firestone` is not
                // what the user calls it. The package stays in the tooltip: it is what the target stores.
                Button appButton = new Button(app.display());
                appButton.getStyleClass().add("emulator-picker-app");
                appButton.setMaxWidth(Double.MAX_VALUE);
                appButton.setAlignment(Pos.CENTER_LEFT);
                appButton.setGraphic(iconView());
                appButton.setTooltip(new Tooltip(app.packageName()));
                appButton.setOnAction(e -> {
                    dialog.setResult(new Selection(instance, app.packageName()));
                    dialog.close();
                });
                apps.getChildren().add(appButton);
            }
            loadIcons(instance, packages.stream().map(EmulatorProbe.InstalledApp::packageName).toList(), apps);
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

    /** Edge of a launcher-icon thumbnail, sized to sit inside a list row without changing its height. */
    private static final double ICON_SIZE = 24;

    /**
     * Launcher icons already read, keyed {@code <instance identity>/<package>}. An {@link Optional#empty()}
     * is a remembered <em>failure</em> — an app whose APK carries no icon we can decode must not be re-fetched
     * on every re-render, and there are three of those per row (initial, post-probe, post-start).
     *
     * <p>The successes are also written through to {@link #DISK}; the failures deliberately are not. A
     * permanent negative on disk would outlive the reason for it (an app updated, an ADB query that happened
     * to fail), and re-deriving one costs a single query.
     */
    private static final Map<String, Optional<Image>> ICON_CACHE = new ConcurrentHashMap<>();

    /** A fixed-size holder so a row's text lines up whether or not its icon ever arrives. */
    private static ImageView iconView() {
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setFitWidth(ICON_SIZE);
        view.setFitHeight(ICON_SIZE);
        return view;
    }

    /**
     * Fills in each listed app's icon, off the FX thread, in list order.
     *
     * <p>A package name is a reverse-DNS string and frequently says nothing about the game it belongs to; the
     * icon is what makes the list readable. One thread walks the packages sequentially rather than one thread
     * per app: each icon is several ADB round-trips (see {@code ApkIcon}) and a row with twenty apps would
     * otherwise open twenty connections to the same device at once.
     *
     * <p>It writes back by <em>position</em>, and re-checks that the button under that index is still the one
     * it fetched for — a start/stop or a re-probe can rebuild the list underneath a fetch in flight.
     */
    private static void loadIcons(EmulatorInstance instance, List<String> packages, VBox apps) {
        new Thread(() -> {
            for (int i = 0; i < packages.size(); i++) {
                String pkg = packages.get(i);
                String key = instance.identity() + "/" + pkg;
                Optional<Image> cached = ICON_CACHE.get(key);
                if (cached == null) {
                    // Disk first: an icon is several ADB round-trips through the APK, and it doesn't change.
                    BufferedImage stored = DISK.icon(instance, pkg);
                    if (stored == null) {
                        stored = EmulatorProbe.appIcon(instance, pkg);
                        DISK.putIcon(instance, pkg, stored);
                    }
                    cached = Optional.ofNullable(ScreenCaptureService.toFxImage(stored));
                    ICON_CACHE.put(key, cached);
                }
                if (cached.isEmpty()) continue;
                Image icon = cached.get();
                int index = i;
                Platform.runLater(() -> {
                    if (index >= apps.getChildren().size()) return;
                    // Identified by the tooltip, not the label: the label is now the app's display name, and
                    // two apps can legitimately share one. The package is the identity.
                    if (apps.getChildren().get(index) instanceof Button button
                            && button.getTooltip() != null && pkg.equals(button.getTooltip().getText())
                            && button.getGraphic() instanceof ImageView view) {
                        view.setImage(icon);
                    }
                });
            }
        }, "emulator-icons-" + instance.name()).start();
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
    private static VBox buildStatusSummary(List<PlatformStatus> statuses, Dialog<Selection> dialog) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8));
        Label title = new Label("No emulator instances found.");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);
        for (PlatformStatus s : statuses) {
            box.getChildren().add(new Label("• " + s.statusLine()));
        }
        Label hint = new Label("Start an instance with ADB enabled, then reopen this picker.");
        hint.setWrapText(true);
        hint.getStyleClass().add("emulator-picker-state");
        box.getChildren().add(hint);
        // Waydroid installed but nothing discovered is the exact moment the user has a symptom and no
        // explanation — the second of the two ways the diagnostics are reached (the other is the row button).
        boolean waydroidInstalled = statuses.stream()
                .anyMatch(s -> s.platformId() == PlatformId.WAYDROID && s.installed());
        if (waydroidInstalled) {
            Button diagnose = new Button("Diagnose Waydroid…");
            diagnose.setOnAction(e -> WaydroidDiagnosticsDialog.show(windowOf(dialog)));
            box.getChildren().add(diagnose);
        }
        return box;
    }

    /** The dialog's window, for parenting a child dialog — null before it is shown. */
    private static Window windowOf(Dialog<Selection> dialog) {
        return dialog.getDialogPane().getScene() == null ? null : dialog.getDialogPane().getScene().getWindow();
    }
}
