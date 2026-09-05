package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
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
        // Nothing to wire for Project Setup: that checklist is the SDK plugin's 📋 Project Setup item since
        // 2026-08-31. Every row of it reads a file the plugin owns, so the shell could only ever have shown
        // it by asking the plugin for the answers.
        menuBar.setOnManageImports(this::openManageImports);
        menuBar.setOnActivityFlow(this::openActivityFlow);
        toolbar.setOnActivityFlow(this::openActivityFlow);
        menuBar.setOnParameters(this::openParameters);
        toolbar.setOnParameters(this::openParameters);
        menuBar.setOnRecoverProjectFiles(recoverProjectFiles);
        // Nothing to wire for the Resource Manager: the picture library is the SDK plugin's, and the manager
        // went to it on 2026-09-01 with the gallery, the archive and the tag rules it is built on. Leaving
        // the menu entry and the toolbar button unwired keeps them visible and dead, so both are gone; the
        // way in is the plugin's own toolbar item.
        menuBar.setOnProjectSettings(this::openProjectSettings);
        toolbar.setOnProjectSettings(this::openProjectSettings);

        // --- Capture / launch / input ---
        // Nothing to wire for the capture targets: that button is the SDK plugin's item, and its dialog is
        // the plugin's too. The shell supplies the bar it is placed on and nothing else.
        //
        // Nor for the launch target, since 2026-09-01: the 🚀 button and its dialog went together, so there
        // is no control left to seed with the current target and no manage callback to answer. The key
        // itself stays in botmaker-project.properties, written by whoever installs a published bot.
        toolbar.setOnToggleDebugOutput(ProjectCreator.readDebug(config.resourcesRoot()), this::writeDebug);
        toolbar.setOnConfigureInput(() -> new BotSettingsDialog(primaryStage, config, null).show());
        // ✂ Capture Templates stood here until 2026-08-31 and is the SDK plugin's item now, placed by the
        // same merge as the pilot's. Everything behind it — the capture target, the size to snap to, the
        // picture folder it writes into — is that plugin's, so there is nothing left for the shell to wire.
        toolbar.setOnOverlayEditor(this::openOverlayEditor);
        // ⏺ Record stood beside it and opened the same overlay straight into recording. The recorder is the
        // SDK plugin's since 2026-09-02 — what a recorded click is written down as is that plugin's sentence —
        // so it reaches the bar as a ToolbarItem and there is nothing left for the shell to wire.

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
     * banner could silently stop working if the wiring changed.
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

    // openLaunchTarget stood here until 2026-09-01, showing the Launch Target dialog and — when the overlay
    // editor had nothing to draw over — running a callback once it closed. The dialog went with the launch
    // rows, so there is no recovery to offer: the overlay now says what to do instead of opening the one
    // place that could have fixed it. That is a real loss of an affordance, recorded rather than papered
    // over, and the thing that would restore it is the launcher becoming a plugin's toolbar item like the
    // capture tools already are.

    private void writeDebug(boolean on) {
        try {
            ProjectCreator.writeDebug(config.resourcesRoot(), on);
        } catch (IOException ex) {
            System.err.println("Failed to save debug setting: " + ex.getMessage());
        }
    }

    /**
     * Opens the program-shape overlay authoring editor (compact clickable block tree + insertion cursor).
     */
    private void openOverlayEditor() {
        ProgramShapeOverlay.open(primaryStage, codeEditorService, projectSettingsService, screenCaptureService,
                activityService, this::liveSessionWindow, null);
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
                // No PROJECT_SETUP entry, and no CAPTURE_TARGETS one, for the same reason as CAPTURE_TEMPLATES
                // below: all three are the SDK plugin's toolbar items since 2026-08-31.
                // No LAUNCH_TARGET entry either, since 2026-09-01: the 🚀 dialog is gone and the shell has
                // nothing to open. The step still reads, without an Open button.
                // No CAPTURE_TEMPLATES entry: the tool is the SDK plugin's toolbar item since 2026-08-31 and
                // the shell has no handle on it. The step still reads, without an Open button — the action's
                // own text already says where it lives, which is what that field is for.
                // No RESOURCES entry, for the same reason as CAPTURE_TEMPLATES: the Resource Manager is the
                // SDK plugin's since 2026-09-01 and the shell has no handle on it.
                .on(StudioAction.ACTIVITY_FLOW, this::openActivityFlow)
                .on(StudioAction.PARAMETERS, this::openParameters)
                .on(StudioAction.OVERLAY_EDITOR, this::openOverlayEditor)
                .on(StudioAction.PUBLISH, this::openPublishDialog)
                .on(StudioAction.GALLERY, this::openGallery)
                .build();
        new GettingStartedDialog(primaryStage, actions).show();
    }
}
