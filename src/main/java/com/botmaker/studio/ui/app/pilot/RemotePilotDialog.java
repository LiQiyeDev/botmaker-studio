package com.botmaker.studio.ui.app.pilot;

import com.botmaker.studio.ui.app.pilot.RemotePilotUi.PilotMode;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi.PilotOutcome;
import com.botmaker.studio.ui.util.QrCodes;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The pairing dialog: what a finished {@link PilotOutcome} looks like on screen — how to reach the pilot from
 * the phone, the two QR codes, the token controls, and (when Funnel was asked for and didn't come up) the
 * {@link FunnelSetupWizard}.
 *
 * <p>Pure rendering: it never starts, stops or probes anything itself. Everything that would change state goes
 * back through {@link Actions}.
 */
final class RemotePilotDialog {

    /** Stable "latest release" permalink the install-app QR points at; the botmaker-pilot CI attaches this. */
    private static final String APK_URL =
            "https://github.com/LiQiyeDev/botmaker-pilot/releases/latest/download/botpilot.apk";

    /** Where a user without Tailscale installs it. */
    static final String TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download";

    /** On-screen QR edge in px. The bitmap is encoded at exactly this size and shown 1:1 (no resample) so the
     *  modules stay crisp — a fractional downscale blurs the edges enough to defeat phone-camera decoding. */
    private static final int QR_PX = 240;

    /**
     * What the dialog can ask {@link RemotePilotUi} to do.
     *
     * @param resetToken revokes the pairing token and returns the outcome to re-render, or {@code null} if there
     *                   is no server to revoke it on
     * @param enableFunnel the opt-in "expose publicly over HTTPS" bring-up
     * @param backgroundMode builds the private-display controls (built lazily — it starts a launcher)
     */
    record Actions(UnaryOperator<PilotOutcome> resetToken, Runnable enableFunnel, Supplier<Node> backgroundMode) {}

    private RemotePilotDialog() {
    }

    static void show(Stage owner, PilotOutcome outcome, Actions actions) {
        PilotMode mode = outcome.mode();
        String url = outcome.url();
        String funnelError = outcome.funnelError();
        boolean funnelLive = mode == PilotMode.FUNNEL_HTTPS;

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("Remote Pilot");
        alert.setHeaderText(switch (mode) {
            case FUNNEL_HTTPS -> "Remote Pilot is live over HTTPS — your phone needs nothing installed.";
            case TAILNET_DIRECT -> "Remote Pilot is running on your tailnet.";
            case ALL_INTERFACES -> "Tailscale not detected — bound to ALL interfaces.";
        });

        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 4;");

        // The user explicitly tried "expose publicly" (Advanced) and Funnel couldn't be enabled → lead with
        // the guided, re-checkable setup wizard rather than a dead-end error line. The default (VPN) open never
        // sets funnelError, so this only appears after the Advanced action.
        if (!funnelLive && funnelError != null) {
            content.getChildren().addAll(
                    FunnelSetupWizard.create(outcome.diag(), funnelError,
                            () -> { alert.close(); actions.enableFunnel().run(); }),
                    new Separator());
            content.getChildren().add(PilotWidgets.wrapped(
                    "Meanwhile you can connect right now over Tailscale with the link below — the phone just "
                    + "needs Tailscale signed in to the same account."));
        } else if (funnelLive) {
            content.getChildren().add(PilotWidgets.wrapped(
                    "Scan the LEFT QR (or tap the link) to open Remote Pilot on your phone — no app, account "
                    + "or VPN needed there. The RIGHT QR installs the optional BotPilot Android app."));
        } else {
            // Default path: VPN over the tailnet. Present the phone's 3 steps as the intended flow, not a
            // fallback apology.
            content.getChildren().add(PilotWidgets.wrapped(
                    "On your phone: ① install Tailscale, ② sign in to THIS same account, ③ scan the LEFT QR "
                    + "(or open the link). The RIGHT QR installs the optional BotPilot app."));
            content.getChildren().add(
                    PilotWidgets.linkBtn("Get Tailscale for your phone ▸", TAILSCALE_DOWNLOAD_URL));
        }

        // Lead with the input-mode choice: background (isolated :N) vs. mirroring the real desktop. This is the
        // recommended path (cursor stays free) and used to be buried at the very bottom of the dialog where the
        // user never found it — hence every click went through the cursor-moving :0 controller.
        content.getChildren().addAll(actions.backgroundMode().get(), new Separator());

        // The URL as a real clickable link (opens the system browser).
        Hyperlink link = new Hyperlink(url);
        link.setOnAction(e -> com.botmaker.studio.util.BrowserLauncher.open(url));
        link.setWrapText(true);
        // Editable field kept as a selectable copy fallback, in case the clipboard write is swallowed by the
        // window system (e.g. some Wayland setups).
        TextField urlField = new TextField(url);
        urlField.setPrefColumnCount(44);
        urlField.setEditable(false);
        Button copy = new Button("Copy URL");
        copy.setOnAction(e -> {
            urlField.requestFocus();
            urlField.selectAll();
            PilotWidgets.copyToClipboard(url);
            PilotWidgets.flashCopied(copy);
        });
        Button reset = new Button("Reset pairing token");
        reset.setTooltip(new Tooltip("Revoke the current token so previously-paired phones must scan again."));
        reset.setOnAction(e -> {
            PilotOutcome refreshed = actions.resetToken().apply(outcome);
            if (refreshed == null) return;
            alert.close();
            show(owner, refreshed, actions);
        });

        content.getChildren().addAll(link, new Label("Token: " + outcome.token()),
                new HBox(8, copy, reset), qrRow(url));

        if (mode == PilotMode.ALL_INTERFACES) {
            Label warn = PilotWidgets.wrapped("⚠ Anyone who can reach this machine's IP can view/control the "
                    + "bot with this token. Prefer connecting over Tailscale.");
            warn.setStyle("-fx-text-fill: #e67e22;");
            content.getChildren().add(warn);
        }

        // Advanced, opt-in: expose publicly over HTTPS so the phone needs no Tailscale/VPN. Only offered when
        // we're not already Funnel-live and the setup wizard isn't already on screen.
        if (!funnelLive && funnelError == null) {
            Hyperlink advanced =
                    new Hyperlink("Advanced: expose publicly over HTTPS (no VPN needed on the phone)…");
            advanced.setTooltip(new Tooltip("Uses Tailscale Funnel. Needs a one-time setup on this computer's "
                    + "Tailscale account; Studio will guide you."));
            advanced.setOnAction(e -> { alert.close(); actions.enableFunnel().run(); });
            content.getChildren().addAll(new Separator(), advanced);
        }

        alert.getDialogPane().setContent(content);
        alert.setResizable(true); // let the user grow it if the QR codes crowd the buttons on small screens
        alert.show();
    }

    /** Side-by-side QR codes with clear separation: left pairs the pilot URL, right downloads the APK. */
    private static Node qrRow(String pairingUrl) {
        HBox row = new HBox(40);
        row.setAlignment(Pos.TOP_CENTER);
        row.setStyle("-fx-padding: 8 0 0 0;");
        row.getChildren().addAll(
                qrCell(pairingUrl, "① Open on phone", "Scan to control the bot"),
                qrCell(APK_URL, "② Get the app (optional)", "Installs BotPilot for Android"));
        return row;
    }

    /**
     * A titled, captioned QR image in its own bordered card.
     *
     * <p>When the code can't be encoded the card still appears, saying so — it used to return {@code null} and
     * the caller dropped it silently, leaving a dialog with a missing QR and no explanation of why.
     */
    private static Node qrCell(String text, String title, String caption) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        Image code = QrCodes.qr(text, QR_PX);

        Node body;
        if (code != null) {
            ImageView iv = new ImageView(code);
            iv.setFitWidth(QR_PX);
            iv.setFitHeight(QR_PX);
            iv.setSmooth(false); // keep module edges sharp if the platform ever scales it
            // White backing so the encoded quiet zone survives against the dark card background/border.
            StackPane qrFrame = new StackPane(iv);
            qrFrame.setStyle("-fx-background-color: white; -fx-padding: 8; -fx-background-radius: 4;");
            body = qrFrame;
        } else {
            Label failed = new Label("Couldn't draw this QR code — use the link above instead.");
            failed.setWrapText(true);
            failed.setAlignment(Pos.CENTER);
            failed.setMaxWidth(QR_PX);
            failed.setStyle("-fx-text-fill: #e67e22;");
            body = failed;
        }

        Label cap = new Label(caption);
        cap.setWrapText(true);
        cap.setStyle("-fx-text-fill: #8b93a1;");
        VBox cell = new VBox(6, heading, body, cap);
        cell.setAlignment(Pos.CENTER);
        cell.setMaxWidth(QR_PX + 40);
        cell.setStyle("-fx-padding: 10; -fx-border-color: #3a3f4b; -fx-border-radius: 8; -fx-border-width: 1;");
        return cell;
    }
}
