package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.game.GameLibraries;
import com.botmaker.studio.game.InstalledGame;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.project.launch.LaunchTargetNames;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

public class ToolbarManager {

    /** Longest window title shown on the Capture button before it's ellipsized. */
    private static final int CAPTURE_LABEL_MAX = 26;
    /** Edge length of the launch target's cover thumbnail on its toolbar button. */
    private static final int LAUNCH_ICON_PX = 20;

    private final EventBus eventBus;
    private final ProjectSettingsService settings;
    // Controls
    private Button undoButton, redoButton;
    private Button runButton, debugButton, followButton, unifiedStopButton;
    private Button stepOverButton, continueButton;
    /** The Capture Targets button, whose text tracks the current project default. */
    private Button captureButton;
    /** The Launch Target button, whose text + icon track the project's {@code launch.target}. */
    private Button launchTargetButton;
    /** The current {@code launch.target} spec, pushed in by {@link UIManager}; null when none is set. */
    private String launchTargetSpec;
    private Label resolutionLabel;

    /** Opens the Project Setup checklist hub; wired by {@link UIManager}. */
    private Runnable onProjectSetup;
    /** Opens the Manage Capture Targets dialog; wired by {@link UIManager}. */
    private Runnable onManageCaptureTargets;
    /** Opens the Launch Target dialog (what the bot launches); wired by {@link UIManager}. */
    private Runnable onManageLaunchTarget;
    private Runnable onActivityFlow;
    /** Persists the debug-output toggle to the project; wired by {@link UIManager}. */
    private Consumer<Boolean> onToggleDebugOutput;
    /** The debug-output toggle's initial (persisted) state — read by {@link UIManager} before building the bar. */
    private boolean debugOutputInitial = true;
    /** Starts the remote pilot server and shows the pairing dialog; wired by {@link UIManager}. */
    private Runnable onEnableRemotePilot;
    /** Opens the live overlay template-capture over the default window; wired by {@link UIManager}. */
    private Runnable onCaptureTemplates;
    /** Opens the program-shape overlay authoring editor; wired by {@link UIManager}. */
    private Runnable onOverlayEditor;
    /** Opens the Resource Manager (image templates); wired by {@link UIManager}. */
    private Runnable onAccessResources;

    private enum AppState { IDLE, RUNNING, DEBUGGING }
    private AppState currentAppState = AppState.IDLE;

    public ToolbarManager(EventBus eventBus, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.settings = settings;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        // Keep the Capture button's text + resolution readout in sync with the project's settings.
        eventBus.subscribe(CoreApplicationEvents.SettingsChangedEvent.class, e -> {
            if (captureButton != null) captureButton.setText(captureButtonText());
            if (resolutionLabel != null) resolutionLabel.setText(resolutionText());
        }, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class, e -> setAppState(AppState.RUNNING), true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStoppedEvent.class, e -> setAppState(AppState.IDLE), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionEvent.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugSessionStartedEvent ignored -> setAppState(AppState.DEBUGGING);
                case CoreApplicationEvents.DebugSessionFinishedEvent ignored -> setAppState(AppState.IDLE);
                case CoreApplicationEvents.DebugSessionPausedEvent ignored -> updateDebugControls(true);
                case CoreApplicationEvents.DebugSessionResumedEvent ignored -> updateDebugControls(false);
            }
        }, true);
        eventBus.subscribe(CoreApplicationEvents.HistoryStateChangedEvent.class, event -> {
            if (undoButton != null) undoButton.setDisable(!event.canUndo());
            if (redoButton != null) redoButton.setDisable(!event.canRedo());
        }, true);
    }

    /**
     * Creates the Left-side group: Undo, Redo, Compile
     */
    public HBox createEditGroup() {
        undoButton = new Button("↶");
        undoButton.setTooltip(new Tooltip("Undo (Ctrl+Z)"));
        undoButton.setDisable(true);
        undoButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.UndoRequestedEvent()));

        redoButton = new Button("↷");
        redoButton.setTooltip(new Tooltip("Redo (Ctrl+Y)"));
        redoButton.setDisable(true);
        redoButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.RedoRequestedEvent()));

        Button compileButton = new Button("⚙ Compile");
        compileButton.getStyleClass().add("toolbar-btn");
        compileButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.CompilationRequestedEvent()));

        HBox group = new HBox(5, undoButton, redoButton, compileButton);
        group.setAlignment(Pos.CENTER_LEFT);
        group.setPadding(new Insets(0, 10, 0, 0));
        return group;
    }

    /** Sets the callback invoked when the toolbar's Project Setup button is clicked. */
    public void setOnProjectSetup(Runnable callback) {
        this.onProjectSetup = callback;
    }

    /** Sets the callback invoked when the toolbar's Capture Targets button is clicked. */
    public void setOnManageCaptureTargets(Runnable callback) {
        this.onManageCaptureTargets = callback;
    }

    /** Sets the callback invoked when the toolbar's Launch Target button is clicked. */
    public void setOnManageLaunchTarget(Runnable callback) {
        this.onManageLaunchTarget = callback;
    }

    /** Sets the callback invoked when the toolbar's Activity Flow button is clicked. */
    public void setOnActivityFlow(Runnable callback) {
        this.onActivityFlow = callback;
    }

    /**
     * Wires the debug-output toggle: {@code initial} is the project's persisted {@code debug} state (shown as the
     * toggle's starting position) and {@code onToggle} persists each change. Call before {@link #createCaptureGroup()}.
     */
    public void setOnToggleDebugOutput(boolean initial, Consumer<Boolean> onToggle) {
        this.debugOutputInitial = initial;
        this.onToggleDebugOutput = onToggle;
    }

    /** Sets the callback invoked when the toolbar's Remote Pilot button is clicked. */
    public void setOnEnableRemotePilot(Runnable callback) {
        this.onEnableRemotePilot = callback;
    }

    /** Sets the callback invoked when the toolbar's Capture Templates button is clicked. */
    public void setOnCaptureTemplates(Runnable callback) {
        this.onCaptureTemplates = callback;
    }

    /** Sets the callback invoked when the toolbar's Overlay Editor button is clicked. */
    public void setOnOverlayEditor(Runnable callback) {
        this.onOverlayEditor = callback;
    }

    /** Sets the callback invoked when the toolbar's Resources button is clicked. */
    public void setOnAccessResources(Runnable callback) {
        this.onAccessResources = callback;
    }

    /**
     * Creates the center group: every project-level action as its own visible button. It is a {@link FlowPane}
     * so a narrow window <em>wraps</em> the buttons onto further rows instead of clipping them — which is why
     * there is no longer a "⋯ More" overflow menu hiding Capture Templates / Overlay Editor / Resources. An
     * overflow menu trades one problem (too wide) for a worse one (an action you can't see is an action you
     * don't know exists), and it hid those three even at full width, where there was room for them.
     *
     * <p>{@code minWidth = 0} matters: without it the group's preferred width becomes a floor on the stage's
     * width, so a button whose label grows ("🐞 Debug: off" → "on", a longer capture target) would push the
     * window wider on click.
     */
    public FlowPane createCaptureGroup() {
        Button projectSetupButton = new Button("🧭 Project Setup");
        projectSetupButton.getStyleClass().add("toolbar-btn");
        projectSetupButton.setTooltip(new Tooltip(
                "Set the project up to run: launch target, capture target, resolution and templates in one checklist"));
        projectSetupButton.setOnAction(e -> {
            if (onProjectSetup != null) onProjectSetup.run();
        });

        captureButton = new Button(captureButtonText());
        captureButton.getStyleClass().add("toolbar-btn");
        captureButton.setTooltip(new Tooltip("Manage screen / window capture targets (current default shown)"));
        captureButton.setOnAction(e -> {
            if (onManageCaptureTargets != null) onManageCaptureTargets.run();
        });

        launchTargetButton = new Button(launchTargetText(launchTargetSpec));
        launchTargetButton.getStyleClass().add("toolbar-btn");
        launchTargetButton.setTooltip(new Tooltip(
                "Choose what the bot launches at startup — a Steam/Epic game, an executable, or an emulator app"));
        launchTargetButton.setOnAction(e -> {
            if (onManageLaunchTarget != null) onManageLaunchTarget.run();
        });
        resolveLaunchArtwork(launchTargetSpec);

        Button activityFlowButton = new Button("🔀 Activity Flow");
        activityFlowButton.getStyleClass().add("toolbar-btn");
        activityFlowButton.setTooltip(new Tooltip(
                "Define the bot's activities and wire the order they run in"));
        activityFlowButton.setOnAction(e -> {
            if (onActivityFlow != null) onActivityFlow.run();
        });

        Button remotePilotButton = new Button("🎮 Remote Pilot");
        remotePilotButton.getStyleClass().add("toolbar-btn");
        remotePilotButton.setTooltip(new Tooltip(
                "Stream what the bot sees to your phone or browser — watch it, start/stop it, "
                        + "or turn on Interact to click and drag in the game yourself"));
        remotePilotButton.setOnAction(e -> {
            if (onEnableRemotePilot != null) onEnableRemotePilot.run();
        });

        ToggleButton debugOutputButton = new ToggleButton(debugOutputText(debugOutputInitial));
        debugOutputButton.getStyleClass().add("toolbar-btn");
        debugOutputButton.setSelected(debugOutputInitial);
        debugOutputButton.setTooltip(new Tooltip(
                "Toggle the bot's debug output ([Bot]/[Game]/[Target]/[Activity] + vision traces). Saved with the project."));
        debugOutputButton.setOnAction(e -> {
            boolean on = debugOutputButton.isSelected();
            debugOutputButton.setText(debugOutputText(on));
            if (onToggleDebugOutput != null) onToggleDebugOutput.accept(on);
        });

        Button captureTemplatesButton = new Button("✂ Capture Templates");
        captureTemplatesButton.getStyleClass().add("toolbar-btn");
        captureTemplatesButton.setTooltip(new Tooltip(
                "Cut and manage the template images the bot matches against"));
        captureTemplatesButton.setOnAction(e -> {
            if (onCaptureTemplates != null) onCaptureTemplates.run();
        });

        Button overlayEditorButton = new Button("⧉ Overlay Editor");
        overlayEditorButton.getStyleClass().add("toolbar-btn");
        overlayEditorButton.setTooltip(new Tooltip("Draw and edit the project's regions and overlays"));
        overlayEditorButton.setOnAction(e -> {
            if (onOverlayEditor != null) onOverlayEditor.run();
        });

        Button resourcesButton = new Button("🗂 Resources");
        resourcesButton.getStyleClass().add("toolbar-btn");
        resourcesButton.setTooltip(new Tooltip("Browse the project's resource files"));
        resourcesButton.setOnAction(e -> {
            if (onAccessResources != null) onAccessResources.run();
        });

        resolutionLabel = new Label(resolutionText());
        resolutionLabel.getStyleClass().add("toolbar-resolution");
        resolutionLabel.setTooltip(new Tooltip("Project standard resolution · primary screen resolution"));

        FlowPane group = new FlowPane(Orientation.HORIZONTAL, 5, 5,
                projectSetupButton, captureButton, launchTargetButton, activityFlowButton, remotePilotButton,
                debugOutputButton, captureTemplatesButton, overlayEditorButton, resourcesButton, resolutionLabel);
        group.setAlignment(Pos.CENTER);
        group.setMinWidth(0);
        return group;
    }

    /** The debug-output toggle's label for a given state. */
    private static String debugOutputText(boolean on) {
        return on ? "🐞 Debug: on" : "🐞 Debug: off";
    }

    /** "Std W×H · 🖵 W×H": the project standard resolution (if set) and the primary screen resolution. */
    private String resolutionText() {
        javafx.geometry.Rectangle2D sb = javafx.stage.Screen.getPrimary().getBounds();
        String screen = "🖵 " + (int) sb.getWidth() + "×" + (int) sb.getHeight();
        com.botmaker.studio.project.StudioProjectSettings.Resolution ref = null;
        try {
            ref = (settings != null) ? settings.current().referenceResolution() : null;
        } catch (Exception ignored) {
        }
        return (ref != null ? "Std " + ref.width() + "×" + ref.height() + "  ·  " : "") + screen;
    }

    /** "🎯 " + the current default target's short name, or "🎯 Capture Targets" when no default is set. */
    private String captureButtonText() {
        CaptureTarget def = null;
        try {
            def = (settings != null) ? settings.defaultTarget() : null;
        } catch (Exception ignored) {
        }
        if (def == null) return "🎯 Capture Targets";
        String name = CaptureTargetNames.shortLabel(def);
        if (name.length() > CAPTURE_LABEL_MAX) name = name.substring(0, CAPTURE_LABEL_MAX - 1) + "…";
        return "🎯 " + name;
    }

    /**
     * Points the Launch Target button at {@code spec} (the project's {@code launch.target}, null when unset):
     * the label becomes the target's name and, once the library scan resolves, its cover art becomes the
     * button's graphic. Called by {@link UIManager} when the bar is built and from {@link LaunchTargetDialog}'s
     * change callback — the launch target lives in {@code botmaker-project.properties}, not in
     * {@link ProjectSettingsService}, so no {@code SettingsChangedEvent} announces it.
     */
    public void setLaunchTarget(String spec) {
        this.launchTargetSpec = (spec == null || spec.isBlank()) ? null : spec.trim();
        if (launchTargetButton == null) return;
        launchTargetButton.setGraphic(null);
        launchTargetButton.setText(launchTargetText(launchTargetSpec));
        resolveLaunchArtwork(launchTargetSpec);
    }

    /** "🚀 " + the target's short name, or "🚀 Launch Target" when none is set. */
    private static String launchTargetText(String spec) {
        return launchTargetText(spec, null);
    }

    private static String launchTargetText(String spec, String displayName) {
        String name = LaunchTargetNames.shortLabel(spec, displayName);
        if (name.length() > CAPTURE_LABEL_MAX) name = name.substring(0, CAPTURE_LABEL_MAX - 1) + "…";
        return "🚀 " + name;
    }

    /**
     * Resolves a {@code <platform>:<id>} spec to its installed game so the button can show the real title and a
     * small cover. The scan reads JSON/VDF off disk, so it runs on a daemon thread and hops back to the FX
     * thread; a spec that resolves to nothing (a plain exe, an uninstalled game) simply leaves the label as-is.
     */
    private void resolveLaunchArtwork(String spec) {
        String platform = LaunchTargetNames.kindOf(spec);
        String id = LaunchTargetNames.tokenOf(spec);
        if (platform == null || id == null) return;
        Button button = launchTargetButton;
        Thread scan = new Thread(() -> {
            InstalledGame game = GameLibraries.findGame(platform, id).orElse(null);
            if (game == null) return;
            javafx.application.Platform.runLater(() -> {
                // A later setLaunchTarget may have won the race; only decorate the spec we were asked about.
                if (!java.util.Objects.equals(spec, launchTargetSpec)) return;
                button.setText(launchTargetText(spec, game.name()));
                if (game.artwork() != null) {
                    ImageView icon = new ImageView(
                            new Image(game.artwork().toUri().toString(), LAUNCH_ICON_PX, LAUNCH_ICON_PX, true, true, true));
                    icon.setPreserveRatio(true);
                    icon.setFitWidth(LAUNCH_ICON_PX);
                    icon.setFitHeight(LAUNCH_ICON_PX);
                    button.setGraphic(icon);
                }
            });
        }, "launch-target-art");
        scan.setDaemon(true);
        scan.start();
    }

    /**
     * Creates the Right-side group: Run, Debug, Stop, Step, Continue
     */
    public HBox createExecutionGroup() {
        runButton = new Button("▶ Run");
        runButton.getStyleClass().addAll("toolbar-btn", "btn-run");
        runButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.ExecutionRequestedEvent()));

        debugButton = new Button("🐞 Debug");
        debugButton.getStyleClass().addAll("toolbar-btn", "btn-debug");
        debugButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugStartRequestedEvent()));

        followButton = new Button("👁 Follow");
        followButton.getStyleClass().addAll("toolbar-btn", "btn-follow");
        followButton.setTooltip(new Tooltip("Run and highlight each executing block live (never pauses at breakpoints)"));
        followButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.FollowStartRequestedEvent()));

        unifiedStopButton = new Button("⏹ Stop");
        unifiedStopButton.getStyleClass().addAll("toolbar-btn", "btn-stop");
        unifiedStopButton.setDisable(true);
        unifiedStopButton.setOnAction(e -> {
            if (currentAppState == AppState.RUNNING) eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
            else if (currentAppState == AppState.DEBUGGING) eventBus.publish(new CoreApplicationEvents.DebugStopRequestedEvent());
        });

        stepOverButton = new Button("⤵ Step");
        stepOverButton.setDisable(true);
        stepOverButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugStepOverRequestedEvent()));

        continueButton = new Button("⏩ Cont");
        continueButton.setDisable(true);
        continueButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugContinueRequestedEvent()));

        HBox group = new HBox(5, runButton, debugButton, followButton, unifiedStopButton, stepOverButton, continueButton);
        group.setAlignment(Pos.CENTER_RIGHT);
        return group;
    }

    private void setAppState(AppState state) {
        this.currentAppState = state;
        updateToolbarState();
    }

    private void updateToolbarState() {
        boolean isBusy = (currentAppState != AppState.IDLE);
        runButton.setDisable(isBusy);
        debugButton.setDisable(isBusy);
        followButton.setDisable(isBusy);
        unifiedStopButton.setDisable(!isBusy);

        if (currentAppState == AppState.DEBUGGING) {
            unifiedStopButton.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white;");
        } else if (currentAppState == AppState.RUNNING) {
            unifiedStopButton.setStyle("-fx-background-color: #E74C3C; -fx-text-fill: white;");
            stepOverButton.setDisable(true);
            continueButton.setDisable(true);
        } else {
            unifiedStopButton.setStyle("");
            stepOverButton.setDisable(true);
            continueButton.setDisable(true);
        }
    }

    private void updateDebugControls(boolean isPaused) {
        if (currentAppState == AppState.DEBUGGING) {
            stepOverButton.setDisable(!isPaused);
            continueButton.setDisable(!isPaused);
        }
    }
}