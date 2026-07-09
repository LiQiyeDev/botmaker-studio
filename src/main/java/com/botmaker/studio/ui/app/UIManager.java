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

    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final DiagnosticsManager diagnosticsManager;
    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectSettingsService projectSettingsService;

    private final ToolbarManager toolbarManager;
    private final EventLogManager eventLogManager;
    private final MenuBarManager menuBarManager;
    private com.botmaker.studio.services.debug.TelemetryDashboardServer dashboardServer;
    private final com.botmaker.studio.runtime.CodeExecutionService codeExecutionService;
    private com.botmaker.studio.services.pilot.PilotServer pilotServer;
    private final FileExplorerManager fileExplorerManager;
    private final WindowPreviewManager windowPreviewManager;

    private VBox blocksContainer;
    private Label statusLabel;
    private TextArea outputArea;
    private ListView<Diagnostic> errorListView;
    private TabPane bottomTabPane;
    private Consumer<Void> onSelectProject;

    // --- NEW: Filter State ---
    private List<Diagnostic> allDiagnostics = new ArrayList<>();
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
        this.menuBarManager.setOnManageLibraries(() ->
                new ManageLibrariesDialog(primaryStage, libraryService, mavenCentralSearch, jitPackSearch).show());
        this.menuBarManager.setOnManageImports(() ->
                new ManageImportsDialog(primaryStage, codeEditorService).show());
        this.menuBarManager.setOnManageActivities(() ->
                new ManageActivitiesDialog(primaryStage, activityService).show());
        this.menuBarManager.setOnSetActivityValues(() ->
                new SetActivityValuesDialog(primaryStage, activityService).show());
        this.menuBarManager.setOnManageResources(this::openResourceManager);
        this.toolbarManager.setOnManageCaptureTargets(() ->
                new ManageCaptureTargetsDialog(primaryStage, projectSettingsService).show());
        this.toolbarManager.setOnOpenDebugDashboard(this::openDebugDashboard);
        GitHubClient gitHubClient = new GitHubClient();
        GitHubGallery gallery = new GitHubGallery(gitHubClient);
        BotInstaller botInstaller = new BotInstaller(gitHubClient, gallery);
        this.menuBarManager.setOnBrowseGallery(() ->
                new GalleryDialog(primaryStage, gallery, botInstaller).show());
        GitHubAuth gitHubAuth = new GitHubAuth();
        BotPublisher botPublisher = new BotPublisher(gitHubClient, gitHubAuth);
        this.menuBarManager.setOnPublishGallery(() ->
                new PublishDialog(primaryStage, gitHubAuth, gitHubClient, gallery, botPublisher,
                        config.projectName(), config.projectPath()).show());
        this.menuBarManager.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));
        this.menuBarManager.setOnOpenDebugDashboard(this::openDebugDashboard);
        this.menuBarManager.setOnEnableRemotePilot(this::openRemotePilot);
        this.fileExplorerManager = new FileExplorerManager(config, codeEditorService, state);
        this.windowPreviewManager = new WindowPreviewManager(eventBus, config, state);

        setupEventHandlers();
    }

    /** Starts (once) the local telemetry debug dashboard server and opens it in the browser. */
    private void openDebugDashboard() {
        try {
            if (dashboardServer == null) {
                dashboardServer = new com.botmaker.studio.services.debug.TelemetryDashboardServer(
                        eventBus, projectSettingsService);
            }
            String url = dashboardServer.startAndGetUrl();
            com.botmaker.studio.util.BrowserLauncher.open(url);
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug dashboard at " + url));
        } catch (Exception e) {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                    "Could not start debug dashboard: " + e.getMessage()));
        }
    }

    /** Stable "latest release" permalink the install-app QR points at; the botmaker-pilot CI attaches this. */
    private static final String APK_URL =
            "https://github.com/LiQiyeDev/botmaker-pilot/releases/latest/download/botpilot.apk";

    /** How the pilot ended up exposed — drives the dialog header, QR URL, and warning. */
    private enum PilotMode { FUNNEL_HTTPS, TAILNET_DIRECT, ALL_INTERFACES }

    /** Result of {@link #startRemotePilot()} — enough to render the pairing dialog on the FX thread. */
    private record PilotOutcome(String url, String token, PilotMode mode, String funnelError) {}

    /**
     * Starts (once) the remote BotPilot server and shows a pairing dialog. Preferred path is <b>Tailscale
     * Funnel</b>: bind the server to loopback and let Tailscale front it as public HTTPS
     * ({@code https://<machine>.ts.net}) with a valid cert, so the phone needs no Tailscale/VPN. If Funnel
     * isn't available (or isn't enabled in the tailnet ACL) it falls back to a direct bind — the Tailscale
     * interface when the tunnel is up, else all interfaces with a warning — over plain HTTP.
     *
     * <p>The bring-up runs the {@code tailscale} CLI (which can block for seconds), so it happens on a
     * background thread; only the resulting dialog is marshalled back to the FX thread. Doing it inline would
     * freeze (and, if the CLI hangs, appear to crash) the UI.
     */
    private void openRemotePilot() {
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
                o = startRemotePilot();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final PilotOutcome outcome = o;
            final String err = error;
            javafx.application.Platform.runLater(() -> {
                progress.setResult(ButtonType.CANCEL); // let close() dismiss a button-less alert
                progress.close();
                if (outcome != null) {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                            (outcome.mode() == PilotMode.FUNNEL_HTTPS ? "Remote Pilot (HTTPS) at " : "Remote Pilot at ")
                                    + outcome.url()));
                    showRemotePilotDialog(outcome.url(), outcome.token(), outcome.mode(), outcome.funnelError());
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

    /** Blocking bring-up (Tailscale CLI + server bind); must run off the FX thread. */
    private PilotOutcome startRemotePilot() {
        var funnel = new com.botmaker.studio.services.pilot.TailscaleFunnelService();
        String funnelError = null;
        if (funnel.isAvailable() && funnel.dnsName().isPresent()) {
            var ep = pilotServer.start("127.0.0.1"); // loopback — only Funnel fronts it
            var result = funnel.enable(ep.port());
            if (result.ok()) {
                var pub = pilotServer.attachFunnel(funnel, result.publicBase());
                return new PilotOutcome(pub.url(), pub.token(), PilotMode.FUNNEL_HTTPS, null);
            }
            // Funnel present but couldn't be enabled (e.g. not granted in the tailnet ACL): tear the
            // loopback server down and fall through to a directly-bound one, surfacing the reason.
            funnelError = result.error();
            pilotServer.close();
        }

        String tailscale = com.botmaker.studio.services.pilot.PilotServer.detectTailscaleHost();
        boolean allInterfaces = tailscale == null;
        var endpoint = pilotServer.start(allInterfaces ? "0.0.0.0" : tailscale);

        String displayHost = allInterfaces ? hostForUrl() : tailscale;
        String url = "http://" + displayHost + ":" + endpoint.port() + "/?token=" + endpoint.token();
        return new PilotOutcome(url, endpoint.token(),
                allInterfaces ? PilotMode.ALL_INTERFACES : PilotMode.TAILNET_DIRECT, funnelError);
    }

    /** Best-effort local IPv4 for the displayed URL when binding all interfaces (no Tailscale). */
    private static String hostForUrl() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void showRemotePilotDialog(String url, String token, PilotMode mode, String funnelError) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("Remote Pilot");
        alert.setHeaderText(switch (mode) {
            case FUNNEL_HTTPS -> "Remote Pilot is live over HTTPS (Tailscale Funnel).";
            case TAILNET_DIRECT -> "Remote Pilot is running on your tailnet.";
            case ALL_INTERFACES -> "Tailscale not detected — bound to ALL interfaces.";
        });

        // Editable so the URL is always selectable for a manual copy, even if the clipboard write below is
        // swallowed by the window system (e.g. some Wayland setups).
        TextField urlField = new TextField(url);
        urlField.setPrefColumnCount(44);
        Button copy = new Button("Copy URL");
        copy.setOnAction(e -> {
            urlField.requestFocus();
            urlField.selectAll();
            copyToClipboard(url);
            copy.setText("Copied ✓");
        });
        Button open = new Button("Open in browser");
        open.setOnAction(e -> com.botmaker.studio.util.BrowserLauncher.open(url));

        VBox content = new VBox(8,
                new Label(mode == PilotMode.FUNNEL_HTTPS
                        ? "Scan the left QR (or open this URL) on your phone — any browser, no VPN needed:"
                        : "Open this URL in a browser, or enter host/port + token in the BotPilot app:"),
                urlField,
                new Label("Token: " + token),
                new HBox(8, copy, open),
                qrRow(url));
        content.setStyle("-fx-padding: 4;");

        if (funnelError != null) {
            Label fe = new Label("Tailscale Funnel unavailable (" + funnelError.trim()
                    + ") — using a direct connection instead.");
            fe.setWrapText(true);
            fe.setStyle("-fx-text-fill: #e67e22;");
            content.getChildren().add(fe);
        }
        if (mode == PilotMode.ALL_INTERFACES) {
            Label warn = new Label("⚠ Anyone who can reach this machine's IP can view/control the bot with "
                    + "this token. Prefer connecting over Tailscale.");
            warn.setWrapText(true);
            warn.setStyle("-fx-text-fill: #e67e22;");
            content.getChildren().add(warn);
        }
        alert.getDialogPane().setContent(content);
        alert.setResizable(true); // let the user grow it if the QR codes crowd the buttons on small screens
        alert.show();
    }

    private static void copyToClipboard(String text) {
        var cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    /** Side-by-side QR codes: left pairs the pilot URL, right downloads the Android APK. */
    private static Node qrRow(String pairingUrl) {
        HBox row = new HBox(24);
        row.setAlignment(Pos.CENTER_LEFT);
        Node pairing = qrCell(pairingUrl, "Scan to open on phone");
        if (pairing != null) row.getChildren().add(pairing);
        Node install = qrCell(APK_URL, "Or install the Android app");
        if (install != null) row.getChildren().add(install);
        return row;
    }

    /** A captioned QR image, or {@code null} if the code couldn't be encoded. */
    private static Node qrCell(String text, String caption) {
        Image code = com.botmaker.studio.ui.util.QrCodes.qr(text, 200);
        if (code == null) return null;
        ImageView iv = new ImageView(code);
        iv.setFitWidth(160);
        iv.setFitHeight(160);
        Label cap = new Label(caption);
        cap.setWrapText(true);
        VBox cell = new VBox(4, iv, cap);
        cell.setAlignment(Pos.CENTER);
        return cell;
    }

    /** Opens the Resource Manager dialog. Reused by the Project menu and the block image-picker shortcut. */
    private void openResourceManager() {
        new ResourceManagerDialog(primaryStage, config, eventBus, screenCaptureService).show();
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
        HBox editControls = toolbarManager.createEditGroup();
        HBox leftContainer = new HBox(editControls);
        leftContainer.setAlignment(Pos.CENTER_LEFT);

        HBox executionControls = toolbarManager.createExecutionGroup();
        HBox rightContainer = new HBox(executionControls);
        rightContainer.setAlignment(Pos.CENTER_RIGHT);

        HBox captureControls = toolbarManager.createCaptureGroup();
        HBox centerContainer = new HBox(captureControls);
        centerContainer.setAlignment(Pos.CENTER);

        BorderPane topBar = new BorderPane();
        topBar.setPadding(new Insets(6));
        topBar.setLeft(leftContainer);
        topBar.setCenter(centerContainer);
        topBar.setRight(rightContainer);
        topBar.getStyleClass().add("main-toolbar");
        topBar.setMinHeight(50);
        topBar.setPrefHeight(50);
        topBar.setMaxHeight(50);
        topBar.setStyle("-fx-border-color: #dcdcdc; -fx-border-width: 0 0 1 0; -fx-background-color: #f4f4f4;");

        // --- 2. Left Panel: File Explorer (top) + live Window Preview (bottom) ---
        // Fill the column (no maxWidth cap) so the tree occupies the full width the divider gives it —
        // otherwise a capped explorer leaves dead space to its right when the divider is dragged out.
        VBox fileExplorer = fileExplorerManager.createView();
        fileExplorer.setMinWidth(150);
        fileExplorer.setMaxWidth(Double.MAX_VALUE);

        // Preview sits directly under the explorer and to the left of the terminal tabs; collapsible.
        Node previewPanel = windowPreviewManager.getView();
        SplitPane leftColumn = new SplitPane();
        leftColumn.setOrientation(Orientation.VERTICAL);
        leftColumn.getItems().addAll(fileExplorer, previewPanel);
        leftColumn.setDividerPositions(0.62);
        // Keep the left column's size on window resize (don't let it swallow the canvas).
        SplitPane.setResizableWithParent(leftColumn, false);

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
        bottomTabPane.getTabs().addAll(terminalTab, errorsTab, eventsTab);

        // --- 5. Layout Assembly ---
        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(canvasScroll, bottomTabPane);
        verticalSplit.setDividerPositions(0.82);

        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().addAll(leftColumn, verticalSplit);
        mainSplit.setDividerPositions(0.25);

        statusLabel = new Label("Ready");
        statusLabel.setId("status-label");
        statusLabel.setPadding(new Insets(2, 5, 2, 5));

        VBox root = new VBox(menuBarManager.getMenuBar(), topBar, mainSplit, statusLabel);
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
                            diagnosticsManager.findBlockForDiagnostic(diagnostic).ifPresent(block -> {
                                Node uiNode = block.getUINode();
                                if (uiNode != null) uiNode.requestFocus();
                            });
                        }
                    });
                }
            }
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