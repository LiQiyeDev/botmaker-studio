package com.botmaker.studio.ui.app.pilot;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.pilot.NestedSessionLauncher;
import com.botmaker.studio.services.pilot.PilotControlService;
import com.botmaker.studio.services.pilot.PilotServer;
import com.botmaker.studio.services.pilot.TailscaleFunnelService;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote Pilot's editor-side state machine: brings the pilot server up, decides how it is exposed, and owns
 * the two heavyweight resources that outlive a dialog — the {@link PilotServer} itself and the
 * {@link NestedSessionLauncher} that produces the private {@code :N} display it streams.
 *
 * <p>Extracted from {@code UIManager}, which is a window builder and had no business holding a bound port.
 * The rendering is elsewhere in this package: {@link RemotePilotDialog} (pairing), {@link FunnelSetupWizard}
 * (the one-time Tailscale checklist) and {@link BackgroundModeBox} (private-display controls). This class
 * calls them; none of them calls back into it except through the callbacks it hands over.
 */
public final class RemotePilotUi {

    /** How the pilot ended up exposed — drives the dialog header, QR URL, and warning. */
    enum PilotMode { FUNNEL_HTTPS, TAILNET_DIRECT, ALL_INTERFACES }

    /** The specific reason Funnel isn't live, so the wizard can point at the exact one-time fix. */
    enum FunnelIssue { NONE, NOT_INSTALLED, LOGGED_OUT, NOT_ENABLED, NO_HTTPS_CERT, NEEDS_OPERATOR, OTHER }

    /** Snapshot of the Tailscale/Funnel state, computed off the FX thread, that drives the setup wizard. */
    record FunnelDiag(boolean cliPresent, boolean loggedIn, FunnelIssue issue) {}

    /**
     * Result of a pilot bring-up ({@link #startDirect()} / {@link #startFunnel()}) — enough to render the
     * pairing dialog on the FX thread.
     *
     * <p>{@code baseUrl} is the address without the query string ({@code http://host:port} or the Funnel
     * {@code https://…ts.net}); the pairing URL is derived from it and the token. Keeping the two apart is
     * what lets "Reset pairing token" rebuild the URL instead of rewriting it with a regex that only worked
     * while the token happened to be the last query parameter.
     */
    record PilotOutcome(String baseUrl, String token, PilotMode mode, String funnelError, FunnelDiag diag) {
        String url() {
            return baseUrl + "/?token=" + token;
        }

        PilotOutcome withToken(String fresh) {
            return new PilotOutcome(baseUrl, fresh, mode, funnelError, diag);
        }
    }

    private final Stage owner;
    private final EventBus eventBus;
    private final ProjectConfig config;
    private final ProjectSettingsService projectSettingsService;
    private final CodeExecutionService codeExecutionService;

    private PilotServer pilotServer;
    /** Produces + tears down the bot-owned {@code :N} session the pilot streams; created lazily with the server. */
    private NestedSessionLauncher nestedLauncher;

    /** The last successful bring-up, so re-clicking the toolbar just re-shows the same dialog instead of
     *  restarting the server on a fresh port (which would drop an already-paired phone). */
    private PilotOutcome lastOutcome;

    /** FX-thread-only guard: a bring-up is in flight, so a second click must not start a second one. */
    private boolean bringingUp;

    public RemotePilotUi(Stage owner,
                         EventBus eventBus,
                         ProjectConfig config,
                         ProjectSettingsService projectSettingsService,
                         CodeExecutionService codeExecutionService) {
        this.owner = owner;
        this.eventBus = eventBus;
        this.config = config;
        this.projectSettingsService = projectSettingsService;
        this.codeExecutionService = codeExecutionService;
    }

    /**
     * Starts (once) the remote BotPilot server and shows a pairing dialog. The <b>default</b> path is a direct
     * bind on the Tailscale tailnet interface: the phone reaches it by running Tailscale signed into the same
     * account (zero computer-side setup, no public URL, more private). Exposing the pilot publicly over
     * <b>Tailscale Funnel</b> ({@code https://<machine>.ts.net}, so the phone needs nothing) is an opt-in
     * "Advanced" action ({@link #enableFunnelExposure()}) because it requires one-time HTTPS-cert/ACL/operator
     * setup on this machine's account.
     *
     * <p>Idempotent while the server is up: it re-shows the existing pairing dialog, keeping the paired phone
     * connected on the same URL/port/token.
     */
    public void open() {
        bringUp(false, false);
    }

    /**
     * The live private session's host window id for the overlay to draw over, or {@code 0} when there is none —
     * revealing it first, since bring-up minimizes it and an overlay over a minimized window shows nothing.
     *
     * <p>The launcher is created lazily by the background-mode box, so a {@code null} one means no session has
     * ever been started in this project and there is nothing to look at.
     */
    public long liveSessionWindow() {
        return nestedLauncher == null ? 0 : nestedLauncher.revealHostWindow();
    }

    /**
     * Opt-in "Advanced" action: (re)bring up the pilot attempting <b>Tailscale Funnel</b> so the phone needs
     * nothing installed. Always rebinds (Funnel fronts a loopback bind, unlike the default tailnet bind), then
     * shows the pairing dialog — with the guided setup wizard if Funnel couldn't be enabled.
     */
    void enableFunnelExposure() {
        bringUp(true, true);
    }

    /**
     * The launcher for the private {@code :N} display, created on first use bound to the (by now started)
     * {@link PilotServer} and reused across dialog reopens so Stop and the status line still reflect a session
     * started earlier.
     */
    NestedSessionLauncher launcher() {
        if (nestedLauncher == null) {
            nestedLauncher = new NestedSessionLauncher(config.resourcesRoot(), pilotServer);
        }
        return nestedLauncher;
    }

    /**
     * Shared bring-up scaffold: (optionally) tear down a running server, ensure one exists, then run the chosen
     * bring-up off the FX thread and marshal the pairing dialog back.
     *
     * <p>The Tailscale CLI can block for seconds, so this must not run inline — doing so freezes (and, if the
     * CLI hangs, appears to crash) the UI.
     */
    private void bringUp(boolean forceRestart, boolean funnel) {
        // A bring-up is already running. Its progress dialog is not modal, so without this a second toolbar
        // click would start a second thread and race two start() calls onto the same server.
        if (bringingUp) return;

        // Already up and we're not deliberately restarting → re-show the same dialog, don't rebind the port.
        if (!forceRestart && pilotServer != null && pilotServer.isRunning() && lastOutcome != null) {
            showDialog(lastOutcome);
            return;
        }
        if (forceRestart && pilotServer != null) {
            pilotServer.close();
            lastOutcome = null;
        }
        if (pilotServer == null) {
            PilotControlService control = new PilotControlService(codeExecutionService);
            pilotServer = new PilotServer(eventBus, projectSettingsService, control, config.resourcesRoot());
        }
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Starting Remote Pilot…"));

        AtomicBoolean cancelled = new AtomicBoolean();
        Alert progress = progressDialog(cancelled);
        bringingUp = true;
        progress.show();

        Thread t = new Thread(() -> {
            PilotOutcome o = null;
            String error = null;
            try {
                o = funnel ? startFunnel() : startDirect();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final PilotOutcome outcome = o;
            final String err = error;
            Platform.runLater(() -> {
                bringingUp = false;
                progress.setResult(ButtonType.CANCEL); // let close() dismiss a button-less alert
                progress.close();
                if (outcome == null) {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                            "Could not start Remote Pilot: " + err));
                    return;
                }
                lastOutcome = outcome;
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                        (outcome.mode() == PilotMode.FUNNEL_HTTPS ? "Remote Pilot (HTTPS) at " : "Remote Pilot at ")
                                + outcome.url()));
                // Cancel can't unbind a server that has already come up, but it can honour what the user
                // actually asked for: no dialog. The status line above says where it is, and the toolbar
                // button re-shows the pairing dialog on demand.
                if (!cancelled.get()) showDialog(outcome);
            });
        }, "remote-pilot-start");
        t.setDaemon(true);
        t.start();
    }

    /** Indeterminate spinner shown while the (possibly multi-second) Tailscale bring-up runs off-thread. */
    private Alert progressDialog(AtomicBoolean cancelled) {
        Alert a = new Alert(Alert.AlertType.NONE);
        a.initOwner(owner);
        a.setTitle("Remote Pilot");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(30, 30);
        Label msg = new Label("Starting Remote Pilot…\nContacting Tailscale (this can take a few seconds).");
        msg.setWrapText(true);
        HBox box = new HBox(12, spinner, msg);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-padding: 10;");
        a.getDialogPane().setContent(box);
        a.getButtonTypes().setAll(ButtonType.CANCEL);
        // Only a real click means "cancel" — closing it programmatically below sets the same result.
        Button cancel = (Button) a.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancel != null) cancel.addEventFilter(ActionEvent.ACTION, e -> cancelled.set(true));
        return a;
    }

    private void showDialog(PilotOutcome outcome) {
        RemotePilotDialog.show(owner, outcome, new RemotePilotDialog.Actions(
                this::resetToken,
                this::enableFunnelExposure,
                () -> BackgroundModeBox.create(launcher(), projectSettingsService)));
    }

    /**
     * Revokes the pairing token and returns the outcome to re-render with, or {@code null} when there is no
     * server to revoke it on.
     */
    private PilotOutcome resetToken(PilotOutcome current) {
        if (pilotServer == null) return null;
        PilotOutcome refreshed = current.withToken(pilotServer.resetToken());
        lastOutcome = refreshed;
        return refreshed;
    }

    /**
     * Default bring-up: a direct bind on the Tailscale tailnet interface (phone runs Tailscale, same account),
     * or all interfaces (LAN, with a warning) when Tailscale isn't up. No {@code tailscale} CLI call, so it's
     * instant and never surfaces the Funnel wizard. Must run off the FX thread (server bind).
     */
    private PilotOutcome startDirect() {
        return directBind(null, null);
    }

    /**
     * Opt-in bring-up: attempt Tailscale Funnel (public HTTPS, phone needs nothing). On success →
     * {@link PilotMode#FUNNEL_HTTPS}; on any failure fall back to a direct bind but carry the reason
     * ({@code funnelError}/{@link FunnelDiag}) so the pairing dialog shows the setup wizard.
     * Blocking (Tailscale CLI + server bind); must run off the FX thread.
     */
    private PilotOutcome startFunnel() {
        TailscaleFunnelService funnel = new TailscaleFunnelService();
        boolean cli = funnel.isAvailable();
        boolean loggedIn = cli && funnel.isLoggedIn();
        String funnelError;
        FunnelIssue issue;
        if (cli && loggedIn && funnel.dnsName().isPresent()) {
            PilotServer.Endpoint ep = pilotServer.start("127.0.0.1"); // loopback — only Funnel fronts it
            var result = funnel.enable(ep.port());
            if (result.ok()) {
                PilotServer.Endpoint pub = pilotServer.attachFunnel(funnel, result.publicBase());
                return new PilotOutcome(result.publicBase(), pub.token(), PilotMode.FUNNEL_HTTPS, null,
                        new FunnelDiag(true, true, FunnelIssue.NONE));
            }
            // Funnel present but couldn't be enabled (e.g. HTTPS certs / ACL / operator): tear the loopback
            // server down and fall through to a directly-bound one, surfacing the reason.
            funnelError = result.error();
            issue = classifyFunnel(result.error());
            pilotServer.close();
        } else {
            issue = !cli ? FunnelIssue.NOT_INSTALLED : (!loggedIn ? FunnelIssue.LOGGED_OUT : FunnelIssue.OTHER);
            funnelError = switch (issue) {
                case NOT_INSTALLED -> "Tailscale isn't installed on this computer.";
                case LOGGED_OUT -> "Tailscale isn't signed in on this computer.";
                default -> "Tailscale Funnel is unavailable.";
            };
        }
        return directBind(funnelError, new FunnelDiag(cli, loggedIn, issue));
    }

    /** The tailnet-or-all-interfaces bind both paths end on, carrying any Funnel failure through to the dialog. */
    private PilotOutcome directBind(String funnelError, FunnelDiag diag) {
        String tailscale = PilotServer.detectTailscaleHost();
        boolean allInterfaces = tailscale == null;
        PilotServer.Endpoint endpoint = pilotServer.start(allInterfaces ? "0.0.0.0" : tailscale);

        String displayHost = allInterfaces ? hostForUrl() : tailscale;
        return new PilotOutcome("http://" + displayHost + ":" + endpoint.port(), endpoint.token(),
                allInterfaces ? PilotMode.ALL_INTERFACES : PilotMode.TAILNET_DIRECT, funnelError, diag);
    }

    /** Maps a {@code tailscale funnel} stderr line to the specific one-time fix the wizard should surface. */
    static FunnelIssue classifyFunnel(String err) {
        if (err == null) return FunnelIssue.OTHER;
        String e = err.toLowerCase();
        if (e.contains("operator")) return FunnelIssue.NEEDS_OPERATOR;
        if (e.contains("https") || e.contains("cert")) return FunnelIssue.NO_HTTPS_CERT;
        if (e.contains("not enabled")) return FunnelIssue.NOT_ENABLED;
        if (e.contains("not logged in") || e.contains("logged out")) return FunnelIssue.LOGGED_OUT;
        return FunnelIssue.OTHER;
    }

    /** Best-effort local IPv4 for the displayed URL when binding all interfaces (no Tailscale). */
    private static String hostForUrl() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
