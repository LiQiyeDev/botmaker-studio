package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.services.platform.SessionEnvironment;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One-time modal shown on a Wayland session telling the user BotMaker needs X11 (Xorg) and offering to
 * install the distro's X11 session packages. Switching to Xorg can't be done live — the user must log out
 * and pick the "Xorg" / X11 session at the login screen — so this only installs packages and guides; it
 * never claims X11 is enabled. A "don't show again" checkbox suppresses it on future launches.
 */
public final class ForceX11Notice {

    private ForceX11Notice() {}

    /** Shows the notice once if we're on Wayland and the user hasn't suppressed it. */
    public static void maybeShow(Window owner) {
        if (!SessionEnvironment.isWayland()) return;
        if (ProjectPreferences.isWaylandNoticeHidden()) return;
        show(owner);
    }

    private static void show(Window owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("Switch to X11 for full support");
        alert.setHeaderText("BotMaker needs an X11 (Xorg) session");

        Label body = new Label("""
                You're running a Wayland session. BotMaker captures and controls the screen with tools that \
                Wayland blocks, so the live preview and image capture will come back black or fail.

                To fix this, log out and choose the "Xorg" / "X11" session at the login screen — this can't \
                be switched while you're logged in.

                Some desktops (e.g. Fedora KDE) don't install the X11 session by default. Use the button below \
                to install the packages, then log out and pick the Xorg session.""");
        body.setWrapText(true);
        body.setMaxWidth(480);

        CheckBox dontShow = new CheckBox("Don't show this again");
        Label cmdLabel = new Label();
        cmdLabel.setWrapText(true);
        cmdLabel.setMaxWidth(480);
        cmdLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: #8b93a1;");
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setPrefRowCount(8);
        output.setVisible(false);
        output.setManaged(false);

        VBox content = new VBox(12, body, cmdLabel, dontShow, output);
        content.setPadding(new Insets(4));
        alert.getDialogPane().setContent(content);

        ButtonType install = new ButtonType("Install X11 session packages", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(install, close);

        // Keep the dialog open when Install is pressed (run the install in place, streaming output).
        Button installBtn = (Button) alert.getDialogPane().lookupButton(install);
        installBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume();
            runInstall(cmdLabel, output, installBtn);
        });

        alert.setOnHidden(e -> {
            if (dontShow.isSelected()) ProjectPreferences.setWaylandNoticeHidden(true);
        });
        alert.setResizable(true);
        alert.show(); // non-blocking so startup isn't held up
    }

    private static void runInstall(Label cmdLabel, TextArea output, Button installBtn) {
        output.setVisible(true);
        output.setManaged(true);
        List<String> cmd = SessionEnvironment.x11InstallCommand();
        if (cmd == null) {
            cmdLabel.setText("Couldn't detect your package manager — install your desktop's Xorg session manually:");
            output.setText("""
                    Fedora KDE:     sudo dnf install plasma-workspace-x11
                    Fedora GNOME:   sudo dnf install gnome-session-xsession
                    Debian/Ubuntu:  sudo apt install xorg <your-desktop>-session
                    Arch:           sudo pacman -S xorg-server""");
            return;
        }
        cmdLabel.setText("Running: " + String.join(" ", cmd));
        output.clear();
        installBtn.setDisable(true);

        Thread t = new Thread(() -> {
            int exit = -1;
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        Platform.runLater(() -> output.appendText(l + "\n"));
                    }
                }
                exit = p.waitFor();
            } catch (Exception ex) {
                String msg = ex.getMessage();
                Platform.runLater(() -> output.appendText("Failed to run install: " + msg + "\n"));
            }
            int code = exit;
            Platform.runLater(() -> {
                output.appendText(code == 0
                        ? "\n✔ Packages installed. Now log out and choose the \"Xorg\" / X11 session at login.\n"
                        : "\n✖ Install exited with code " + code
                                + ". You can run the command shown above manually in a terminal.\n");
                installBtn.setDisable(false);
            });
        }, "x11-install");
        t.setDaemon(true);
        t.start();
    }
}
