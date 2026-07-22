package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.dnd.BlockEvent;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectRepair;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.validation.DiagnosticsManager;
import com.botmaker.studio.validation.ErrorTranslator;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class UIManager {

    /** Narrowest the file explorer may be dragged. */
    private static final double EXPLORER_MIN_WIDTH = 150;
    /** Widest the file explorer may be dragged — without this the divider has no upper bound at all. */
    private static final double EXPLORER_MAX_WIDTH = 460;

    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final DiagnosticsManager diagnosticsManager;
    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ProjectState state;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectSettingsService projectSettingsService;
    private final ProjectAnalyzer projectAnalyzer;

    private final ToolbarManager toolbarManager;
    private final EventLogManager eventLogManager;
    private final MenuBarManager menuBarManager;
    private final com.botmaker.studio.runtime.CodeExecutionService codeExecutionService;
    private com.botmaker.studio.services.pilot.PilotServer pilotServer;
    private final FileExplorerManager fileExplorerManager;

    // Sharing / GitHub services — promoted to fields so the toolbar VCS/account buttons and the VCS bottom
    // tab can reach them at scene-build time, not just the menu wiring in the constructor.
    private GitHubAuth gitHubAuth;
    private GitHubClient gitHubClient;
    private GitHubGallery gallery;
    private BotPublisher botPublisher;
    private BotInstaller botInstaller;
    private Runnable openPublishDialog;
    private Runnable openVcsDialog;

    /** The canvas VBox's optional Reader-mode banner, so the Editor toggle can remove it in place. */
    private VBox canvasColumn;
    private VcsPanel vcsPanel;
    private int vcsTabIndex = -1;

    private VBox blocksContainer;
    private ScrollPane blocksScrollPane;
    private Label statusLabel;
    private TextArea outputArea;
    private ListView<Diagnostic> errorListView;
    private TabPane bottomTabPane;
    private Consumer<Void> onSelectProject;

    // --- NEW: Filter State ---
    private List<Diagnostic> allDiagnostics = new ArrayList<>();
    /** Opens the Manage Libraries dialog; captured so the Getting Started guide can reuse it. */
    private Runnable onManageLibraries;
    private ToggleButton errorFilterBtn;
    private ToggleButton warningFilterBtn;
    private ToggleButton infoFilterBtn;

    public UIManager(BlockDragAndDropManager dragAndDropManager,
                     EventBus eventBus,
                     CodeEditorService codeEditorService,
                     DiagnosticsManager diagnosticsManager,
                     Stage primaryStage,
                     ProjectConfig config,
                     ProjectState state, ProjectAnalyzer projectAnalyzer,
                     LibraryService libraryService,
                     ActivityService activityService,
                     com.botmaker.studio.runtime.CodeExecutionService codeExecutionService) {
        this.eventBus = eventBus;
        this.codeEditorService = codeEditorService;
        this.codeExecutionService = codeExecutionService;
        this.diagnosticsManager = diagnosticsManager;
        this.primaryStage = primaryStage;
        this.config = config;
        this.state = state;
        this.projectAnalyzer = projectAnalyzer;

        // Editor settings (capture targets + default). Stateless over (config, state, eventBus); the
        // capture service honors the default target so pickers stop re-asking which screen to use.
        this.projectSettingsService = new ProjectSettingsService(config, state, eventBus);
        this.screenCaptureService = new ScreenCaptureService(projectSettingsService);

        this.toolbarManager = new ToolbarManager(eventBus, projectSettingsService);
        this.eventLogManager = new EventLogManager(eventBus);
        this.menuBarManager = new MenuBarManager(primaryStage);
        this.menuBarManager.setEventBus(eventBus);
        this.menuBarManager.setProjectPath(config.projectPath());
        // Startup banner: which local builds are actually running (distinct from the GitHub update check).
        System.out.println(com.botmaker.studio.config.VersionInfo.banner(config.projectPath()));
        MavenCentralSearch mavenCentralSearch = new MavenCentralSearch();
        JitPackSearch jitPackSearch = new JitPackSearch();
        this.onManageLibraries = () ->
                new ManageLibrariesDialog(primaryStage, libraryService, mavenCentralSearch, jitPackSearch).show();
        this.menuBarManager.setOnManageLibraries(onManageLibraries);
        this.menuBarManager.setOnProjectSetup(this::openProjectSetup);
        this.toolbarManager.setOnProjectSetup(this::openProjectSetup);
        this.menuBarManager.setOnGettingStarted(this::openGettingStarted);
        this.menuBarManager.setOnManageImports(() ->
                new ManageImportsDialog(primaryStage, codeEditorService).show());
        Runnable openActivityFlow = () -> new ActivityFlowDialog(primaryStage, activityService).show();
        this.menuBarManager.setOnActivityFlow(openActivityFlow);
        this.toolbarManager.setOnActivityFlow(openActivityFlow);
        this.menuBarManager.setOnRecoverProjectFiles(() -> recoverProjectFiles(activityService));
        this.menuBarManager.setOnManageResources(this::openResourceManager);
        this.menuBarManager.setOnProjectSettings(() ->
                new ProjectSettingsDialog(primaryStage, projectSettingsService, projectAnalyzer).show());
        this.toolbarManager.setOnManageCaptureTargets(() ->
                new ManageCaptureTargetsDialog(primaryStage, projectSettingsService).show());
        this.toolbarManager.setOnManageLaunchTarget(() ->
                new LaunchTargetDialog(primaryStage, config.resourcesRoot(), spec -> { }).show());
        this.toolbarManager.setOnToggleDebugOutput(
                com.botmaker.studio.project.ProjectCreator.readDebug(config.resourcesRoot()),
                on -> {
                    try {
                        com.botmaker.studio.project.ProjectCreator.writeDebug(config.resourcesRoot(), on);
                    } catch (java.io.IOException ex) {
                        System.err.println("Failed to save debug setting: " + ex.getMessage());
                    }
                });
        this.toolbarManager.setOnEnableRemotePilot(this::openRemotePilot);
        this.toolbarManager.setOnCaptureTemplates(this::openOverlayTemplateCapture);
        this.toolbarManager.setOnOverlayEditor(this::openOverlayEditor);
        this.toolbarManager.setOnAccessResources(this::openResourceManager);
        this.gitHubClient = new GitHubClient();
        this.gallery = new GitHubGallery(gitHubClient);
        this.gitHubAuth = new GitHubAuth();
        this.botInstaller = new BotInstaller(gitHubClient, gallery);
        this.botPublisher = new BotPublisher(gitHubClient, gitHubAuth);
        this.menuBarManager.setOnBrowseGallery(() ->
                new GalleryDialog(primaryStage, gallery, botInstaller, gitHubAuth, gitHubClient).show());
        this.openPublishDialog = () ->
                new PublishDialog(primaryStage, gitHubAuth, gitHubClient, gallery, botPublisher,
                        config.projectName(), config.projectPath()).show();
        this.menuBarManager.setOnPublishGallery(openPublishDialog);
        this.openVcsDialog = () ->
                new VcsDialog(primaryStage, config.projectName(), config.projectPath(), botPublisher,
                        gitHubAuth, gitHubClient, eventBus, openPublishDialog).show();
        this.menuBarManager.setOnShowHistory(openVcsDialog);
        this.menuBarManager.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));
        this.menuBarManager.setOnEnableRemotePilot(this::openRemotePilot);
        this.fileExplorerManager = new FileExplorerManager(config, codeEditorService, state, activityService, eventBus);

        setupEventHandlers();
    }

    /**
     * Project ▸ Recover Project Files — puts back the scaffolding BotMaker owns.
     *
     * <p>Two kinds of breakage, because there are two ways to break it (see {@link ProjectRepair}):
     * <b>missing files</b>, deleted outside the Studio or from the explorer, which are recreated but never
     * overwritten; and <b>damaged locked methods</b>, where the file is present but something BotMaker calls has
     * been renamed or rewritten. The second used to be invisible here — the file existed, so recovery declared
     * the project healthy while the bot didn't compile. The user's own methods, and their own method bodies, are
     * never touched by either.
     */
    private void recoverProjectFiles(ActivityService activityService) {
        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, state.getTemplate(), activityService.current());
        List<ProjectRepair.Damage> damaged =
                ProjectRepair.findDamaged(config, state.getTemplate(), canonicalScaffold(activityService));

        if (missing.isEmpty() && damaged.isEmpty()) {
            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.setTitle("Recover Project Files");
            ok.setHeaderText("Nothing to recover.");
            ok.setContentText("Every file this project needs is present, and nothing BotMaker generates has "
                    + "been changed.");
            ok.showAndWait();
            return;
        }

        StringBuilder detail = new StringBuilder();
        ProjectRepair.summarise(missing).forEach((reason, names) ->
                detail.append(reason).append(":\n  ").append(String.join("\n  ", names)).append("\n\n"));
        if (!damaged.isEmpty()) {
            detail.append("methods BotMaker needs (will be restored):\n  ");
            detail.append(damaged.stream().map(ProjectRepair.Damage::describe)
                    .collect(java.util.stream.Collectors.joining("\n  ")));
            detail.append("\n\n");
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Recover Project Files");
        confirm.setHeaderText(headerFor(missing, damaged));
        confirm.setContentText(detail.toString().trim()
                + "\n\nExisting files are never overwritten, and your own methods — and the bodies of the "
                + "methods you write — are never touched.");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        try {
            ProjectRepair.recover(config, missing);
            List<java.nio.file.Path> repaired =
                    ProjectRepair.repairDamaged(config, state.getTemplate(),
                            canonicalScaffold(activityService), damaged);

            // Activity stubs, activities.json, and the generated Activities/ActivityRegistry are
            // ActivityService's to write — re-running update() with the current config restores them all.
            // It writes off-thread, so refresh the tree once it's done rather than racing it.
            if (ProjectRepair.needsActivityRegeneration(missing)) {
                activityService.update(activityService.current())
                        .thenRun(() -> javafx.application.Platform.runLater(fileExplorerManager::refreshTree));
            }

            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(summaryOf(missing, repaired)));
            fileExplorerManager.refreshTree();

            // A repaired file's blocks on screen are now stale — reload the one being looked at.
            if (state.getActiveFile() != null && repaired.contains(state.getActiveFile().getPath())) {
                codeEditorService.switchToFile(state.getActiveFile().getPath());
            }
        } catch (java.io.IOException ex) {
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Recover Project Files");
            err.setHeaderText("Could not recover the project files.");
            err.setContentText(ex.getMessage());
            err.showAndWait();
        }
    }

    /** What the generators would produce for this project's scaffold today, keyed by path. */
    private java.util.Map<java.nio.file.Path, String> canonicalScaffold(ActivityService activityService) {
        java.util.Map<java.nio.file.Path, String> byPath = new java.util.LinkedHashMap<>();
        java.nio.file.Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return byPath;

        ProjectTemplate template = state.getTemplate() != null ? state.getTemplate() : ProjectTemplate.EMPTY;
        ProjectCreator.sourcesFor(template, config.className(), config.packageName())
                .forEach((name, source) -> byPath.put(mainDir.resolve(name), source));

        // Each activity stub's isEnabled() is generated against that activity's own flag, so the canonical
        // source is per-file — only ActivityService can say what it should be.
        for (ActivityDefinition activity : activityService.current().activities()) {
            byPath.put(config.activitiesPackageDir().resolve(activity.name() + ".java"),
                    activityService.generateStubSource(activity));
        }
        return byPath;
    }

    private static String headerFor(List<ProjectRepair.Missing> missing, List<ProjectRepair.Damage> damaged) {
        if (damaged.isEmpty()) return missing.size() + " file(s) are missing and will be regenerated.";
        if (missing.isEmpty()) return damaged.size() + " method(s) BotMaker needs will be restored.";
        return missing.size() + " file(s) are missing and " + damaged.size()
                + " method(s) BotMaker needs have been changed.";
    }

    private static String summaryOf(List<ProjectRepair.Missing> missing, List<java.nio.file.Path> repaired) {
        StringBuilder sb = new StringBuilder("Recovered ");
        sb.append(missing.size()).append(" file(s)");
        if (!repaired.isEmpty()) sb.append(" and repaired ").append(repaired.size()).append(" file(s)");
        return sb.append('.').toString();
    }

    /**
     * Bounds how far the explorer/canvas divider can be dragged, in pixels.
     *
     * <p>A {@code SplitPane} divider is otherwise unbounded: because the explorer intentionally has no
     * {@code maxWidth} (a cap there leaves dead space beside the tree), it could be dragged across the entire
     * window and squash the code canvas to nothing. Clamping the divider keeps the drag range sane while the
     * explorer still fills whatever column width it is given.
     *
     * <p>Re-clamped on width changes too, so shrinking the window can't leave the divider out of range.
     */
    private static void clampExplorerWidth(SplitPane mainSplit) {
        if (mainSplit.getDividers().isEmpty()) return;
        SplitPane.Divider divider = mainSplit.getDividers().get(0);

        Runnable clamp = () -> {
            double width = mainSplit.getWidth();
            if (width <= 0) return;                       // not laid out yet; the width listener re-runs this
            double min = EXPLORER_MIN_WIDTH / width;
            double max = EXPLORER_MAX_WIDTH / width;
            if (min > max) return;                        // window narrower than the explorer's own minimum
            double pos = divider.getPosition();
            if (pos > max) divider.setPosition(max);
            else if (pos < min) divider.setPosition(min);
        };

        divider.positionProperty().addListener((obs, ov, nv) -> clamp.run());
        mainSplit.widthProperty().addListener((obs, ov, nv) -> clamp.run());
    }

    /** Stable "latest release" permalink the install-app QR points at; the botmaker-pilot CI attaches this. */
    private static final String APK_URL =
            "https://github.com/LiQiyeDev/botmaker-pilot/releases/latest/download/botpilot.apk";

    /** Tailscale admin console where the one-time Funnel node-attribute is granted (computer/account side).
     *  The attribute lives in the tailnet policy file on the Access Controls page (there is no
     *  {@code /admin/settings/funnel} page — that 404s). */
    private static final String TAILSCALE_FUNNEL_ADMIN_URL =
            "https://login.tailscale.com/admin/acls";
    /** Tailscale admin DNS page where HTTPS certificates are enabled for the tailnet. */
    private static final String TAILSCALE_DNS_ADMIN_URL =
            "https://login.tailscale.com/admin/dns";
    /** Where a user without Tailscale installs it. */
    private static final String TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download";
    /** The ACL policy snippet that grants the Funnel node-attribute (paste into the admin policy editor). */
    private static final String FUNNEL_ACL_SNIPPET =
            "\"nodeAttrs\": [{ \"target\": [\"autogroup:member\"], \"attr\": [\"funnel\"] }]";

    /** How the pilot ended up exposed — drives the dialog header, QR URL, and warning. */
    private enum PilotMode { FUNNEL_HTTPS, TAILNET_DIRECT, ALL_INTERFACES }

    /** The specific reason Funnel isn't live, so the wizard can point at the exact one-time fix. */
    private enum FunnelIssue { NONE, NOT_INSTALLED, LOGGED_OUT, NOT_ENABLED, NO_HTTPS_CERT, NEEDS_OPERATOR, OTHER }

    /** Snapshot of the Tailscale/Funnel state, computed off the FX thread, that drives the setup wizard. */
    private record FunnelDiag(boolean cliPresent, boolean loggedIn, FunnelIssue issue) {}

    /** Result of a pilot bring-up ({@link #startRemotePilotDirect()} / {@link #startRemotePilotFunnel()}) —
     *  enough to render the pairing dialog on the FX thread. */
    private record PilotOutcome(String url, String token, PilotMode mode, String funnelError, FunnelDiag diag) {}

    /** The last successful bring-up, so re-clicking the toolbar just re-shows the same dialog instead of
     *  restarting the server on a fresh port (which would drop an already-paired phone). */
    private PilotOutcome lastPilotOutcome;

    /**
     * Starts (once) the remote BotPilot server and shows a pairing dialog. The <b>default</b> path is a direct
     * bind on the Tailscale tailnet interface: the phone reaches it by running Tailscale signed into the same
     * account (zero computer-side setup, no public URL, more private). Exposing the pilot publicly over
     * <b>Tailscale Funnel</b> ({@code https://<machine>.ts.net}, so the phone needs nothing) is an opt-in
     * "Advanced" action ({@link #enableFunnelExposure()}) because it requires one-time HTTPS-cert/ACL/operator
     * setup on this machine's account.
     *
     * <p>The tailnet bind is cheap, but the (opt-in) Funnel bring-up runs the {@code tailscale} CLI (which can
     * block for seconds), so both go through a background thread; only the resulting dialog is marshalled back
     * to the FX thread. Doing it inline would freeze (and, if the CLI hangs, appear to crash) the UI.
     */
    private void openRemotePilot() {
        openRemotePilot(false);
    }

    /**
     * @param forceRestart when {@code false} (toolbar/menu click) and the server is already running, this is
     *   idempotent — it just re-shows the existing pairing dialog, keeping the paired phone connected on the
     *   same URL/port/token. When {@code true} it tears the server down and re-runs the bring-up, deliberately
     *   rebinding. The default bring-up is the direct tailnet bind (no Funnel); {@link #enableFunnelExposure()}
     *   is the opt-in path that attempts Funnel.
     */
    private void openRemotePilot(boolean forceRestart) {
        bringUpRemotePilot(forceRestart, false);
    }

    /**
     * Opt-in "Advanced" action: (re)bring up the pilot attempting <b>Tailscale Funnel</b> so the phone needs
     * nothing installed. Always rebinds (Funnel fronts a loopback bind, unlike the default tailnet bind), then
     * shows the pairing dialog — with the guided setup wizard if Funnel couldn't be enabled.
     */
    private void enableFunnelExposure() {
        bringUpRemotePilot(true, true);
    }

    /**
     * Shared bring-up scaffold: (optionally) tear down a running server, ensure one exists, then run the chosen
     * bring-up ({@code funnel ? }{@link #startRemotePilotFunnel()}{@code : }{@link #startRemotePilotDirect()})
     * off the FX thread and marshal the pairing dialog back.
     */
    private void bringUpRemotePilot(boolean forceRestart, boolean funnel) {
        // Already up and we're not deliberately restarting → re-show the same dialog, don't rebind the port.
        if (!forceRestart && pilotServer != null && pilotServer.isRunning() && lastPilotOutcome != null) {
            showRemotePilotDialog(lastPilotOutcome.url(), lastPilotOutcome.token(), lastPilotOutcome.mode(),
                    lastPilotOutcome.funnelError(), lastPilotOutcome.diag());
            return;
        }
        if (forceRestart && pilotServer != null) {
            pilotServer.close();
            lastPilotOutcome = null;
        }
        if (pilotServer == null) {
            var control = new com.botmaker.studio.services.pilot.PilotControlService(codeExecutionService);
            pilotServer = new com.botmaker.studio.services.pilot.PilotServer(
                    eventBus, projectSettingsService, control);
        }
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Starting Remote Pilot…"));
        Alert progress = buildPilotProgressDialog();
        progress.show();
        Thread t = new Thread(() -> {
            PilotOutcome o = null;
            String error = null;
            try {
                o = funnel ? startRemotePilotFunnel() : startRemotePilotDirect();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final PilotOutcome outcome = o;
            final String err = error;
            javafx.application.Platform.runLater(() -> {
                progress.setResult(ButtonType.CANCEL); // let close() dismiss a button-less alert
                progress.close();
                if (outcome != null) {
                    lastPilotOutcome = outcome;
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                            (outcome.mode() == PilotMode.FUNNEL_HTTPS ? "Remote Pilot (HTTPS) at " : "Remote Pilot at ")
                                    + outcome.url()));
                    showRemotePilotDialog(outcome.url(), outcome.token(), outcome.mode(),
                            outcome.funnelError(), outcome.diag());
                } else {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                            "Could not start Remote Pilot: " + err));
                }
            });
        }, "remote-pilot-start");
        t.setDaemon(true);
        t.start();
    }

    /** Indeterminate spinner shown while the (possibly multi-second) Tailscale bring-up runs off-thread. */
    private Alert buildPilotProgressDialog() {
        Alert a = new Alert(Alert.AlertType.NONE);
        a.initOwner(primaryStage);
        a.setTitle("Remote Pilot");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(30, 30);
        Label msg = new Label("Starting Remote Pilot…\nContacting Tailscale (this can take a few seconds).");
        msg.setWrapText(true);
        HBox box = new HBox(12, spinner, msg);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-padding: 10;");
        a.getDialogPane().setContent(box);
        a.getButtonTypes().setAll(ButtonType.CANCEL); // present so the pane is valid; we close it programmatically
        return a;
    }

    /**
     * Default bring-up: a direct bind on the Tailscale tailnet interface (phone runs Tailscale, same account),
     * or all interfaces (LAN, with a warning) when Tailscale isn't up. No {@code tailscale} CLI call, so it's
     * instant and never surfaces the Funnel wizard. Must run off the FX thread (server bind).
     */
    private PilotOutcome startRemotePilotDirect() {
        String tailscale = com.botmaker.studio.services.pilot.PilotServer.detectTailscaleHost();
        boolean allInterfaces = tailscale == null;
        var endpoint = pilotServer.start(allInterfaces ? "0.0.0.0" : tailscale);

        String displayHost = allInterfaces ? hostForUrl() : tailscale;
        String url = "http://" + displayHost + ":" + endpoint.port() + "/?token=" + endpoint.token();
        return new PilotOutcome(url, endpoint.token(),
                allInterfaces ? PilotMode.ALL_INTERFACES : PilotMode.TAILNET_DIRECT, null, null);
    }

    /**
     * Opt-in bring-up: attempt Tailscale Funnel (public HTTPS, phone needs nothing). On success →
     * {@link PilotMode#FUNNEL_HTTPS}; on any failure fall back to a direct bind but carry the reason
     * ({@code funnelError}/{@link FunnelDiag}) so {@link #showRemotePilotDialog} shows the setup wizard.
     * Blocking (Tailscale CLI + server bind); must run off the FX thread.
     */
    private PilotOutcome startRemotePilotFunnel() {
        var funnel = new com.botmaker.studio.services.pilot.TailscaleFunnelService();
        boolean cli = funnel.isAvailable();
        boolean loggedIn = cli && funnel.isLoggedIn();
        String funnelError;
        FunnelIssue issue;
        if (cli && loggedIn && funnel.dnsName().isPresent()) {
            var ep = pilotServer.start("127.0.0.1"); // loopback — only Funnel fronts it
            var result = funnel.enable(ep.port());
            if (result.ok()) {
                var pub = pilotServer.attachFunnel(funnel, result.publicBase());
                return new PilotOutcome(pub.url(), pub.token(), PilotMode.FUNNEL_HTTPS, null,
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

        String tailscale = com.botmaker.studio.services.pilot.PilotServer.detectTailscaleHost();
        boolean allInterfaces = tailscale == null;
        var endpoint = pilotServer.start(allInterfaces ? "0.0.0.0" : tailscale);

        String displayHost = allInterfaces ? hostForUrl() : tailscale;
        String url = "http://" + displayHost + ":" + endpoint.port() + "/?token=" + endpoint.token();
        return new PilotOutcome(url, endpoint.token(),
                allInterfaces ? PilotMode.ALL_INTERFACES : PilotMode.TAILNET_DIRECT, funnelError,
                new FunnelDiag(cli, loggedIn, issue));
    }

    /** Maps a {@code tailscale funnel} stderr line to the specific one-time fix the wizard should surface. */
    private static FunnelIssue classifyFunnel(String err) {
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
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void showRemotePilotDialog(String url, String token, PilotMode mode, String funnelError,
                                       FunnelDiag diag) {
        boolean funnelLive = mode == PilotMode.FUNNEL_HTTPS;
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(primaryStage);
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
            content.getChildren().addAll(funnelWizardBox(diag, funnelError, alert), new Separator());
            content.getChildren().add(wrapped("Meanwhile you can connect right now over Tailscale with the link "
                    + "below — the phone just needs Tailscale signed in to the same account."));
        } else if (funnelLive) {
            content.getChildren().add(wrapped(
                    "Scan the LEFT QR (or tap the link) to open Remote Pilot on your phone — no app, account "
                    + "or VPN needed there. The RIGHT QR installs the optional BotPilot Android app."));
        } else {
            // Default path: VPN over the tailnet. Present the phone's 3 steps as the intended flow, not a
            // fallback apology.
            content.getChildren().add(wrapped("On your phone: ① install Tailscale, ② sign in to THIS same "
                    + "account, ③ scan the LEFT QR (or open the link). The RIGHT QR installs the optional "
                    + "BotPilot app."));
            content.getChildren().add(linkBtn("Get Tailscale for your phone ▸", TAILSCALE_DOWNLOAD_URL));
        }

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
            copyToClipboard(url);
            copy.setText("Copied ✓");
        });
        Button reset = new Button("Reset pairing token");
        reset.setTooltip(new Tooltip(
                "Revoke the current token so previously-paired phones must scan again."));
        reset.setOnAction(e -> {
            if (pilotServer == null) return;
            String fresh = pilotServer.resetToken();
            String newUrl = url.replaceFirst("token=[^&]*$", "token=" + fresh);
            alert.close();
            showRemotePilotDialog(newUrl, fresh, mode, funnelError, diag);
        });

        content.getChildren().addAll(link, new Label("Token: " + token), new HBox(8, copy, reset), qrRow(url));

        if (mode == PilotMode.ALL_INTERFACES) {
            Label warn = wrapped("⚠ Anyone who can reach this machine's IP can view/control the bot with this "
                    + "token. Prefer connecting over Tailscale.");
            warn.setStyle("-fx-text-fill: #e67e22;");
            content.getChildren().add(warn);
        }

        // Advanced, opt-in: expose publicly over HTTPS so the phone needs no Tailscale/VPN. Only offered when
        // we're not already Funnel-live and the setup wizard isn't already on screen.
        if (!funnelLive && funnelError == null) {
            Hyperlink advanced = new Hyperlink("Advanced: expose publicly over HTTPS (no VPN needed on the phone)…");
            advanced.setTooltip(new Tooltip("Uses Tailscale Funnel. Needs a one-time setup on this computer's "
                    + "Tailscale account; Studio will guide you."));
            advanced.setOnAction(e -> { alert.close(); enableFunnelExposure(); });
            content.getChildren().addAll(new Separator(), advanced);
        }
        alert.getDialogPane().setContent(content);
        alert.setResizable(true); // let the user grow it if the QR codes crowd the buttons on small screens
        alert.show();
    }

    /**
     * The one-time "make Funnel work so the phone needs nothing" checklist. Rendered from the off-thread
     * {@link FunnelDiag} snapshot (no blocking CLI calls here), with the current blocker highlighted and a
     * Re-check button that re-runs the whole bring-up.
     */
    private Node funnelWizardBox(FunnelDiag diag, String funnelError, Alert owner) {
        VBox box = new VBox(6);
        Label title = wrapped("Set up Tailscale Funnel once on THIS computer's account — then any phone "
                + "connects by just opening the link (no Tailscale, no VPN, nothing to install on the phone):");
        title.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(title);

        boolean step1ok = diag != null && diag.cliPresent() && diag.loggedIn();
        FunnelIssue issue = diag == null ? FunnelIssue.OTHER : diag.issue();

        // 1. Installed & signed in
        HBox s1 = stepRow(step1ok, "Tailscale installed & signed in on this computer",
                issue == FunnelIssue.NOT_INSTALLED || issue == FunnelIssue.LOGGED_OUT);
        if (diag != null && !diag.cliPresent()) s1.getChildren().add(linkBtn("Install Tailscale ▸", TAILSCALE_DOWNLOAD_URL));
        else if (diag != null && !diag.loggedIn()) s1.getChildren().add(copyCmdBtn("tailscale up"));
        box.getChildren().add(s1);

        // 2. HTTPS certificates — can't reliably probe, but the CLI error names it (NO_HTTPS_CERT) when it's
        // the blocker, so highlight it then. This is the most common blocker once the ACL grant is in place.
        HBox s2 = stepRow(step1ok, "HTTPS certificates enabled for your tailnet",
                issue == FunnelIssue.NO_HTTPS_CERT);
        s2.getChildren().add(linkBtn("Open DNS settings ▸", TAILSCALE_DNS_ADMIN_URL));
        box.getChildren().add(s2);

        // 3. Funnel node-attribute granted in the ACL
        HBox s3 = stepRow(false, "\"funnel\" attribute granted in your tailnet ACL",
                issue == FunnelIssue.NOT_ENABLED);
        s3.getChildren().addAll(linkBtn("Open Access Controls ▸", TAILSCALE_FUNNEL_ADMIN_URL),
                copyCmdBtn(FUNNEL_ACL_SNIPPET, "Copy ACL snippet"));
        box.getChildren().add(s3);

        // 4. Operator (so Studio, running as you, can drive Funnel without sudo)
        HBox s4 = stepRow(false, "Let Studio manage Funnel without root (run once)",
                issue == FunnelIssue.NEEDS_OPERATOR);
        String operatorCmd = "sudo tailscale set --operator=" + System.getProperty("user.name", "$USER");
        s4.getChildren().add(copyCmdBtn(operatorCmd, "Copy command"));
        box.getChildren().add(s4);

        // Always surface the literal CLI reason (not just for OTHER) — it's the fastest way to tell HTTPS-cert
        // vs ACL vs operator apart when the checklist guesses wrong.
        if (funnelError != null && !funnelError.isBlank()) {
            Label raw = wrapped("Tailscale said: " + funnelError.trim());
            raw.setStyle("-fx-text-fill: #e67e22;");
            box.getChildren().add(raw);
        }

        Button recheck = new Button("Re-check & enable");
        recheck.setDefaultButton(true);
        recheck.setOnAction(e -> { owner.close(); enableFunnelExposure(); });
        box.getChildren().add(recheck);
        return box;
    }

    /** One wizard checklist row: a ✓/✗ status glyph + label; highlighted orange when it's the active blocker. */
    private static HBox stepRow(boolean done, String text, boolean isBlocker) {
        Label glyph = new Label(done ? "✓" : "✗");
        glyph.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (done ? "#27ae60" : "#e67e22") + ";");
        Label label = new Label(text);
        label.setWrapText(true);
        if (isBlocker) label.setStyle("-fx-text-fill: #e67e22; -fx-font-weight: bold;");
        HBox row = new HBox(8, glyph, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Hyperlink linkBtn(String text, String url) {
        Hyperlink h = new Hyperlink(text);
        h.setOnAction(e -> com.botmaker.studio.util.BrowserLauncher.open(url));
        return h;
    }

    private static Button copyCmdBtn(String command) { return copyCmdBtn(command, "Copy"); }

    /** A small button that copies {@code command} to the clipboard (for shell commands / ACL snippets). */
    private static Button copyCmdBtn(String command, String label) {
        Button b = new Button(label);
        b.setTooltip(new Tooltip(command));
        b.setOnAction(e -> { copyToClipboard(command); b.setText("Copied ✓"); });
        return b;
    }

    private static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(460);
        return l;
    }

    private static void copyToClipboard(String text) {
        var cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    /** Side-by-side QR codes with clear separation: left pairs the pilot URL, right downloads the APK. */
    private static Node qrRow(String pairingUrl) {
        HBox row = new HBox(40);
        row.setAlignment(Pos.TOP_CENTER);
        row.setStyle("-fx-padding: 8 0 0 0;");
        Node pairing = qrCell(pairingUrl, "① Open on phone", "Scan to control the bot");
        if (pairing != null) row.getChildren().add(pairing);
        Node install = qrCell(APK_URL, "② Get the app (optional)", "Installs BotPilot for Android");
        if (install != null) row.getChildren().add(install);
        return row;
    }

    /** On-screen QR edge in px. The bitmap is encoded at exactly this size and shown 1:1 (no resample) so the
     *  modules stay crisp — a fractional downscale blurs the edges enough to defeat phone-camera decoding. */
    private static final int QR_PX = 240;

    /** A titled, captioned QR image in its own bordered card, or {@code null} if the code couldn't encode. */
    private static Node qrCell(String text, String title, String caption) {
        Image code = com.botmaker.studio.ui.util.QrCodes.qr(text, QR_PX);
        if (code == null) return null;
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        ImageView iv = new ImageView(code);
        iv.setFitWidth(QR_PX);
        iv.setFitHeight(QR_PX);
        iv.setSmooth(false); // keep module edges sharp if the platform ever scales it
        // White backing so the encoded quiet zone survives against the dark card background/border.
        StackPane qrFrame = new StackPane(iv);
        qrFrame.setStyle("-fx-background-color: white; -fx-padding: 8; -fx-background-radius: 4;");
        Label cap = new Label(caption);
        cap.setWrapText(true);
        cap.setStyle("-fx-text-fill: #8b93a1;");
        VBox cell = new VBox(6, heading, qrFrame, cap);
        cell.setAlignment(Pos.CENTER);
        cell.setMaxWidth(QR_PX + 40);
        cell.setStyle("-fx-padding: 10; -fx-border-color: #3a3f4b; -fx-border-radius: 8; -fx-border-width: 1;");
        return cell;
    }

    /** Opens the Resource Manager dialog. Reused by the Project menu and the block image-picker shortcut. */
    private void openResourceManager() {
        new ResourceManagerDialog(primaryStage, config, eventBus, screenCaptureService).show();
    }

    /**
     * Opens the Project Setup checklist hub — the toolbar/menu entry and the auto-open-on-creation target
     * (called from {@code BotMakerStudio.finishOpen}), so it's public. Reuses the overlay template capture for
     * its optional "Image templates" step.
     */
    public void openProjectSetup() {
        new ProjectSetupDialog(primaryStage, config, projectSettingsService, projectAnalyzer, eventBus,
                this::openOverlayTemplateCapture).show();
    }

    /** Opens the Help ▸ Getting Started guide, whose section jump-buttons reuse the toolbar/menu open actions. */
    private void openGettingStarted() {
        GettingStartedDialog.Actions actions = new GettingStartedDialog.Actions(
                this::openProjectSetup,
                () -> new ManageCaptureTargetsDialog(primaryStage, projectSettingsService).show(),
                () -> new LaunchTargetDialog(primaryStage, config.resourcesRoot(), spec -> { }).show(),
                this::openOverlayTemplateCapture,
                this::openResourceManager,
                this::openRemotePilot,
                onManageLibraries);
        new GettingStartedDialog(primaryStage, actions).show();
    }

    /** Opens the live overlay template-capture over the project's default window target. */
    private void openOverlayTemplateCapture() {
        com.botmaker.studio.ui.app.capture.OverlayTemplateCapture.open(
                primaryStage, config, projectSettingsService, screenCaptureService, eventBus);
    }

    /** Opens the program-shape overlay authoring editor (compact clickable block tree + insertion cursor). */
    private void openOverlayEditor() {
        com.botmaker.studio.ui.app.overlay.ProgramShapeOverlay.open(
                primaryStage, codeEditorService, projectSettingsService, screenCaptureService, false);
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.OpenResourceManagerEvent.class,
                e -> openResourceManager(), true);
        eventBus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, this::handleBlocksUpdate, true);
        eventBus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class, event -> {
            if (outputArea.getText().length() > 10_000) {
                String current = outputArea.getText();
                outputArea.setText("[...Trimmed...]\n" + current.substring(current.length() - 5000) + event.text());
                outputArea.positionCaret(outputArea.getLength());
            } else {
                outputArea.appendText(event.text());
            }
        }, true);
        eventBus.subscribe(CoreApplicationEvents.OutputClearedEvent.class, event -> outputArea.clear(), true);
        eventBus.subscribe(CoreApplicationEvents.StatusMessageEvent.class, event -> statusLabel.setText(event.message()), true);
        eventBus.subscribe(CoreApplicationEvents.DiagnosticsUpdatedEvent.class, event -> {
            diagnosticsManager.processDiagnostics(event.diagnostics());
            updateErrors(diagnosticsManager.getDiagnostics());
            statusLabel.setText(diagnosticsManager.getErrorSummary());
        }, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> selectBottomTab(0), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> selectBottomTab(0), true);
        eventBus.subscribe(CoreApplicationEvents.InputRequestedEvent.class, this::promptForInput, true);
    }

    /** Shows a modal prompt when the running bot blocks on stdin, then sends the entered line to the program. */
    private void promptForInput(CoreApplicationEvents.InputRequestedEvent event) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Bot needs input");
        dialog.setHeaderText("The bot is waiting for input");
        dialog.setContentText(switch (event.type()) {
            case "int" -> "Enter a whole number:";
            case "double" -> "Enter a decimal number:";
            case "boolean" -> "Enter true or false:";
            case "line" -> "Enter some text:";
            default -> "Enter input:";
        });
        dialog.showAndWait().ifPresent(value ->
                eventBus.publish(new CoreApplicationEvents.SendInputEvent(value)));
    }

    private void handleBlocksUpdate(CoreApplicationEvents.UIBlocksUpdatedEvent event) {
        blocksContainer.getChildren().clear();
        if (event.rootBlock() != null) {
            Node rootNode = event.rootBlock().getUINode(codeEditorService);
            rootNode.addEventHandler(BlockEvent.BreakpointToggleEvent.TOGGLE_BREAKPOINT, e ->
                    eventBus.publish(new CoreApplicationEvents.BreakpointToggledEvent(e.getBlock(), e.isEnabled())));
            blocksContainer.getChildren().add(rootNode);
        }
    }

    public Scene createScene() {
        menuBarManager.setOnSelectProject(v -> { if (onSelectProject != null) onSelectProject.accept(null); });

        // --- 1. Top Bar Construction (edit controls left, execution controls right) ---
        // One wrapping bar. Each group stays an indivisible unit (its own HBox/FlowPane), and the outer
        // FlowPane reflows those units onto as many rows as the current width needs — so every button is
        // always visible and readable at any window size. This replaces a hand-rolled reflow that swapped the
        // capture group between the bar's center and a second row at a hardcoded 1080px: a pixel guess
        // unrelated to the buttons' real widths, which both clipped and hid controls.
        HBox editControls = toolbarManager.createEditGroup();
        editControls.setAlignment(Pos.CENTER_LEFT);

        HBox executionControls = toolbarManager.createExecutionGroup();
        HBox rightContainer = new HBox(10, executionControls, buildIdentityCluster());
        rightContainer.setAlignment(Pos.CENTER_RIGHT);

        FlowPane captureControls = toolbarManager.createCaptureGroup();

        FlowPane topBar = new FlowPane(Orientation.HORIZONTAL, 10, 4,
                editControls, captureControls, rightContainer);
        topBar.setPadding(new Insets(6));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("main-toolbar");
        // Free to grow to a second/third row (the old fixed 50px clipped the wrapped rows), and free to
        // shrink: a non-zero min width would let a button's label growing on click ("🐞 Debug: on" → "off")
        // widen the whole stage, which is exactly the "window resizes when I click" symptom.
        topBar.setMinWidth(0);
        topBar.setPrefHeight(Region.USE_COMPUTED_SIZE);
        topBar.setStyle("-fx-border-color: #dcdcdc; -fx-border-width: 0 0 1 0; -fx-background-color: #f4f4f4;");

        VBox toolbarColumn = new VBox(topBar);
        toolbarColumn.setMinWidth(0);

        // --- 2. Left Panel: File Explorer ---
        // Fill the column (no maxWidth cap) so the tree occupies the full width the divider gives it —
        // otherwise a capped explorer leaves dead space to its right when the divider is dragged out.
        // The *drag range* is bounded separately, by clampExplorerWidth() on the split's divider: capping
        // the node here (as this used to) is what reintroduces the dead space.
        VBox fileExplorer = fileExplorerManager.createView();
        fileExplorer.setMinWidth(EXPLORER_MIN_WIDTH);
        fileExplorer.setMaxWidth(Double.MAX_VALUE);
        // Keep the left column's size on window resize (don't let it swallow the canvas).
        SplitPane.setResizableWithParent(fileExplorer, false);

        // --- 3. Center: Code Canvas ---
        blocksContainer = new VBox(10);
        blocksContainer.getStyleClass().add("blocks-canvas");
        blocksContainer.setPadding(new Insets(20));

        // Accept block drags over the whole canvas so the OS "forbidden" cursor doesn't flash over gaps/padding.
        // Real drop zones (separators / block hitboxes) sit on top and consume the event; this only fires over
        // bare canvas, where a release is simply a no-op (no onDragDropped here).
        blocksContainer.setOnDragOver(e -> {
            var db = e.getDragboard();
            if (db.hasContent(BlockDragAndDropManager.ADDABLE_BLOCK_FORMAT)
                    || db.hasContent(BlockDragAndDropManager.EXISTING_BLOCK_FORMAT)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY, javafx.scene.input.TransferMode.MOVE);
            }
        });

        ScrollPane canvasScroll = new ScrollPane(blocksContainer);
        canvasScroll.setFitToWidth(true);
        canvasScroll.setFitToHeight(true);
        canvasScroll.getStyleClass().add("code-scroll-pane");
        blocksScrollPane = canvasScroll;

        // Reader mode: a full-colour, control-free view of someone else's bot. A single banner carries the
        // state; the blocks themselves render without any controls (LockResolver suppresses interaction).
        canvasColumn = new VBox(canvasScroll);
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);
        if (state.isReaderMode()) {
            blocksContainer.getStyleClass().add("reader-mode");
            canvasColumn.getChildren().add(0, createReaderBanner());
        }

        // --- 4. Bottom Panel: Terminal/Errors ---
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.getStyleClass().add("console-area");
        addContextMenu(outputArea);

        // -- Construct Error Panel with Filters --
        VBox errorPanel = createErrorPanel();

        bottomTabPane = new TabPane();
        Tab terminalTab = new Tab("Terminal", outputArea); terminalTab.setClosable(false);
        Tab errorsTab = new Tab("Errors", errorPanel); errorsTab.setClosable(false);
        Tab eventsTab = new Tab("Event Log", eventLogManager.getView()); eventsTab.setClosable(false);

        // VCS tool window — IntelliJ's Commit view docked beside Terminal (VcsPanel, shared with the dialog).
        vcsPanel = new VcsPanel(primaryStage, config.projectName(), config.projectPath(), botPublisher,
                gitHubAuth, gitHubClient, eventBus, openPublishDialog);
        Tab vcsTab = new Tab("VCS", vcsPanel.getView()); vcsTab.setClosable(false);

        bottomTabPane.getTabs().addAll(terminalTab, errorsTab, eventsTab, vcsTab);
        vcsTabIndex = bottomTabPane.getTabs().indexOf(vcsTab);
        // Keep the changed-files tree fresh whenever the user opens the tab.
        bottomTabPane.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == vcsTab && vcsPanel != null) vcsPanel.refresh();
        });

        // --- 5. Layout Assembly ---
        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(canvasColumn, bottomTabPane);
        verticalSplit.setDividerPositions(0.82);

        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().addAll(fileExplorer, verticalSplit);
        mainSplit.setDividerPositions(0.25);
        clampExplorerWidth(mainSplit);

        statusLabel = new Label("Ready");
        statusLabel.setId("status-label");
        statusLabel.setPadding(new Insets(2, 5, 2, 5));

        VBox root = new VBox(menuBarManager.getMenuBar(), toolbarColumn, mainSplit, statusLabel);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        root.getStyleClass().add("light-theme");

        primaryStage.setOnHidden(e -> eventLogManager.shutdown());

        Scene scene = new Scene(root, 1000, 700);

        // Block "state" styling (highlight / error / breakpoint / read-only) via pseudo-classes.
        var blocksCss = UIManager.class.getResource("/css/blocks.css");
        if (blocksCss != null) {
            scene.getStylesheets().add(blocksCss.toExternalForm());
        }

        // Global Key Handlers
        KeyCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCombination pasteCombo = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }
            if (copyCombo.match(event)) {
                eventBus.publish(new CoreApplicationEvents.CopyRequestedEvent());
                event.consume();
            } else if (pasteCombo.match(event)) {
                eventBus.publish(new CoreApplicationEvents.PasteRequestedEvent());
                event.consume();
            }
        });

        return scene;
    }

    // =========================================================================
    // READER / EDITOR MODE + IDENTITY / VCS TOOLBAR CLUSTER
    // =========================================================================

    /** The "Reading — switch to Editor to change" banner shown above the canvas for an installed bot. */
    private HBox createReaderBanner() {
        Label msg = new Label("Reading “" + config.projectName()
                + "”. Switch to Editor mode to make it yours and start changing it.");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button toEditor = new Button("Switch to Editor mode");
        toEditor.setOnAction(e -> switchToEditorMode());
        HBox banner = new HBox(10, msg, spacer, toEditor);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("reader-banner");
        return banner;
    }

    /**
     * "Improve this bot" — flips the installed bot from Reader to Editor mode. Requires no GitHub account (the
     * fork/branch only materializes at PR time): it drops the local opt-in marker, commits the current state
     * locally so there's a restore point, then reloads so every block re-renders with its controls.
     */
    private void switchToEditorMode() {
        try {
            com.botmaker.studio.project.ProjectMode.switchToEditor(config.projectPath());
        } catch (java.io.IOException ex) {
            new Alert(Alert.AlertType.ERROR, "Couldn't switch to Editor mode: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return;
        }
        state.setReaderMode(false);
        // Commit the as-installed state locally (best-effort) so "Editor mode" has a clean starting point.
        new Thread(() -> {
            try {
                new com.botmaker.studio.project.vcs.ProjectVcs(config.projectPath())
                        .commit("Start editing (switched from Reader mode)");
            } catch (Exception ignored) {
                // A missing/again-committed repo is fine; the reload below is what matters.
            }
            javafx.application.Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent()));
        }, "reader-to-editor").start();
    }

    /** The far-right toolbar cluster: a VCS button plus the two BotMaker-wide account buttons. */
    private HBox buildIdentityCluster() {
        Button vcsButton = new Button("⑂ VCS");
        vcsButton.setTooltip(new Tooltip("Show version control (commit, changes, history)"));
        vcsButton.setOnAction(e -> { if (vcsTabIndex >= 0) selectBottomTab(vcsTabIndex); });

        Button gitHub = roundButton("GH", "#24292f", "GitHub account");
        gitHub.setOnAction(e -> showGitHubAccountPopup(gitHub));
        refreshGitHubButton(gitHub);

        Button google = roundButton("G", "#4285F4", "Google account (coming soon)");
        google.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Google sign-in isn't available yet — it's reserved for future Tailscale/Drive features.",
                ButtonType.OK).showAndWait());

        HBox cluster = new HBox(6, vcsButton, gitHub, google);
        cluster.setAlignment(Pos.CENTER_RIGHT);
        return cluster;
    }

    private Button roundButton(String glyph, String bg, String tooltip) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tooltip));
        b.setStyle("-fx-background-radius: 14; -fx-min-width: 28; -fx-min-height: 28; "
                + "-fx-max-width: 28; -fx-max-height: 28; -fx-padding: 0; -fx-font-size: 10px; "
                + "-fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: " + bg + ";");
        return b;
    }

    /** Labels the GitHub round button with the signed-in login initials (or a bare mark when signed out). */
    private void refreshGitHubButton(Button gitHub) {
        if (gitHubAuth != null && gitHubAuth.isAuthenticated()) {
            gitHubAuth.login(gitHubClient).thenAccept(login -> javafx.application.Platform.runLater(() -> {
                if (login != null && !login.isBlank()) {
                    gitHub.setText(login.substring(0, Math.min(2, login.length())).toUpperCase());
                    gitHub.setTooltip(new Tooltip("Signed in to GitHub as " + login));
                }
            }));
        } else {
            gitHub.setText("GH");
            gitHub.setTooltip(new Tooltip("Sign in to GitHub"));
        }
    }

    /** Opens the shared {@link GitHubAccountBar} (device-flow handshake) in a small popup off the round button. */
    private void showGitHubAccountPopup(Button gitHub) {
        Stage popup = new Stage();
        popup.initOwner(primaryStage);
        popup.initModality(javafx.stage.Modality.NONE);
        popup.setTitle("GitHub account");
        GitHubAccountBar bar = new GitHubAccountBar(popup, gitHubAuth, gitHubClient,
                () -> refreshGitHubButton(gitHub));
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(new Scene(box));
        popup.show();
    }

    private VBox createErrorPanel() {
        errorListView = new ListView<>();
        configureErrorList(errorListView);
        addContextMenu(errorListView);
        VBox.setVgrow(errorListView, Priority.ALWAYS);

        // --- Filter Buttons ---
        errorFilterBtn = new ToggleButton("Errors");
        errorFilterBtn.setSelected(true);
        errorFilterBtn.setStyle("-fx-text-fill: #E74C3C; -fx-font-weight: bold;");
        errorFilterBtn.setOnAction(e -> applyErrorFilters());

        warningFilterBtn = new ToggleButton("Warnings");
        warningFilterBtn.setSelected(true);
        warningFilterBtn.setStyle("-fx-text-fill: #F39C12; -fx-font-weight: bold;");
        warningFilterBtn.setOnAction(e -> applyErrorFilters());

        infoFilterBtn = new ToggleButton("Infos/Hints");
        infoFilterBtn.setSelected(true);
        infoFilterBtn.setStyle("-fx-text-fill: #3498DB; -fx-font-weight: bold;");
        infoFilterBtn.setOnAction(e -> applyErrorFilters());

        HBox filterBar = new HBox(10, new Label("Filter: "), errorFilterBtn, warningFilterBtn, infoFilterBtn);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(5));
        filterBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        return new VBox(filterBar, errorListView);
    }

    private void applyErrorFilters() {
        if (allDiagnostics == null) return;

        List<Diagnostic> filtered = allDiagnostics.stream()
                .filter(d -> {
                    DiagnosticSeverity severity = d.getSeverity();
                    if (severity == DiagnosticSeverity.Error) return errorFilterBtn.isSelected();
                    if (severity == DiagnosticSeverity.Warning) return warningFilterBtn.isSelected();
                    if (severity == DiagnosticSeverity.Information || severity == DiagnosticSeverity.Hint) return infoFilterBtn.isSelected();
                    return true;
                })
                .collect(Collectors.toList());

        errorListView.getItems().setAll(filtered);
    }

    private void configureErrorList(ListView<Diagnostic> lv) {
        lv.setPlaceholder(new Label("No issues found."));
        lv.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic diagnostic, boolean empty) {
                super.updateItem(diagnostic, empty);

                if (empty || diagnostic == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    setOnMouseClicked(null);
                } else {
                    String message = ErrorTranslator.getShortSummary(diagnostic);
                    int line = diagnostic.getRange().getStart().getLine() + 1;

                    // --- NEW: Extract Filename from Data Field ---
                    String filename = "";
                    if (diagnostic.getData() instanceof String) {
                        String uri = (String) diagnostic.getData();
                        try {
                            // Try to parse as URI to get clean filename (e.g. Main.java)
                            java.nio.file.Path p = java.nio.file.Path.of(new java.net.URI(uri));
                            filename = "[" + p.getFileName().toString() + "] ";
                        } catch (Exception e) {
                            // Fallback for non-standard URIs
                            if (uri.contains("/")) {
                                filename = "[" + uri.substring(uri.lastIndexOf('/') + 1) + "] ";
                            } else {
                                filename = "[" + uri + "] ";
                            }
                        }
                    }
                    // ---------------------------------------------

                    String icon = "";
                    String colorStyle = "";
                    String iconColorStyle = "";

                    if (diagnostic.getSeverity() == DiagnosticSeverity.Error) {
                        icon = "❌";
                        colorStyle = "-fx-text-fill: #C0392B;";
                        iconColorStyle = "-fx-text-fill: #E74C3C;";
                    } else if (diagnostic.getSeverity() == DiagnosticSeverity.Warning) {
                        icon = "⚠️";
                        colorStyle = "-fx-text-fill: #D35400;";
                        iconColorStyle = "-fx-text-fill: #F39C12;";
                    } else {
                        icon = "ℹ️";
                        colorStyle = "-fx-text-fill: #2980B9;";
                        iconColorStyle = "-fx-text-fill: #3498DB;";
                    }

                    Label iconLabel = new Label(icon);
                    iconLabel.setStyle(iconColorStyle + "-fx-font-size: 14px; -fx-padding: 0 8 0 0;");

                    // Add filename to the text
                    setText(String.format("%sLine %d: %s", filename, line, message));
                    setStyle(colorStyle + "-fx-font-family: 'Segoe UI', sans-serif; -fx-font-weight: normal;");
                    setGraphic(iconLabel);

                    setOnMouseClicked(event -> {
                        if (event.getClickCount() >= 1) {
                            diagnosticsManager.findBlockForDiagnostic(diagnostic).ifPresent(UIManager.this::scrollToBlock);
                        }
                    });
                }
            }
        });
    }

    /**
     * Brings {@code block} into view when its error is clicked in the Errors panel: highlights it (reusing the
     * debugger's {@link CoreApplicationEvents.BlockHighlightEvent} path) and scrolls the canvas so the block is
     * visible. Runs the scroll on the next pulse so the node's layout bounds are current.
     */
    private void scrollToBlock(com.botmaker.studio.core.CodeBlock block) {
        if (block == null) return;
        com.botmaker.studio.core.CodeBlock target = block.getHighlightTarget();
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
        Node node = target != null ? target.getUINode() : null;
        if (node == null || blocksScrollPane == null || blocksContainer == null) return;
        javafx.application.Platform.runLater(() -> {
            javafx.geometry.Bounds nodeInContent =
                    blocksContainer.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double contentH = blocksContainer.getBoundsInLocal().getHeight();
            double viewportH = blocksScrollPane.getViewportBounds().getHeight();
            if (contentH > viewportH) {
                double vvalue = (nodeInContent.getMinY() - 20) / (contentH - viewportH);
                blocksScrollPane.setVvalue(Math.max(0, Math.min(1, vvalue)));
            }
            node.requestFocus();
        });
    }

    private void addContextMenu(Control control) {
        ContextMenu cm = new ContextMenu();
        if (control instanceof TextArea) {
            TextArea ta = (TextArea) control;
            MenuItem copy = new MenuItem("Copy");
            copy.setOnAction(e -> ta.copy());
            MenuItem clear = new MenuItem("Clear");
            clear.setOnAction(e -> ta.clear());
            cm.getItems().addAll(copy, new SeparatorMenuItem(), clear);
            ta.setContextMenu(cm);
        } else if (control instanceof ListView) {
            ListView<?> lv = (ListView<?>) control;
            MenuItem copy = new MenuItem("Copy Selection");
            copy.setOnAction(e -> {
                Object selected = lv.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();

                    // MODIFIED: Copy only message if it's a Diagnostic object
                    String textToCopy;
                    if (selected instanceof Diagnostic) {
                        textToCopy = ((Diagnostic) selected).getMessage();
                    } else {
                        textToCopy = selected.toString();
                    }

                    content.putString(textToCopy);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                }
            });
            cm.getItems().add(copy);
            lv.setContextMenu(cm);
        }
    }

    private void updateErrors(List<Diagnostic> diagnostics) {
        this.allDiagnostics = (diagnostics != null) ? new ArrayList<>(diagnostics) : new ArrayList<>();
        applyErrorFilters();
        updateFilterButtonCounts();

        boolean hasErrors = allDiagnostics.stream().anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
        if (hasErrors) {
            selectBottomTab(1);
        }
    }

    private void updateFilterButtonCounts() {
        long errCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Error).count();
        long warnCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Warning).count();
        long infoCount = allDiagnostics.stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Information || d.getSeverity() == DiagnosticSeverity.Hint).count();

        errorFilterBtn.setText(String.format("Errors (%d)", errCount));
        warningFilterBtn.setText(String.format("Warnings (%d)", warnCount));
        infoFilterBtn.setText(String.format("Infos (%d)", infoCount));
    }

    private void selectBottomTab(int index) {
        if (bottomTabPane != null && index < bottomTabPane.getTabs().size()) {
            bottomTabPane.getSelectionModel().select(index);
        }
    }

    public void setOnSelectProject(Consumer<Void> callback) { this.onSelectProject = callback; }
}