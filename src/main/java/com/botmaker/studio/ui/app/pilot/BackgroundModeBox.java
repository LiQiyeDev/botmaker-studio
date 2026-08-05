package com.botmaker.studio.ui.app.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.studio.emulator.EmulatorProbe;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.pilot.NestedSessionLauncher;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The "Background mode" controls — the pilot's <b>recommended</b> input path: launch the project's configured
 * game into a bot-owned nested display ({@code :N}) and route the pilot through it (flawless background input),
 * versus mirroring the real {@code :0} desktop (where Interact moves your real cursor).
 *
 * <p>Carries one persistent, colour-coded status line — green "Isolated on :N — &lt;game&gt; attached" vs amber
 * "Mirroring your real desktop :0" — so the user can always tell which path is live. The producer is the
 * {@link NestedSessionLauncher} it is handed, which {@link RemotePilotUi} keeps across dialog reopens so Stop
 * and the status still reflect a session started earlier.
 */
final class BackgroundModeBox {

    private BackgroundModeBox() {
    }

    static Node create(NestedSessionLauncher launcher, ProjectSettingsService settings) {
        VBox box = new VBox(6);
        Label title = new Label("Background mode — run the game in a private display (recommended)");
        title.setStyle("-fx-font-weight: bold;");
        Label help = PilotWidgets.wrapped("Launches the configured game into a private nested display the bot "
                + "alone drives, so the pilot previews and controls that window while your real cursor stays "
                + "free. The launched window is the target — no capture source to pick. Otherwise the pilot "
                + "mirrors your real desktop and Interact moves your actual cursor.");

        ChoiceBox<NestedSession.Backend> backend = new ChoiceBox<>();
        backend.getItems().addAll(NestedSession.Backend.XEPHYR, NestedSession.Backend.GAMESCOPE);
        // Preselect the backend the configured target actually needs (gamescope for a game, Xephyr otherwise),
        // single-sourced through SessionBackends so the pilot and the Launch buttons can't disagree.
        backend.setValue(SessionBackends.preferredBackend(launcher.configuredTarget()));
        backend.setTooltip(new Tooltip(
                "Xephyr: 2D targets. gamescope: hardware-3D (Proton/DXVK/Vulkan) — needs a GPU box."));

        Button start = new Button("Start background mode");
        Button stop = new Button("Stop");
        Button showWin = new Button("Show display window");
        showWin.setTooltip(new Tooltip(
                "Raise the Xephyr host window on your desktop so you can watch the private display."));
        Label status = PilotWidgets.wrapped("");

        // Button/visibility state only — never touches the status text, so a transient start/failure message set
        // by the async callback isn't clobbered when we re-enable the buttons.
        Runnable refreshButtons = () -> {
            boolean running = launcher.isRunning();
            boolean backendOk = SessionBackends.isAvailable(backend.getValue());
            LaunchSpec configured = launcher.configuredTarget();
            // An emulator app is already off the desktop, so there is nothing for a private display to add and
            // NestedSessionLauncher would refuse anyway — don't offer a button whose only outcome is a refusal.
            boolean offDesktop = configured != null && configured.kind().runsOffDesktop();
            boolean canStart = configured != null && backendOk && !offDesktop;
            start.setDisable(running || !canStart);
            stop.setDisable(!running);
            backend.setDisable(running);
            boolean xephyr = backend.getValue() == NestedSession.Backend.XEPHYR;
            showWin.setVisible(xephyr);
            showWin.setManaged(xephyr);
            showWin.setDisable(!running);
        };
        // The persistent resting status line: green when isolated, amber when mirroring / unavailable.
        Runnable refreshStatus = () -> {
            if (launcher.isRunning()) {
                String disp = launcher.activeDisplay();
                String game = launcher.attachedTitle();
                status.setText("● Isolated on " + disp + (game != null ? " — " + game + " attached" : " — attached")
                        + ". Interact drives it; your real cursor stays free.");
                status.setStyle("-fx-text-fill: #27ae60;"); // green — the good, isolated state
                return;
            }
            LaunchSpec spec = launcher.configuredTarget();
            if (spec != null && spec.kind().runsOffDesktop()) {
                emulatorIsolationStatus(spec, status);
                return;
            }
            if (spec == null) {
                status.setText("● Set a launch target (Run ▸ Launch Target…) to enable background mode.");
            } else if (!SessionBackends.isAvailable(backend.getValue())) {
                status.setText("● To use this backend for background mode, "
                        + SessionBackends.installHint(backend.getValue()) + ".");
            } else {
                status.setText("● Mirroring your real desktop :0 — Interact moves your real cursor. Start "
                        + "background mode to run " + spec.describe() + " isolated.");
            }
            status.setStyle("-fx-text-fill: #e67e22;"); // amber — cursor-moving / not-yet-isolated
        };
        backend.setOnAction(e -> { refreshButtons.run(); refreshStatus.run(); });
        refreshButtons.run();
        refreshStatus.run();

        start.setOnAction(e -> {
            int[] size = referenceSize(settings);
            start.setDisable(true);
            launcher.start(backend.getValue(), size[0], size[1], (ok, msg) -> {
                if (!ok) {
                    // Loud failure (e.g. a host launcher stole the game onto :0) — show it, stay amber.
                    status.setText("● " + msg);
                    status.setStyle("-fx-text-fill: #e67e22;");
                } else if (launcher.isRunning()) {
                    refreshStatus.run(); // terminal success → green "Isolated on :N"
                } else {
                    status.setText(msg); // interim "Bringing up…"
                    status.setStyle("-fx-text-fill: #7f8c8d;");
                }
                refreshButtons.run();
            });
        });
        stop.setOnAction(e -> {
            launcher.stop();
            refreshButtons.run();
            refreshStatus.run();
        });
        showWin.setOnAction(e -> raiseXephyrHostWindow(launcher.activeDisplay(), status));

        box.getChildren().addAll(title, help,
                new HBox(8, new Label("Backend:"), backend, start, stop, showWin), status);
        return box;
    }

    /**
     * The status line for a target that already runs off the desktop — an emulator app.
     *
     * <p>The box used to tell such a target it was "mirroring your real desktop :0" and offer it a private
     * display, which was wrong twice over: the pilot doesn't mirror the desktop for these (it streams the
     * emulator over ADB, {@code PilotRoutes}), and a nested display has nothing to give a surface that was
     * never on a display of ours. The isolated state is the <em>resting</em> state here, so this is green.
     *
     * <p>Whether the emulator is actually up is a TCP probe, so it runs off the FX thread and only upgrades
     * the line when it comes back — the box never blocks on it.
     */
    private static void emulatorIsolationStatus(LaunchSpec spec, Label status) {
        String instance = spec.emulatorInstance();
        String app = spec.emulatorPackage();
        status.setText("● Already isolated — " + app + " runs inside " + instance
                + ". The pilot streams the emulator over ADB and Interact taps land inside it; your real "
                + "cursor stays free.");
        status.setStyle("-fx-text-fill: #27ae60;"); // green — this target is isolated by construction
        Thread probe = new Thread(() -> {
            boolean up = EmulatorInstances.byName(instance).map(EmulatorProbe::isRunning).orElse(false);
            if (up) return;
            Platform.runLater(() -> {
                status.setText("● " + instance + " isn't running — start it with ▶ Launch now (or the emulator "
                        + "picker). The pilot streams it over ADB as soon as it's up; no background mode needed.");
                status.setStyle("-fx-text-fill: #e67e22;");
            });
        }, "emulator-liveness");
        probe.setDaemon(true);
        probe.start();
    }

    /**
     * Raise the Xephyr host window (it lives on the real {@code :0} desktop, titled "Xephyr on :N …") so the user
     * can watch the private display directly. gamescope has no such host window — there the pilot preview is the
     * only view, which is why this affordance is Xephyr-only. Best-effort: reports into {@code status} if the
     * window can't be found.
     */
    private static void raiseXephyrHostWindow(String display, Label status) {
        GenericWindow host = null;
        for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
            String t = w.getTitle();
            if (t != null && t.toLowerCase().contains("xephyr") && (display == null || t.contains(display))) {
                host = w;
                break;
            }
        }
        if (host != null) {
            NativeControllerFactory.get().focusWindow(host);
        } else {
            status.setText("● Couldn't find the Xephyr host window to raise — it may have been closed.");
            status.setStyle("-fx-text-fill: #e67e22;");
        }
    }

    /**
     * The project's reference resolution (what image templates were authored at) as {@code [width, height]},
     * or the launcher's default when unset — the nested display is sized to match so captures line up with the
     * templates.
     */
    private static int[] referenceSize(ProjectSettingsService settings) {
        try {
            var res = settings.current().referenceResolution();
            if (res != null && res.width() > 0 && res.height() > 0) {
                return new int[]{res.width(), res.height()};
            }
        } catch (Exception ignored) {
            // fall through to the launcher default
        }
        return new int[]{NestedSessionLauncher.DEFAULT_WIDTH, NestedSessionLauncher.DEFAULT_HEIGHT};
    }
}
