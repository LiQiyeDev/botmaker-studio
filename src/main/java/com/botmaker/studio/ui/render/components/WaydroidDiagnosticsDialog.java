package com.botmaker.studio.ui.render.components;

import com.botmaker.shared.emulator.WaydroidDiagnostics;
import com.botmaker.shared.emulator.WaydroidDiagnostics.Finding;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;

/**
 * What is wrong with the local Waydroid setup, and the commands that fix it — <b>to copy, not to run</b>.
 *
 * <p>The probes are shared's {@link WaydroidDiagnostics}; this class is only their presentation. It keeps the
 * "never execute" rule of that class visible in the UI: each finding shows its commands in a read-only text
 * area with a <em>Copy</em> button and no Run button, because every one of them needs {@code sudo} and reaches
 * outside anything BotMaker owns (the host firewall, the Android system image). The user reads them, decides,
 * and runs them in their own terminal.
 *
 * <p>Reached two ways, per the same principle as the rest of the picker — <em>offered</em>, never forced: the
 * "Diagnose…" button on the Waydroid row, and automatically when Waydroid is installed but discovery came back
 * with nothing, which is precisely the moment the user has a symptom and no explanation.
 */
public final class WaydroidDiagnosticsDialog {

    private WaydroidDiagnosticsDialog() {}

    /** Runs the probes off the FX thread and shows the results. Returns as soon as the dialog is dismissed. */
    public static void show(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.setTitle("Waydroid diagnostics");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);

        VBox body = new VBox(10);
        body.setPadding(new Insets(12));
        body.getChildren().add(new Label("Checking Waydroid…"));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(560);
        scroll.setPrefViewportHeight(420);
        dialog.getDialogPane().setContent(scroll);

        // The probes shell out (systemctl, waydroid status, ip route) — never on the FX thread.
        new Thread(() -> {
            List<Finding> findings = WaydroidDiagnostics.run();
            Platform.runLater(() -> {
                body.getChildren().clear();
                if (findings.isEmpty()) {
                    body.getChildren().add(allClear());
                    return;
                }
                for (Finding finding : findings) {
                    body.getChildren().add(card(finding));
                }
            });
        }, "waydroid-diagnostics").start();

        dialog.showAndWait();
    }

    /**
     * The empty result, worded as a conclusion rather than a shrug: ruling the setup out is the answer that
     * makes running the check worthwhile, and "no problems found" alone reads as "the check did nothing".
     */
    private static VBox allClear() {
        Label title = new Label("No problems found.");
        title.setStyle("-fx-font-weight: bold;");
        Label detail = new Label("The container service, session, host networking, ARM translation layer and "
                + "display size all look right. If a bot still can't reach Waydroid, the cause is somewhere "
                + "else — start the session and check that the app is actually running.");
        detail.setWrapText(true);
        return new VBox(6, title, detail);
    }

    /** One finding: symptom, remedy, the commands (read-only) and a Copy button. Never a Run button. */
    private static VBox card(Finding finding) {
        Label symptom = new Label(finding.symptom());
        symptom.setWrapText(true);
        symptom.setStyle("-fx-font-weight: bold;");

        Label remedy = new Label(finding.remedy());
        remedy.setWrapText(true);

        VBox card = new VBox(6, symptom, remedy);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color: -fx-control-inner-background-alt; -fx-background-radius: 6;");

        if (!finding.commands().isEmpty()) {
            TextArea commands = new TextArea(finding.commandBlock());
            commands.setEditable(false);
            commands.setWrapText(false);
            commands.setPrefRowCount(Math.min(finding.commands().size(), 12));
            commands.setStyle("-fx-font-family: monospace;");

            Button copy = new Button("Copy commands");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(finding.commandBlock());
                Clipboard.getSystemClipboard().setContent(content);
                copy.setText("Copied");
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox actions = new HBox(8, spacer, copy);
            actions.setAlignment(Pos.CENTER_RIGHT);

            if (finding.docUrl() != null) {
                Hyperlink docs = new Hyperlink("Upstream docs");
                docs.setOnAction(e -> browse(finding.docUrl()));
                actions.getChildren().add(0, docs);
            }
            card.getChildren().addAll(commands, actions);
        }
        return card;
    }

    /** Opens a URL in the user's browser; silently does nothing where that isn't supported. */
    private static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // A diagnostics panel that throws while trying to open a help page is worse than one that doesn't.
        }
    }
}
