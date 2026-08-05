package com.botmaker.studio.ui.app.pilot;

import com.botmaker.studio.ui.app.pilot.RemotePilotUi.FunnelDiag;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi.FunnelIssue;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The one-time "make Funnel work so the phone needs nothing" checklist, rendered from the off-thread
 * {@link FunnelDiag} snapshot — no blocking CLI calls happen here — with the current blocker highlighted and a
 * Re-check button that re-runs the whole bring-up.
 */
final class FunnelSetupWizard {

    /** Tailscale admin console where the one-time Funnel node-attribute is granted (computer/account side).
     *  The attribute lives in the tailnet policy file on the Access Controls page (there is no
     *  {@code /admin/settings/funnel} page — that 404s). */
    private static final String TAILSCALE_FUNNEL_ADMIN_URL = "https://login.tailscale.com/admin/acls";
    /** Tailscale admin DNS page where HTTPS certificates are enabled for the tailnet. */
    private static final String TAILSCALE_DNS_ADMIN_URL = "https://login.tailscale.com/admin/dns";
    /** The ACL policy snippet that grants the Funnel node-attribute (paste into the admin policy editor). */
    private static final String FUNNEL_ACL_SNIPPET =
            "\"nodeAttrs\": [{ \"target\": [\"autogroup:member\"], \"attr\": [\"funnel\"] }]";

    private FunnelSetupWizard() {
    }

    /**
     * @param onRecheck closes the owning dialog and re-runs the Funnel bring-up
     */
    static Node create(FunnelDiag diag, String funnelError, Runnable onRecheck) {
        VBox box = new VBox(6);
        Label title = PilotWidgets.wrapped("Set up Tailscale Funnel once on THIS computer's account — then any "
                + "phone connects by just opening the link (no Tailscale, no VPN, nothing to install on the "
                + "phone):");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);

        boolean step1ok = diag != null && diag.cliPresent() && diag.loggedIn();
        FunnelIssue issue = diag == null ? FunnelIssue.OTHER : diag.issue();

        // 1. Installed & signed in
        HBox s1 = PilotWidgets.stepRow(step1ok, "Tailscale installed & signed in on this computer",
                issue == FunnelIssue.NOT_INSTALLED || issue == FunnelIssue.LOGGED_OUT);
        if (diag != null && !diag.cliPresent()) {
            s1.getChildren().add(
                    PilotWidgets.linkBtn("Install Tailscale ▸", RemotePilotDialog.TAILSCALE_DOWNLOAD_URL));
        } else if (diag != null && !diag.loggedIn()) {
            s1.getChildren().add(PilotWidgets.copyCmdBtn("tailscale up"));
        }
        box.getChildren().add(s1);

        // 2. HTTPS certificates — can't reliably probe, but the CLI error names it (NO_HTTPS_CERT) when it's
        // the blocker, so highlight it then. This is the most common blocker once the ACL grant is in place.
        HBox s2 = PilotWidgets.stepRow(step1ok, "HTTPS certificates enabled for your tailnet",
                issue == FunnelIssue.NO_HTTPS_CERT);
        s2.getChildren().add(PilotWidgets.linkBtn("Open DNS settings ▸", TAILSCALE_DNS_ADMIN_URL));
        box.getChildren().add(s2);

        // 3. Funnel node-attribute granted in the ACL
        HBox s3 = PilotWidgets.stepRow(false, "\"funnel\" attribute granted in your tailnet ACL",
                issue == FunnelIssue.NOT_ENABLED);
        s3.getChildren().addAll(PilotWidgets.linkBtn("Open Access Controls ▸", TAILSCALE_FUNNEL_ADMIN_URL),
                PilotWidgets.copyCmdBtn(FUNNEL_ACL_SNIPPET, "Copy ACL snippet"));
        box.getChildren().add(s3);

        // 4. Operator (so Studio, running as you, can drive Funnel without sudo)
        HBox s4 = PilotWidgets.stepRow(false, "Let Studio manage Funnel without root (run once)",
                issue == FunnelIssue.NEEDS_OPERATOR);
        String operatorCmd = "sudo tailscale set --operator=" + System.getProperty("user.name", "$USER");
        s4.getChildren().add(PilotWidgets.copyCmdBtn(operatorCmd, "Copy command"));
        box.getChildren().add(s4);

        // Always surface the literal CLI reason (not just for OTHER) — it's the fastest way to tell HTTPS-cert
        // vs ACL vs operator apart when the checklist guesses wrong.
        if (funnelError != null && !funnelError.isBlank()) {
            Label raw = PilotWidgets.wrapped("Tailscale said: " + funnelError.trim());
            raw.setStyle("-fx-text-fill: #e67e22;");
            box.getChildren().add(raw);
        }

        Button recheck = new Button("Re-check & enable");
        recheck.setDefaultButton(true);
        recheck.setOnAction(e -> onRecheck.run());
        box.getChildren().add(recheck);
        return box;
    }
}
