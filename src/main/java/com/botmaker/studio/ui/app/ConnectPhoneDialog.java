package com.botmaker.studio.ui.app;

import com.botmaker.shared.device.ScrcpyServer;
import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.AdbTools;
import com.botmaker.shared.emulator.SavedDevices;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
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
 *       says so and {@link AdbTools#installHint()} says what it would buy.</li>
 *   <li><b>By address</b> — a phone in legacy {@code adb tcpip} mode, dialled directly with no binary
 *       anywhere. This is the route that works on a machine with nothing installed, and it is the one that
 *       persists.</li>
 * </ul>
 *
 * <p>Every probe here — the server query, the reachability check — blocks on a socket, so all of it runs off
 * the FX thread and writes back through {@link Platform#runLater}.
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
            }
            return rows;
        }

        List<AdbTools.ServerDevice> phones = devices.stream().filter(d -> !d.emulator()).toList();
        if (phones.isEmpty()) {
            rows.add(note("An adb server is running but owns no phone. Plug one in and enable USB debugging, "
                    + "or pair it under Developer options ▸ Wireless debugging."));
            return rows;
        }
        for (AdbTools.ServerDevice phone : phones) {
            rows.add(serverRow(phone));
        }
        rows.add(note("These are found automatically — they need no address and are not saved."));
        return rows;
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
     * an optional install, a phone that sleeps stops producing frames, and a locked phone captures its lock
     * screen — which reads as "my templates stopped matching" rather than as a locked phone.
     */
    private static VBox notes() {
        VBox box = new VBox(4);
        Label heading = new Label("Worth knowing");
        heading.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(heading);
        box.getChildren().add(note(ScrcpyServer.available()
                ? "scrcpy was found, so capture uses the continuous video stream."
                : ScrcpyServer.installHint()));
        box.getChildren().add(note("Keep the screen awake — Developer options ▸ \"Stay awake while charging\". "
                + "A sleeping phone produces no frames."));
        box.getChildren().add(note("Unlock the phone before running a bot. A locked one captures its lock "
                + "screen, and every template quietly stops matching."));
        return box;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("emulator-picker-state");
        label.setWrapText(true);
        label.setMaxWidth(480);
        return label;
    }
}
