package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

public class ToolbarManager {

    /** Longest window title shown on the Capture button before it's ellipsized. */
    private static final int CAPTURE_LABEL_MAX = 26;

    private final EventBus eventBus;
    private final ProjectSettingsService settings;
    // Controls
    private Button undoButton, redoButton;
    private Button runButton, debugButton, followButton, unifiedStopButton;
    private Button stepOverButton, continueButton;
    /** The Capture Targets button, whose text tracks the current project default. */
    private Button captureButton;

    /** Opens the Manage Capture Targets dialog; wired by {@link UIManager}. */
    private Runnable onManageCaptureTargets;
    /** Opens the debug dashboard; wired by {@link UIManager}. */
    private Runnable onOpenDebugDashboard;
    /** Starts the remote pilot server and shows the pairing dialog; wired by {@link UIManager}. */
    private Runnable onEnableRemotePilot;
    /** Opens the live overlay template-capture over the default window; wired by {@link UIManager}. */
    private Runnable onCaptureTemplates;

    private enum AppState { IDLE, RUNNING, DEBUGGING }
    private AppState currentAppState = AppState.IDLE;

    public ToolbarManager(EventBus eventBus, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.settings = settings;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
        // Keep the Capture button's text in sync with the project's default target.
        eventBus.subscribe(CoreApplicationEvents.SettingsChangedEvent.class, e -> {
            if (captureButton != null) captureButton.setText(captureButtonText());
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

    /** Sets the callback invoked when the toolbar's Capture Targets button is clicked. */
    public void setOnManageCaptureTargets(Runnable callback) {
        this.onManageCaptureTargets = callback;
    }

    /** Sets the callback invoked when the toolbar's Debug Dashboard button is clicked. */
    public void setOnOpenDebugDashboard(Runnable callback) {
        this.onOpenDebugDashboard = callback;
    }

    /** Sets the callback invoked when the toolbar's Remote Pilot button is clicked. */
    public void setOnEnableRemotePilot(Runnable callback) {
        this.onEnableRemotePilot = callback;
    }

    /** Sets the callback invoked when the toolbar's Capture Templates button is clicked. */
    public void setOnCaptureTemplates(Runnable callback) {
        this.onCaptureTemplates = callback;
    }

    /**
     * Creates the center group: the Capture Targets button (opens the manage dialog; its text shows the
     * current default target) next to the Debug Dashboard button (opens the live telemetry dashboard).
     */
    public HBox createCaptureGroup() {
        captureButton = new Button(captureButtonText());
        captureButton.getStyleClass().add("toolbar-btn");
        captureButton.setTooltip(new Tooltip("Manage screen / window capture targets (current default shown)"));
        captureButton.setOnAction(e -> {
            if (onManageCaptureTargets != null) onManageCaptureTargets.run();
        });

        Button debugDashboardButton = new Button("📊 Debug Dashboard");
        debugDashboardButton.getStyleClass().add("toolbar-btn");
        debugDashboardButton.setTooltip(new Tooltip("Open the live telemetry debug dashboard in your browser"));
        debugDashboardButton.setOnAction(e -> {
            if (onOpenDebugDashboard != null) onOpenDebugDashboard.run();
        });

        Button remotePilotButton = new Button("🎮 Remote Pilot");
        remotePilotButton.getStyleClass().add("toolbar-btn");
        remotePilotButton.setTooltip(new Tooltip(
                "Control and watch the bot from your phone — shows a QR to scan (no VPN needed)"));
        remotePilotButton.setOnAction(e -> {
            if (onEnableRemotePilot != null) onEnableRemotePilot.run();
        });

        Button captureTemplatesButton = new Button("✂ Capture Templates");
        captureTemplatesButton.getStyleClass().add("toolbar-btn");
        captureTemplatesButton.setTooltip(new Tooltip(
                "Draw a live overlay over the default window to quickly grab image templates"));
        captureTemplatesButton.setOnAction(e -> {
            if (onCaptureTemplates != null) onCaptureTemplates.run();
        });

        HBox group = new HBox(5, captureButton, captureTemplatesButton, debugDashboardButton, remotePilotButton);
        group.setAlignment(Pos.CENTER);
        return group;
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