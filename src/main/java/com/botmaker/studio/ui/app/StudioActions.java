package com.botmaker.studio.ui.app;

import com.botmaker.studio.docs.StudioAction;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.ImageTemplateLibrary;
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
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.app.capture.OverlayTemplateCapture;
import com.botmaker.studio.ui.app.overlay.ProgramShapeOverlay;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi;
import com.botmaker.studio.ui.app.settings.SettingsDialog;
import javafx.stage.Stage;

import java.io.IOException;

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
    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final ProjectSettingsService projectSettingsService;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectAnalyzer projectAnalyzer;
    private final ActivityService activityService;
    private final LibraryService libraryService;
    private final RemotePilotUi remotePilot;
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

    /**
     * Six of the thirteen parameters this took were the project's own services, re-listed here after
     * {@code UIManager} had already listed them; they arrive as one {@link StudioContext} now. What is left is
     * genuinely the shell's: the window, the two things only the shell builds
     * ({@link ScreenCaptureService}, {@link RemotePilotUi}) and the two surfaces these actions are wired onto.
     */
    StudioActions(StudioContext ctx,
                  Stage primaryStage,
                  ScreenCaptureService screenCaptureService,
                  RemotePilotUi remotePilot,
                  MenuBarManager menuBar,
                  ToolbarManager toolbar,
                  Runnable recoverProjectFiles) {
        this.primaryStage = primaryStage;
        this.config = ctx.config();
        this.eventBus = ctx.eventBus();
        this.codeEditorService = ctx.codeEditorService();
        this.projectSettingsService = ctx.projectSettingsService();
        this.screenCaptureService = screenCaptureService;
        this.projectAnalyzer = ctx.projectAnalyzer();
        this.activityService = ctx.activityService();
        this.libraryService = ctx.libraryService();
        this.remotePilot = remotePilot;
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
        menuBar.setOnProjectSetup(this::openProjectSetup);
        toolbar.setOnProjectSetup(this::openProjectSetup);
        menuBar.setOnManageImports(this::openManageImports);
        menuBar.setOnActivityFlow(this::openActivityFlow);
        toolbar.setOnActivityFlow(this::openActivityFlow);
        menuBar.setOnParameters(this::openParameters);
        // The entry is named after what it opens, and that differs by project — "parameters" belong to an
        // activity, "settings" to the project, and calling both the same thing is the naming drift to avoid.
        menuBar.setParametersLabel(activityService.current().settingsModel().isJava()
                ? "Settings..." : "Parameters...");
        menuBar.setOnRecoverProjectFiles(recoverProjectFiles);
        menuBar.setOnManageResources(this::openResourceManager);
        toolbar.setOnAccessResources(this::openResourceManager);
        menuBar.setOnProjectSettings(this::openProjectSettings);
        toolbar.setOnProjectSettings(this::openProjectSettings);

        // --- Capture / launch / input ---
        toolbar.setResourcesDir(config.resourcesRoot());
        toolbar.setOnManageCaptureTargets(this::openManageCaptureTargets);
        toolbar.setLaunchTarget(ProjectCreator.readLaunchTarget(config.resourcesRoot()));
        toolbar.setOnManageLaunchTarget(() -> openLaunchTarget(null));
        toolbar.setOnToggleDebugOutput(ProjectCreator.readDebug(config.resourcesRoot()), this::writeDebug);
        toolbar.setOnConfigureInput(() -> new BotSettingsDialog(primaryStage, config, null).show());
        toolbar.setOnCaptureTemplates(this::openOverlayTemplateCapture);
        toolbar.setOnOverlayEditor(() -> openOverlayEditor(false));
        toolbar.setOnRecordMacro(() -> openOverlayEditor(true));

        // --- Remote Pilot ---
        menuBar.setOnEnableRemotePilot(remotePilot::open);
        toolbar.setOnEnableRemotePilot(remotePilot::open);

        // --- Sharing / VCS ---
        menuBar.setOnBrowseGallery(this::openGallery);
        menuBar.setOnPublishGallery(this::openPublishDialog);
        menuBar.setOnShowHistory(this::openVcsDialog);
        menuBar.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));

        // --- Help ---
        menuBar.setOnGettingStarted(this::openGettingStarted);
    }

    GitHubAuth gitHubAuth() { return gitHubAuth; }

    GitHubClient gitHubClient() { return gitHubClient; }

    BotPublisher botPublisher() { return botPublisher; }

    /**
     * Opens the Project Setup checklist hub — also the auto-open-on-creation target (reached from
     * {@code BotMakerStudio.finishOpen} through the shell). Reuses the overlay template capture for its
     * optional "Image templates" step.
     */
    void openProjectSetup() {
        new ProjectSetupDialog(primaryStage, config, projectSettingsService, projectAnalyzer, eventBus,
                this::openOverlayTemplateCapture, toolbar::setLaunchTarget).show();
    }

    /** Opens the Resource Manager. Reused by the Project menu, the toolbar and the block image-picker. */
    void openResourceManager() {
        new ResourceManagerDialog(primaryStage, config, eventBus, screenCaptureService).show();
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

    private void openManageLibraries() {
        new ManageLibrariesDialog(primaryStage, libraryService, mavenCentralSearch, jitPackSearch).show();
    }

    private void openManageImports() {
        new ManageImportsDialog(primaryStage, codeEditorService).show();
    }

    private void openActivityFlow() {
        new ActivityFlowDialog(primaryStage, activityService).show();
    }

    /**
     * The settings editor — which of the two depends on where this project keeps its values. A java-model
     * project gets the project-wide {@link SettingsDialog}; a legacy one keeps {@link ParametersDialog} and
     * its per-activity ownership, unchanged. Selected here, once, rather than branched inside either.
     */
    private void openParameters() {
        if (activityService.current().settingsModel().isJava()) {
            new SettingsDialog(primaryStage, config, activityService).show();
        } else {
            new ParametersDialog(primaryStage, activityService).show();
        }
    }

    private void openProjectSettings() {
        new ProjectSettingsDialog(primaryStage, projectSettingsService, projectAnalyzer).show();
    }

    private void openManageCaptureTargets() {
        new ManageCaptureTargetsDialog(primaryStage, projectSettingsService, config.resourcesRoot()).show();
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

    /** Opens the live overlay template-capture over the project's default window target. */
    private void openOverlayTemplateCapture() {
        OverlayTemplateCapture.open(primaryStage, config, projectSettingsService, screenCaptureService, eventBus,
                ImageTemplateLibrary.openActivityTag(config, codeEditorService.getState()));
    }

    /**
     * Opens the program-shape overlay authoring editor (compact clickable block tree + insertion cursor).
     *
     * <p>{@code startRecording} is the toolbar's ⏺ Record: the entry point that keeps the overlay's recording
     * flag live, now that the standalone Record Macro button is gone and the recorder lives in the HUD.
     */
    private void openOverlayEditor(boolean startRecording) {
        ProgramShapeOverlay.open(primaryStage, codeEditorService, projectSettingsService, screenCaptureService,
                activityService, remotePilot::liveSessionWindow, startRecording, this::openLaunchTarget);
    }

    /**
     * Opens Help ▸ Getting Started. The dialog owns none of the prose — it renders
     * {@link com.botmaker.studio.docs.Workflow}, the same source {@code WORKFLOW.md} is generated from — so all
     * this has to supply is the way to actually open each destination a step names.
     */
    private void openGettingStarted() {
        GettingStartedDialog.Actions actions = GettingStartedDialog.Actions.builder()
                .on(StudioAction.PROJECT_SETUP, this::openProjectSetup)
                .on(StudioAction.CAPTURE_TARGETS, this::openManageCaptureTargets)
                .on(StudioAction.LAUNCH_TARGET, () -> openLaunchTarget(null))
                .on(StudioAction.CAPTURE_TEMPLATES, this::openOverlayTemplateCapture)
                .on(StudioAction.RESOURCES, this::openResourceManager)
                .on(StudioAction.ACTIVITY_FLOW, this::openActivityFlow)
                .on(StudioAction.OVERLAY_EDITOR, () -> openOverlayEditor(false))
                .on(StudioAction.REMOTE_PILOT, remotePilot::open)
                .on(StudioAction.PUBLISH, this::openPublishDialog)
                .on(StudioAction.GALLERY, this::openGallery)
                .build();
        new GettingStartedDialog(primaryStage, actions).show();
    }
}
