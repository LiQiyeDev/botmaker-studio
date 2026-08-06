package com.botmaker.studio.ui.app;

import com.botmaker.studio.runtime.CodeExecutionService;
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
import com.botmaker.studio.ui.render.theme.BlockTheme;
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
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
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

    /** Share of the toolbar's width the run cluster may occupy before it wraps — see {@code createScene()}. */
    private static final double EXEC_WIDTH_SHARE = 0.42;
    /** Floor under that share, so a very narrow window wraps the cluster rather than stacking it one per row. */
    private static final double EXEC_MIN_WRAP_PX = 170;

    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final DiagnosticsManager diagnosticsManager;
    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ProjectState state;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectSettingsService projectSettingsService;
    /** Held for the overlay editor, which needs the activity list to know where to insert. */
    private final ActivityService activityService;
    private final ProjectAnalyzer projectAnalyzer;

    private final ToolbarManager toolbarManager;
    private final EventLogManager eventLogManager;
    private final MenuBarManager menuBarManager;
    private final com.botmaker.studio.runtime.CodeExecutionService codeExecutionService;
    /** Remote Pilot in full: the server, the private-display launcher, and every dialog they put on screen. */
    private final com.botmaker.studio.ui.app.pilot.RemotePilotUi remotePilot;
    private final FileExplorerManager fileExplorerManager;

    // Theme management
    private Scene scene;
    private Parent root;
    /** Kept so {@link #dispose()} can drop it from {@link BlockTheme}'s <b>static</b> listener list — otherwise
     *  every project switch leaves a lambda holding the previous window's whole scene graph alive. */
    private final Consumer<BlockTheme.ThemeType> themeListener;
    /** Built with the scene; holds the second of this window's two theme listeners. */
    private IdentityCluster identityCluster;

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
                     CodeExecutionService codeExecutionService) {
        this.eventBus = eventBus;
        this.codeEditorService = codeEditorService;
        this.codeExecutionService = codeExecutionService;
        this.diagnosticsManager = diagnosticsManager;
        this.primaryStage = primaryStage;
        this.config = config;
        this.state = state;
        this.projectAnalyzer = projectAnalyzer;
        this.activityService = activityService;

        // Editor settings (capture targets + default). Stateless over (config, state, eventBus); the
        // capture service honors the default target so pickers stop re-asking which screen to use.
        this.projectSettingsService = new ProjectSettingsService(config, state, eventBus);
        this.screenCaptureService = new ScreenCaptureService(projectSettingsService);
        this.remotePilot = new com.botmaker.studio.ui.app.pilot.RemotePilotUi(
                primaryStage, eventBus, config, projectSettingsService, codeExecutionService);

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
                new ManageCaptureTargetsDialog(primaryStage, projectSettingsService, config.resourcesRoot()).show());
        this.toolbarManager.setResourcesDir(config.resourcesRoot());
        this.toolbarManager.setLaunchTarget(
                com.botmaker.studio.project.ProjectCreator.readLaunchTarget(config.resourcesRoot()));
        this.toolbarManager.setOnManageLaunchTarget(() -> new LaunchTargetDialog(
                primaryStage, config, this.toolbarManager::setLaunchTarget).show());
        this.toolbarManager.setOnToggleDebugOutput(
                com.botmaker.studio.project.ProjectCreator.readDebug(config.resourcesRoot()),
                on -> {
                    try {
                        com.botmaker.studio.project.ProjectCreator.writeDebug(config.resourcesRoot(), on);
                    } catch (java.io.IOException ex) {
                        System.err.println("Failed to save debug setting: " + ex.getMessage());
                    }
                });
        // The click/vision tuning is a project setting the SDK reads before the first click. Older projects
        // carry it as a generated BotSettings.java (or, older still, an inline ClickConfig call in main) —
        // migrate them here, on open, which is the one moment we know the project and haven't yet built the
        // file explorer that would go on listing a file we are about to delete.
        try {
            String migratedMain = com.botmaker.studio.project.BotSettings.migrate(config);
            if (migratedMain != null) refreshCachedSource(config.mainSourceFile(), migratedMain);
        } catch (java.io.IOException ex) {
            System.err.println("Could not move this project's input settings into its project properties: "
                    + ex.getMessage());
        }
        // Same moment, same reason: a project created before GameLoop.java and Startup.java were retired binds
        // a 3-arg Bot.start the SDK no longer has, so it doesn't compile until this runs.
        try {
            String migratedMain = com.botmaker.studio.project.ScaffoldMigration.migrate(config);
            if (migratedMain != null) refreshCachedSource(config.mainSourceFile(), migratedMain);
        } catch (java.io.IOException ex) {
            System.err.println("Could not update this project's entry point to the current scaffold: "
                    + ex.getMessage());
        }
        this.toolbarManager.setOnConfigureInput(() -> new BotSettingsDialog(primaryStage, config, null).show());
        this.toolbarManager.setOnEnableRemotePilot(remotePilot::open);
        this.toolbarManager.setOnCaptureTemplates(this::openOverlayTemplateCapture);
        this.toolbarManager.setOnOverlayEditor(this::openOverlayEditor);
        this.toolbarManager.setOnRecordMacro(this::openOverlayEditorRecording);
        this.toolbarManager.setOnAccessResources(this::openResourceManager);
        this.gitHubClient = new GitHubClient();
        this.gitHubAuth = new GitHubAuth();
        this.gallery = new GitHubGallery(gitHubClient, gitHubAuth);
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
        this.menuBarManager.setOnEnableRemotePilot(remotePilot::open);
        this.fileExplorerManager = new FileExplorerManager(config, codeEditorService, state, activityService, eventBus);

        // Initialize theme system and set up theme change listener
        BlockTheme.initialize();
        this.themeListener = themeType -> applyThemeToScene();
        BlockTheme.addThemeChangeListener(themeListener);

        setupEventHandlers();
    }

    /**
     * Releases everything this window acquired from the OS and from static state, so the project it was built
     * for can be closed. Called by {@code BotMakerStudio} on project open (for the outgoing window), on the
     * switch back to the project selector, and on shutdown.
     *
     * <p>A new {@code UIManager} is built for every open <em>and every reload</em>, so without this each VCS
     * rollback or Reader→Editor switch left behind a bound pilot port, a live nested display with the game
     * still in it, and two theme listeners pinning the dead scene graph. Idempotent.
     */
    public void dispose() {
        remotePilot.close();
        BlockTheme.removeThemeChangeListener(themeListener);
        if (identityCluster != null) {
            identityCluster.dispose();
            identityCluster = null;
        }
        eventLogManager.shutdown();
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

    /**
     * Tells the editor that {@code file} was rewritten on disk behind its back, e.g. by the one-time
     * {@code BotSettings} migration.
     *
     * <p>The editor caches file contents in memory, so a disk-only write would be invisible — and would be
     * overwritten by the next edit that flushes the stale copy. Update the cached copy, and re-render when it
     * happens to be the file on screen. A file the editor hasn't loaded needs nothing.
     */
    private void refreshCachedSource(java.nio.file.Path file, String updated) {
        if (file == null || updated == null) return;
        state.getAllFiles().stream()
                .filter(f -> f.getPath().equals(file))
                .findFirst()
                .ifPresent(f -> {
                    String previous = f.getContent();
                    f.setContent(updated);
                    var active = state.getActiveFile();
                    if (active != null && active.getPath().equals(file)) {
                        eventBus.publish(new CoreApplicationEvents.CodeUpdatedEvent(updated, previous));
                    }
                });
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
                this::openOverlayTemplateCapture, toolbarManager::setLaunchTarget).show();
    }

    /** Opens the Help ▸ Getting Started guide, whose section jump-buttons reuse the toolbar/menu open actions. */
    private void openGettingStarted() {
        GettingStartedDialog.Actions actions = new GettingStartedDialog.Actions(
                this::openProjectSetup,
                () -> new ManageCaptureTargetsDialog(primaryStage, projectSettingsService, config.resourcesRoot()).show(),
                () -> new LaunchTargetDialog(
                        primaryStage, config, toolbarManager::setLaunchTarget).show(),
                this::openOverlayTemplateCapture,
                this::openResourceManager,
                remotePilot::open,
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
        openOverlayEditor(false);
    }

    /**
     * The same overlay, opened straight into a recording session — the toolbar's ⏺ Record. It is the entry
     * point that makes the overlay's {@code startRecording} flag live again: the standalone Record Macro
     * button was dropped when the recorder was merged into the HUD, and nothing has passed {@code true} since.
     */
    private void openOverlayEditorRecording() {
        openOverlayEditor(true);
    }

    private void openOverlayEditor(boolean startRecording) {
        com.botmaker.studio.ui.app.overlay.ProgramShapeOverlay.open(
                primaryStage, codeEditorService, projectSettingsService, screenCaptureService, activityService,
                remotePilot::liveSessionWindow, startRecording, this::chooseLaunchTargetThen);
    }

    /**
     * Shows the Launch Target dialog and runs {@code retry} once it closes — the recovery the overlay editor
     * takes when there is nothing to draw over (no private session up, no default capture target). The dialog
     * is where the game is both chosen and started ("▶ Launch now"), so it is the one place that can turn "no
     * window" into a window.
     */
    private void chooseLaunchTargetThen(Runnable retry) {
        new LaunchTargetDialog(primaryStage, config, toolbarManager::setLaunchTarget).show(retry);
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

        // --- 1. Top Bar Construction (edit controls left, project actions centered, run controls right) ---
        // A BorderPane, not a FlowPane. Only the *center* group wraps; left and right are pinned to their
        // edges and never move when it does. A FlowPane cannot centre its middle child — it packs all three
        // units from the leading edge — which is why the project buttons used to sit left-aligned and why the
        // run cluster drifted with them. Each group is still an indivisible unit (its own HBox/FlowPane).
        HBox editControls = toolbarManager.createEditGroup();
        editControls.setAlignment(Pos.CENTER_LEFT);

        FlowPane executionControls = toolbarManager.createExecutionGroup();
        this.identityCluster = new IdentityCluster(primaryStage, gitHubAuth, gitHubClient,
                () -> { if (vcsTabIndex >= 0) selectBottomTab(vcsTabIndex); });
        HBox rightContainer = new HBox(10, executionControls, identityCluster.node());
        rightContainer.setAlignment(Pos.CENTER_RIGHT);
        rightContainer.setMinWidth(0);
        // Breathing room against the window edge, mirroring the padding createEditGroup() applies on its side.
        rightContainer.setPadding(new Insets(0, 10, 0, 0));

        FlowPane captureControls = toolbarManager.createCaptureGroup();

        BorderPane topBar = new BorderPane();
        topBar.setLeft(editControls);
        topBar.setCenter(captureControls);
        topBar.setRight(rightContainer);
        BorderPane.setAlignment(editControls, Pos.CENTER_LEFT);
        BorderPane.setAlignment(rightContainer, Pos.CENTER_RIGHT);
        topBar.setPadding(new Insets(6));
        topBar.getStyleClass().add("main-toolbar");
        // Width is free to shrink; height is *not*, and the asymmetry is the whole point. JavaFX reads the
        // **scene root's** minimum to decide the Stage's, so root.setMinHeight(0) below is what keeps a label
        // growing on click (or a new wrap row) from pushing the window outwards — clamping this bar as well
        // buys nothing there and costs everything here: the root VBox's shrink pass treats every child as a
        // candidate regardless of Vgrow, splitting the deficit evenly and clamping each at its own min. The
        // canvas ScrollPane's preferred height tracks the block list, so on any real bot the root is
        // permanently over-subscribed — a bar with min height 0 then takes its share down to nothing, and
        // since JavaFX doesn't clip a Region, its buttons paint upward over the menu bar. Pinning min to pref
        // makes the bar refuse the shrink; mainSplit (min 0, Vgrow.ALWAYS) absorbs it instead, as it should.
        topBar.setMinWidth(0);
        // Why the run cluster needs telling how wide it may be: BorderPane lays its right child out at that
        // child's *preferred* width, and a FlowPane's preferred width is "whatever fits on one row" unless it is
        // given a wrap length. Left alone it would therefore behave exactly like the HBox it replaced — pinned to
        // one line, with the centre group absorbing every pixel the window loses. Tying the wrap length to a share
        // of the bar means it stays one row while there is room for one (the share exceeds the cluster's natural
        // width on any normal window) and starts wrapping only once the bar is genuinely tight — which is the
        // point at which the centre group would otherwise have been wrapping alone.
        executionControls.prefWrapLengthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(EXEC_MIN_WRAP_PX, topBar.getWidth() * EXEC_WIDTH_SHARE), topBar.widthProperty()));
        topBar.setPrefHeight(Region.USE_COMPUTED_SIZE);
        topBar.setMinHeight(Region.USE_PREF_SIZE);
        topBar.setStyle("-fx-border-color: #dcdcdc; -fx-border-width: 0 0 1 0;");

        VBox toolbarColumn = new VBox(topBar);
        toolbarColumn.setMinWidth(0);
        toolbarColumn.setMinHeight(Region.USE_PREF_SIZE);

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
        // Same reason as the toolbar's clamps: a SplitPane's computed minimum is the sum of its items', and
        // the file explorer carries a real EXPLORER_MIN_WIDTH floor. Left unclamped that floor reaches the
        // Stage and becomes a minimum window size the user can't drag below.
        mainSplit.setMinWidth(0);
        mainSplit.setMinHeight(0);

        statusLabel = new Label("Ready");
        statusLabel.setId("status-label");
        statusLabel.setPadding(new Insets(2, 5, 2, 5));

        VBox root = new VBox(menuBarManager.getMenuBar(), toolbarColumn, mainSplit, statusLabel);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        // Initialize with the current theme - add the appropriate theme class
        applyThemeToScene(root);

        // Store references for theme switching
        this.root = root;

        // The only clamp the Stage actually reads: JavaFX derives a window's minimum size from the scene
        // root's computed minimum. Clamping here is therefore both necessary and sufficient — the children's
        // own honest minimums never reach the Stage through it, which is why the toolbar above keeps its.
        root.setMinWidth(0);
        root.setMinHeight(0);

        // The window going away is one more way this project ends (e.g. the window manager closes it without
        // routing through shutdown()) — release the same things dispose() does, idempotently.
        primaryStage.setOnHidden(e -> dispose());

        Scene scene = new Scene(root, 1000, 700);
        this.scene = scene;

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
        // Daemon: a git commit that hangs must not keep the JVM alive after the user closes the window.
        Thread commit = new Thread(() -> {
            try {
                new com.botmaker.studio.project.vcs.ProjectVcs(config.projectPath())
                        .commit("Start editing (switched from Reader mode)");
            } catch (Exception ignored) {
                // A missing/again-committed repo is fine; the reload below is what matters.
            }
            javafx.application.Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent()));
        }, "reader-to-editor");
        commit.setDaemon(true);
        commit.start();
    }

    /** Applies the current theme CSS class to the specified root node. */
    private void applyThemeToScene(Parent rootNode) {
        if (rootNode == null) return;

        BlockTheme.ThemeType current = BlockTheme.getCurrentThemeType();

        // Remove all theme classes
        rootNode.getStyleClass().removeAll("default-theme", "dark-theme", "black-theme", "high-contrast-theme", "light-theme");

        // Add the current theme class
        switch (current) {
            case DEFAULT -> rootNode.getStyleClass().add("default-theme");
            case DARK -> rootNode.getStyleClass().add("dark-theme");
            case BLACK -> rootNode.getStyleClass().add("black-theme");
            case HIGH_CONTRAST -> rootNode.getStyleClass().add("high-contrast-theme");
        }
    }

    /** Applies the current theme CSS class to the stored scene root. */
    private void applyThemeToScene() {
        applyThemeToScene(root);
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
