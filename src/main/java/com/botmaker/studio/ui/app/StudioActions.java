package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.docs.StudioAction;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.SdkUpgradeService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.app.dev.PickerGalleryWindow;
import com.botmaker.studio.ui.app.overlay.ProgramShapeOverlay;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;

/**
 * Every top-level action the shell offers, built once and wired into the menu bar and the toolbar.
 *
 * <p>This used to be ~120 lines of {@code setOnX} calls interleaved with service construction in
 * {@code UIManager}'s constructor, which made "which button opens what" unreadable and hid the ordering
 * constraints between them. Here the actions are named methods and {@link #wire} is the one table.
 *
 * <p>It also owns the GitHub/sharing services, because they exist only to back these actions — the scene
 * reaches them through {@link #gitHubAuth()} and friends rather than through fields of its own.
 */
final class StudioActions {

    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final ProjectSettingsService projectSettingsService;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectAnalyzer projectAnalyzer;
    private final ActivityService activityService;
    private final LibraryService libraryService;
    private final MenuBarManager menuBar;
    private final ToolbarManager toolbar;
    private final Runnable recoverProjectFiles;

    private final MavenCentralSearch mavenCentralSearch = new MavenCentralSearch();
    private final JitPackSearch jitPackSearch = new JitPackSearch();

    private final GitHubClient gitHubClient = new GitHubClient();
    private final GitHubAuth gitHubAuth = new GitHubAuth();
    private final GitHubGallery gallery = new GitHubGallery(gitHubClient, gitHubAuth);
    private final BotInstaller botInstaller = new BotInstaller(gitHubClient, gallery);
    private final BotPublisher botPublisher = new BotPublisher(gitHubClient, gitHubAuth);
    // Reads the plugin index off the same raw CDN the gallery uses, with the same client and no account.
    private final PluginRegistry pluginRegistry = new PluginRegistry(gitHubClient);

    /**
     * Six of the thirteen parameters this took were the project's own services, re-listed here after
     * {@code UIManager} had already listed them; they arrive as one {@link StudioContext} now. What is left is
     * genuinely the shell's: the window, the one thing only the shell builds
     * ({@link ScreenCaptureService}) and the two surfaces these actions are wired onto.
     */
    StudioActions(StudioContext ctx,
                  Stage primaryStage,
                  ScreenCaptureService screenCaptureService,
                  MenuBarManager menuBar,
                  ToolbarManager toolbar,
                  Runnable recoverProjectFiles) {
        this.primaryStage = primaryStage;
        this.config = ctx.config();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        this.codeEditorService = ctx.codeEditorService();
        this.projectSettingsService = ctx.projectSettingsService();
        this.screenCaptureService = screenCaptureService;
        this.projectAnalyzer = ctx.projectAnalyzer();
        this.activityService = ctx.activityService();
        this.libraryService = ctx.libraryService();
        this.menuBar = menuBar;
        this.toolbar = toolbar;
        this.recoverProjectFiles = recoverProjectFiles;
    }

    /** Installs every action on the menu bar and the toolbar. Called once, from the shell's constructor. */
    void wire() {
        menuBar.setEventBus(eventBus);
        menuBar.setProjectPath(config.projectPath());

        // --- Project ---
        menuBar.setOnManageLibraries(this::openManageLibraries);
        menuBar.setOnManagePlugins(this::openManagePlugins);
        menuBar.setOnReloadPlugins(this::reloadPlugins);
        menuBar.setOnUpgradeSdk(this::openSdkUpgrade);
        menuBar.setOnModernise(this::openModernise);
        menuBar.setOnProjectSetup(this::openProjectSetup);
        toolbar.setOnProjectSetup(this::openProjectSetup);
        menuBar.setOnManageImports(this::openManageImports);
        menuBar.setOnActivityFlow(this::openActivityFlow);
        toolbar.setOnActivityFlow(this::openActivityFlow);
        menuBar.setOnParameters(this::openParameters);
        toolbar.setOnParameters(this::openParameters);
        menuBar.setOnRecoverProjectFiles(recoverProjectFiles);
        menuBar.setOnManageResources(this::openResourceManager);
        toolbar.setOnAccessResources(this::openResourceManager);
        menuBar.setOnProjectSettings(this::openProjectSettings);
        toolbar.setOnProjectSettings(this::openProjectSettings);

        // --- Capture / launch / input ---
        toolbar.setResourcesDir(config.resourcesRoot());
        // Nothing to wire for the capture targets: that button is the SDK plugin's item, and its dialog is
        // the plugin's too. The shell supplies the bar it is placed on and nothing else.
        toolbar.setLaunchTarget(ProjectCreator.readLaunchTarget(config.resourcesRoot()));
        toolbar.setOnManageLaunchTarget(() -> openLaunchTarget(null));
        toolbar.setOnToggleDebugOutput(ProjectCreator.readDebug(config.resourcesRoot()), this::writeDebug);
        toolbar.setOnConfigureInput(() -> new BotSettingsDialog(primaryStage, config, null).show());
        // ✂ Capture Templates stood here until 2026-08-31 and is the SDK plugin's item now, placed by the
        // same merge as the pilot's. Everything behind it — the capture target, the size to snap to, the
        // picture folder it writes into — is that plugin's, so there is nothing left for the shell to wire.
        toolbar.setOnOverlayEditor(() -> openOverlayEditor(false));
        toolbar.setOnRecordMacro(() -> openOverlayEditor(true));

        // The Remote Pilot used to be wired here. It is the SDK plugin's feature since 2026-08-30 and reaches
        // the bar as a ToolbarItem like any other plugin's, so there is nothing for the shell to wire.

        // --- Sharing / VCS ---
        menuBar.setOnBrowseGallery(this::openGallery);
        menuBar.setOnPublishGallery(this::openPublishDialog);
        menuBar.setOnShowHistory(this::openVcsDialog);
        menuBar.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));

        // --- Help ---
        menuBar.setOnGettingStarted(this::openGettingStarted);
        menuBar.setOnPickerGallery(this::openPickerGallery);
    }

    GitHubAuth gitHubAuth() { return gitHubAuth; }

    GitHubClient gitHubClient() { return gitHubClient; }

    BotPublisher botPublisher() { return botPublisher; }

    /**
     * Opens the Project Setup checklist hub — also the auto-open-on-creation target (reached from
     * {@code BotMakerStudio.finishOpen} through the shell).
     */
    void openProjectSetup() {
        new ProjectSetupDialog(primaryStage, config, projectSettingsService, projectAnalyzer, eventBus,
                toolbar::setLaunchTarget).show();
    }

    /** Opens the Resource Manager. Reused by the Project menu, the toolbar and the block image-picker. */
    void openResourceManager() {
        new ResourceManagerDialog(primaryStage, config, eventBus, screenCaptureService,
                codeEditorService).show();
    }

    /** Opens the Publish-to-gallery dialog. Shared with the VCS panel's "publish" button. */
    void openPublishDialog() {
        new PublishDialog(primaryStage, gitHubAuth, gitHubClient, gallery, botPublisher, config).show();
    }

    private void openVcsDialog() {
        new VcsDialog(primaryStage, config.projectName(), config.projectPath(), botPublisher,
                gitHubAuth, gitHubClient, eventBus, this::openPublishDialog).show();
    }

    private void openGallery() {
        new GalleryDialog(primaryStage, gallery, botInstaller, gitHubAuth, gitHubClient).show();
    }

    /**
     * Public because the SDK-floor banner on the canvas offers it too, not only the Project menu — that
     * banner exists precisely to send the user here, and routing it through the menu callback would mean the
     * banner could silently stop working if the wiring changed. Same reason {@code openProjectSetup} is public.
     */
    public void openManageLibraries() {
        new ManageLibrariesDialog(primaryStage, libraryService, mavenCentralSearch, jitPackSearch).show();
    }

    /**
     * The registry browser, which installs through the same {@link LibraryService} the dialog above uses —
     * a plugin is an ordinary dependency, and the registry only answers where to find its coordinate.
     */
    void openManagePlugins() {
        new ManagePluginsDialog(primaryStage, libraryService, pluginRegistry, jitPackSearch).show();
    }

    /**
     * Re-resolves the project's classpath and re-binds its plugins, with the pom untouched.
     *
     * <p>This is the plugin author's inner loop: {@code mvn install} the plugin into {@code ~/.m2}, reload,
     * see the change. The coordinate resolves to the same jar path either way, so nothing about the project
     * has changed and there is nothing to write — what moved is the jar's bytes, and a fresh classloader is
     * the whole of what it takes to see them.
     *
     * <p>Reported as an alert rather than silently: a reload that found the same plugins as before looks
     * exactly like a reload that did nothing, and an author who forgot to run {@code mvn install} needs to
     * be able to tell those apart.
     */
    void reloadPlugins() {
        libraryService.reloadPlugins().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                ThemedWindows.alert(Alert.AlertType.ERROR,
                        "Could not reload plugins: " + failure.getMessage()).showAndWait();
                return;
            }
            List<StudioPlugin> plugins = PluginHost.plugins();
            StringBuilder names = new StringBuilder();
            for (StudioPlugin plugin : plugins) {
                names.append(names.isEmpty() ? "" : "\n").append("• ").append(plugin.displayName());
            }
            ThemedWindows.alert(Alert.AlertType.INFORMATION,
                    plugins.size() + " plugin(s) loaded:\n" + names).showAndWait();
        }));
    }

    /**
     * The same operation Manage Libraries offers as a cell edit — change the SDK version — but with the
     * consequences read out of both jars first. Public for the same reason as {@link #openManageLibraries}:
     * the canvas banner is a second, non-menu route to it.
     */
    public void openSdkUpgrade() {
        new SdkUpgradeDialog(primaryStage,
                new SdkUpgradeService(config, state, libraryService, jitPackSearch)).show();
    }

    /**
     * The same report and the same repair pass with no version in it — move this bot off what the SDK it
     * already pins has deprecated. It needs no network at all: the answer is in the jar the project resolves
     * today.
     */
    private void openModernise() {
        new SdkUpgradeDialog(primaryStage,
                new SdkUpgradeService(config, state, libraryService, jitPackSearch)).showModernise();
    }

    private void openManageImports() {
        new ManageImportsDialog(primaryStage, codeEditorService).show();
    }

    private void openActivityFlow() {
        new ActivityFlowDialog(primaryStage, activityService).show();
    }

    /**
     * Opens the dev-only picker gallery, seeded with this project so the template and colour editors have
     * something real to resolve. Only reachable from a dev build's Help menu.
     */
    private void openPickerGallery() {
        new PickerGalleryWindow(primaryStage, config).show();
    }

    /** The one editor for every value the bot reads. */
    private void openParameters() {
        new ParametersDialog(primaryStage, config, activityService).show();
    }

    private void openProjectSettings() {
        new ProjectSettingsDialog(primaryStage, projectSettingsService, projectAnalyzer).show();
    }

    /**
     * Shows the Launch Target dialog, running {@code then} once it closes when there is one — the recovery the
     * overlay editor takes when there is nothing to draw over (no private session up, no default capture
     * target). The dialog is where the game is both chosen and started ("▶ Launch now"), so it is the one
     * place that can turn "no window" into a window.
     */
    private void openLaunchTarget(Runnable then) {
        LaunchTargetDialog dialog = new LaunchTargetDialog(primaryStage, config, toolbar::setLaunchTarget);
        if (then == null) dialog.show();
        else dialog.show(then);
    }

    private void writeDebug(boolean on) {
        try {
            ProjectCreator.writeDebug(config.resourcesRoot(), on);
        } catch (IOException ex) {
            System.err.println("Failed to save debug setting: " + ex.getMessage());
        }
    }

    /**
     * Opens the program-shape overlay authoring editor (compact clickable block tree + insertion cursor).
     *
     * <p>{@code startRecording} is the toolbar's ⏺ Record: the entry point that keeps the overlay's recording
     * flag live, now that the standalone Record Macro button is gone and the recorder lives in the HUD.
     */
    private void openOverlayEditor(boolean startRecording) {
        ProgramShapeOverlay.open(primaryStage, codeEditorService, projectSettingsService, screenCaptureService,
                activityService, this::liveSessionWindow, startRecording, this::openLaunchTarget);
    }

    /**
     * The live private session's host window for the overlay to draw over, or {@code 0} when none is running —
     * revealed first, since a session is brought up minimized and an overlay over a minimized window shows
     * nothing.
     *
     * <p>Asked of the project's one {@link BackgroundLauncher} rather than of the pilot. It used to come from
     * {@code RemotePilotUi}, which was the only thing holding a launcher; the pilot is a plugin now, and a
     * host may not reach into one. The launcher is the right source anyway — it is per project and holds the
     * session whether the pilot, the ▶ Launch button, or nothing at all started it.
     */
    private long liveSessionWindow() {
        return BackgroundLauncher.forProject(config.resourcesRoot()).revealHostWindow();
    }

    /**
     * Opens Help ▸ Getting Started. The dialog owns none of the prose — it renders
     * {@link com.botmaker.studio.docs.Workflow}, the same source {@code WORKFLOW.md} is generated from — so all
     * this has to supply is the way to actually open each destination a step names.
     */
    private void openGettingStarted() {
        GettingStartedDialog.Actions actions = GettingStartedDialog.Actions.builder()
                .on(StudioAction.PROJECT_SETUP, this::openProjectSetup)
                // No CAPTURE_TARGETS entry either, and for the same reason as CAPTURE_TEMPLATES below: the
                // targets manager is the SDK plugin's toolbar item since 2026-08-31.
                .on(StudioAction.LAUNCH_TARGET, () -> openLaunchTarget(null))
                // No CAPTURE_TEMPLATES entry: the tool is the SDK plugin's toolbar item since 2026-08-31 and
                // the shell has no handle on it. The step still reads, without an Open button — the action's
                // own text already says where it lives, which is what that field is for.
                .on(StudioAction.RESOURCES, this::openResourceManager)
                .on(StudioAction.ACTIVITY_FLOW, this::openActivityFlow)
                .on(StudioAction.PARAMETERS, this::openParameters)
                .on(StudioAction.OVERLAY_EDITOR, () -> openOverlayEditor(false))
                .on(StudioAction.PUBLISH, this::openPublishDialog)
                .on(StudioAction.GALLERY, this::openGallery)
                .build();
        new GettingStartedDialog(primaryStage, actions).show();
    }
}
