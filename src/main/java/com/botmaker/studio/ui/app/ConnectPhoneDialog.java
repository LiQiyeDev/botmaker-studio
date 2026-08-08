package com.botmaker.studio.ui.app;

import com.botmaker.shared.device.ScrcpyServer;
import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.AdbTools;
import com.botmaker.shared.emulator.SavedDevices;
import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.ManagedTools;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <b>Connect a phone…</b> — the surface for the one Android target that cannot be discovered by reading a
 * product's config file, because there is no product: a phone the user owns.
 *
 * <p>It is a <em>connection</em> dialog, not a picker. Nothing is selected here and nothing is returned;
 * what it does is make a phone <b>discoverable</b>, after which it appears in every existing picker as an
 * ordinary {@code EmulatorInstance} — see {@code DevicePlatform} for why a phone is a discovery path rather
 * than a new concept. That is also why it writes {@link SavedDevices}, which lives in shared: a phone added
 * here has to be resolvable by a generated bot at run time too, not just by the editor that added it.
 *
 * <p>The two routes are shown separately because they need different things from the user:
 *
 * <ul>
 *   <li><b>Cable, or Android 11+ wireless debugging</b> — needs a host adb server, which is the one part of
 *       this stack that is not pure dadb. Nothing here <em>requires</em> it; when it is missing the section
 *       offers to download it rather than sending the user off to install it.</li>
 *   <li><b>By address</b> — a phone in legacy {@code adb tcpip} mode, dialled directly with no binary
 *       anywhere. This is the route that works on a machine with nothing installed, and it is the one that
 *       persists.</li>
 * </ul>
 *
 * <p>Every probe here — the server query, the reachability check, the scrcpy lookup — blocks on a socket or a
 * subprocess, so all of it runs off the FX thread and writes back through {@link Platform#runLater}. The two
 * downloads do the same, on their own named threads.
 *
 * <p><b>Nothing downloads on its own.</b> Both tools are behind a button the user presses, because
 * platform-tools carries Google's Android SDK Terms of Use and opening a dialog is not consent to accept them.
 * The one exception lives elsewhere and is deliberately not here: a bot with no dialog to show fetches
 * {@code scrcpy-server} itself (see {@code ScrcpyServer.ensure()}).
 */
public final class ConnectPhoneDialog {

    private ConnectPhoneDialog() {}

    private static final Color ONLINE = Color.web("#34a853");
    private static final Color TROUBLE = Color.web("#f9ab00");
    private static final Color OFFLINE = Color.web("#9aa0a6");

    /** Shows the dialog and blocks until it is closed. Nothing is returned: the result is the saved list. */
    public static void show(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.setTitle("Connect a phone");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox content = new VBox(14);
        content.setPadding(new Insets(12));

        VBox cable = section("Plugged in, or wireless debugging");
        VBox byAddress = section("By address");
        content.getChildren().addAll(cable, byAddress, notes());

        refreshServer(cable);
        refreshSaved(byAddress);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);
        scroll.setPrefViewportWidth(520);
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }

    /** A titled block whose body is rebuilt in place by the refreshers; children[1..] are the body. */
    private static VBox section(String title) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, heading);
        return box;
    }

    private static void setBody(VBox section, List<? extends Region> rows) {
        section.getChildren().retainAll(section.getChildren().get(0));
        section.getChildren().addAll(rows);
    }

    // --- the adb server half ---

    /**
     * Queries the adb server off the FX thread and rebuilds the section: its devices, or — when there is no
     * server — what starting one would need.
     */
    private static void refreshServer(VBox section) {
        setBody(section, List.of(new Label("Checking for an adb server…")));
        new Thread(() -> {
            boolean running = AdbTools.serverRunning();
            List<AdbTools.ServerDevice> devices = running ? AdbTools.devices() : List.of();
            boolean binary = AdbTools.binary().isPresent();
            Platform.runLater(() -> setBody(section, serverRows(section, running, devices, binary)));
        }, "connect-phone-adb-server").start();
    }

    private static List<Region> serverRows(VBox section, boolean running,
                                           List<AdbTools.ServerDevice> devices, boolean binary) {
        List<Region> rows = new java.util.ArrayList<>();
        if (!running) {
            rows.add(note(binary
                    ? "No adb server is running. Starting one lets BotMaker see a phone on a USB cable."
                    : "No adb server is running, and no `adb` binary was found — " + AdbTools.installHint()));
            if (binary) {
                Button start = new Button("Start adb server");
                // Deliberately the only thing here that starts a daemon. Enumeration stays a read (see
                // AdbTools.devices), so a background process is never a side effect of opening a dialog.
                start.setOnAction(e -> {
                    start.setDisable(true);
                    new Thread(() -> {
                        AdbTools.startServer();
                        Platform.runLater(() -> refreshServer(section));
                    }, "connect-phone-start-server").start();
                });
                rows.add(new HBox(start));
                rows.add(pairBox(section));
            } else if (ManagedTools.platformTools() != null) {
                // The pin is per-OS and Google publishes three; a fourth OS gets the sentence and no button,
                // rather than a button whose only possible outcome is a failure message.
                rows.add(downloadRow("Download adb (" + size(ManagedTools.platformTools()) + ")",
                        "connect-phone-download-adb",
                        ManagedTools::installPlatformTools,
                        () -> refreshServer(section)));
            }
            return rows;
        }

        List<AdbTools.ServerDevice> phones = devices.stream().filter(d -> !d.emulator()).toList();
        if (phones.isEmpty()) {
            rows.add(note("An adb server is running but owns no phone. Plug one in and enable USB debugging, "
                    + "or pair it below."));
        } else {
            for (AdbTools.ServerDevice phone : phones) {
                rows.add(serverRow(phone));
            }
            rows.add(note("These are found automatically — they need no address and are not saved."));
        }
        rows.add(pairBox(section));
        return rows;
    }

    // --- wireless pairing, the route that never needs a cable ---

    /**
     * <b>Android 11+ wireless debugging</b>, in the two steps the phone itself presents as two steps.
     *
     * <p>They are separate fields rather than one because <b>the two ports are different</b>: the
     * <i>Pair device with pairing code</i> popup shows a short-lived pairing port alongside the six digits,
     * while the wireless-debugging screen behind it shows the debugging port that every later session uses.
     * Sending the pairing port to {@code adb connect} fails every time, so a single field would be a field that
     * cannot work.
     *
     * <p>Pairing is done once per phone and survives reboots; after it, a phone that has moved to a new Wi-Fi
     * address needs only the connect half. Neither writes anything: a connected phone appears in the list above
     * as a device the server owns, for the same reason those rows are not addable.
     */
    private static VBox pairBox(VBox section) {
        Label problem = new Label();
        problem.getStyleClass().add("emulator-picker-state");
        problem.setWrapText(true);
        problem.setMaxWidth(480);

        TextField pairAddress = new TextField();
        pairAddress.setPromptText("192.168.1.5:37123");
        pairAddress.setPrefColumnCount(15);
        TextField code = new TextField();
        code.setPromptText("123456");
        code.setPrefColumnCount(7);
        Button pair = new Button("Pair");

        TextField connectAddress = new TextField();
        connectAddress.setPromptText("192.168.1.5:5555");
        connectAddress.setPrefColumnCount(15);
        Button connect = new Button("Connect");

        // Pairing deliberately does not refresh the list: it connects nothing by itself, so the only result to
        // show is what adb said — and a refresh would rebuild this box and take that sentence away.
        Runnable doPair = () -> adbCommand(null, problem, List.of(pair, connect),
                () -> AdbTools.pair(pairAddress.getText(), code.getText()), "connect-phone-pair");
        Runnable doConnect = () -> adbCommand(section, problem, List.of(pair, connect),
                () -> AdbTools.connect(connectAddress.getText()), "connect-phone-connect");
        pair.setOnAction(e -> doPair.run());
        code.setOnAction(e -> doPair.run());
        pairAddress.setOnAction(e -> doPair.run());
        connect.setOnAction(e -> doConnect.run());
        connectAddress.setOnAction(e -> doConnect.run());

        HBox pairRow = new HBox(6, new Label("Pair"), pairAddress, code, pair);
        pairRow.setAlignment(Pos.CENTER_LEFT);
        HBox connectRow = new HBox(6, new Label("Connect"), connectAddress, connect);
        connectRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6,
                note("Wireless, no cable: on the phone, Developer options ▸ Wireless debugging ▸ Pair device "
                        + "with pairing code. Pair with the address and code in that popup, then connect to the "
                        + "address on the screen behind it — the ports differ."),
                pairRow, connectRow, problem);
        box.setPadding(new Insets(4, 6, 4, 6));
        return box;
    }

    /**
     * Runs one adb command off the FX thread and shows <b>adb's own words</b>, not a summary of them — "Failed:
     * Wrong password or connection was dropped" tells a user to re-read the code off their phone; "pairing
     * failed" tells them nothing they could act on.
     */
    private static void adbCommand(VBox section, Label problem, List<Button> buttons,
                                   Supplier<AdbTools.Outcome> command, String threadName) {
        buttons.forEach(b -> b.setDisable(true));
        problem.setText("Working…");
        new Thread(() -> {
            AdbTools.Outcome outcome = command.get();
            Platform.runLater(() -> {
                buttons.forEach(b -> b.setDisable(false));
                problem.setText(outcome.message());
                if (outcome.ok() && section != null) {
                    // A connected phone is one the server now owns, so the list above is what has to change —
                    // and re-running the query is how it learns.
                    refreshServer(section);
                }
            });
        }, threadName).start();
    }

    /**
     * One device the server owns. It is deliberately not addable: the server discovers it every time, so a
     * saved copy would be a second row for the same phone that goes stale the moment it is unplugged.
     *
     * <p>An {@code unauthorized} row is the one worth reading carefully — it means the phone's "Allow USB
     * debugging?" prompt has not been accepted, which is the single most common reason a plugged-in phone
     * does nothing, and it is invisible unless something says it out loud.
     */
    private static HBox serverRow(AdbTools.ServerDevice phone) {
        Circle dot = new Circle(5, phone.online() ? ONLINE : TROUBLE);
        Label name = new Label(phone.displayName());
        name.setStyle("-fx-font-weight: bold;");
        Label where = new Label(phone.usb() ? "USB" : "network");
        where.getStyleClass().add("emulator-picker-state");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label("unauthorized".equals(phone.state())
                ? "unauthorized — accept the prompt on the phone"
                : phone.state());
        state.getStyleClass().add("emulator-picker-state");
        HBox row = new HBox(8, dot, name, where, spacer, state);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        row.getStyleClass().add("emulator-picker-row");
        Tooltip.install(row, new Tooltip(phone.serial()));
        return row;
    }

    // --- the saved-address half ---

    private static void refreshSaved(VBox section) {
        List<Region> rows = new java.util.ArrayList<>();
        List<SavedDevices.SavedDevice> saved = SavedDevices.load();
        if (saved.isEmpty()) {
            rows.add(note("No addresses saved. On the phone: Developer options ▸ enable USB debugging, then "
                    + "`adb tcpip 5555` once over a cable — after that it answers on its Wi-Fi address with no "
                    + "cable and no adb binary on this machine."));
        }
        for (SavedDevices.SavedDevice device : saved) {
            rows.add(savedRow(section, device));
        }
        rows.add(addRow(section));
        setBody(section, rows);
    }

    /** One saved phone: a liveness dot filled in off-thread, its name, its address, and Forget. */
    private static HBox savedRow(VBox section, SavedDevices.SavedDevice device) {
        Circle dot = new Circle(5, OFFLINE);
        Label name = new Label(device.displayName());
        name.setStyle("-fx-font-weight: bold;");
        Label address = new Label(device.endpoint().label());
        address.getStyleClass().add("emulator-picker-state");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label("checking…");
        state.getStyleClass().add("emulator-picker-state");

        Button forget = new Button("Forget");
        forget.getStyleClass().add("emulator-picker-state");
        forget.setOnAction(e -> {
            SavedDevices.remove(device.endpoint());
            refreshSaved(section);
        });

        new Thread(() -> {
            boolean reachable = device.endpoint().reachable();
            Platform.runLater(() -> {
                dot.setFill(reachable ? ONLINE : OFFLINE);
                // "not answering" rather than "offline": nothing here can tell a phone that is switched off
                // from one that has left tcpip mode, or from a Wi-Fi address that has since been reassigned.
                state.setText(reachable ? "answering" : "not answering");
            });
        }, "connect-phone-probe").start();

        HBox row = new HBox(8, dot, name, address, spacer, state, forget);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        row.getStyleClass().add("emulator-picker-row");
        return row;
    }

    /** The add form: an address, an optional name, and the one button that writes the file. */
    private static HBox addRow(VBox section) {
        TextField address = new TextField();
        address.setPromptText("192.168.1.5:5555");
        address.setPrefColumnCount(16);
        TextField name = new TextField();
        name.setPromptText("Name (optional)");
        name.setPrefColumnCount(12);

        Label problem = new Label();
        problem.getStyleClass().add("emulator-picker-state");

        Button add = new Button("Add");
        Runnable submit = () -> {
            String typed = address.getText() == null ? "" : address.getText().trim();
            // A bare address gets the conventional port rather than a complaint — `adb tcpip 5555` is the
            // command the section above tells the user to run, so 5555 is what they were just told to use.
            String withPort = typed.contains(":") ? typed : typed + ":5555";
            SavedDevices.SavedDevice device = SavedDevices.parseAddress(withPort, name.getText());
            if (device == null) {
                problem.setText("Not a host:port address.");
                return;
            }
            SavedDevices.add(device);
            refreshSaved(section);
        };
        add.setOnAction(e -> submit.run());
        address.setOnAction(e -> submit.run());
        name.setOnAction(e -> submit.run());

        HBox row = new HBox(6, address, name, add, problem);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        return row;
    }

    // --- what a user needs to know regardless of route ---

    /**
     * The three things that are true of every phone target and are invisible until they bite: the fast path is
     * one optional file, a phone that sleeps stops producing frames, and a locked phone captures its lock
     * screen — which reads as "my templates stopped matching" rather than as a locked phone.
     */
    private static VBox notes() {
        VBox box = new VBox(4);
        Label heading = new Label("Worth knowing");
        heading.setStyle("-fx-font-weight: bold;");
        VBox scrcpy = new VBox(6);
        box.getChildren().addAll(heading, scrcpy);
        refreshScrcpy(scrcpy);
        // Since Phase 2 the fast path sets stay_awake itself, so the phone setting is now only about the ADB
        // floor — worth saying, because "keep the screen awake" reads as a chore the app could have done.
        box.getChildren().add(note("Keep the screen awake — Developer options ▸ \"Stay awake while charging\". "
                + "A sleeping phone produces no frames. The scrcpy fast path does this for you for the length "
                + "of a session and wakes the screen on connect; plain ADB does neither."));
        box.getChildren().add(note("Unlock the phone before running a bot. A locked one captures its lock "
                + "screen, and every template quietly stops matching."));
        return box;
    }

    /**
     * The scrcpy line, filled in off the FX thread — locating a server can shell out to a {@code scrcpy} on
     * {@code PATH} to ask its version, which is a subprocess, not a field read.
     */
    private static void refreshScrcpy(VBox slot) {
        slot.getChildren().setAll(note("Checking for scrcpy…"));
        new Thread(() -> {
            boolean present = ScrcpyServer.available();
            Platform.runLater(() -> {
                if (present) {
                    slot.getChildren().setAll(note("The scrcpy server was found, so capture uses the "
                            + "continuous video stream and injects input directly."));
                    return;
                }
                slot.getChildren().setAll(
                        note(ScrcpyServer.installHint()),
                        downloadRow("Download scrcpy-server (" + size(ManagedTools.SCRCPY_SERVER) + ")",
                                "connect-phone-download-scrcpy",
                                ManagedTools::installScrcpyServer,
                                () -> refreshScrcpy(slot)));
            });
        }, "connect-phone-scrcpy").start();
    }

    // --- the two downloads ---

    /**
     * A button that fetches one pinned tool, with the progress bar beside it and the failure in words.
     *
     * <p>{@code install} runs on its own thread and returns whether the tool is there afterwards; {@code done}
     * runs on the FX thread on success and is what re-asks the question the button was answering. Failure is
     * one sentence rather than a dialog: nothing is broken by it — every route that worked before the click
     * still works, which is the whole reason this stack treats a missing tool as an ordinary state.
     */
    private static HBox downloadRow(String label, String threadName,
                                    Function<Downloads.Progress, Boolean> install, Runnable done) {
        Button button = new Button(label);
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(160);
        bar.setVisible(false);
        Label problem = new Label();
        problem.getStyleClass().add("emulator-picker-state");
        problem.setWrapText(true);

        button.setOnAction(e -> {
            button.setDisable(true);
            bar.setVisible(true);
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            problem.setText("");
            new Thread(() -> {
                // total is -1 when the server sent no Content-Length; the bar then stays indeterminate rather
                // than inventing a fraction.
                boolean ok = install.apply((bytes, total) -> {
                    double fraction = total > 0 ? (double) bytes / total : ProgressBar.INDETERMINATE_PROGRESS;
                    Platform.runLater(() -> bar.setProgress(fraction));
                });
                Platform.runLater(() -> {
                    bar.setVisible(false);
                    button.setDisable(false);
                    if (ok) {
                        done.run();
                    } else {
                        problem.setText("The download did not complete — check the connection and try again. "
                                + "Nothing was installed.");
                    }
                });
            }, threadName).start();
        });

        HBox row = new HBox(8, button, bar, problem);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        return row;
    }

    /** A pin's size as the user will see it charged to their connection, or nothing when there is no pin. */
    private static String size(Downloads.Remote remote) {
        if (remote == null || remote.size() <= 0) {
            return "unknown size";
        }
        return String.format(Locale.ROOT, "%.1f MB", remote.size() / 1_000_000d);
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("emulator-picker-state");
        label.setWrapText(true);
        label.setMaxWidth(480);
        return label;
    }
}
