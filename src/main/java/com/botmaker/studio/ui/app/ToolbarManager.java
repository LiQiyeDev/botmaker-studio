package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

public class ToolbarManager {

    private final EventBus eventBus;
    // Controls
    private Button undoButton, redoButton;
    private Button runButton, debugButton, unifiedStopButton;
    private Button stepOverButton, continueButton;

    /** Opens the Manage Capture Targets dialog; wired by {@link UIManager}. */
    private Runnable onManageCaptureTargets;

    private enum AppState { IDLE, RUNNING, DEBUGGING }
    private AppState currentAppState = AppState.IDLE;

    public ToolbarManager(EventBus eventBus) {
        this.eventBus = eventBus;
        setupEventHandlers();
    }

    private void setupEventHandlers() {
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

    /**
     * Creates the center group: the Capture Targets button (opens the manage dialog).
     */
    public HBox createCaptureGroup() {
        Button captureButton = new Button("🎯 Capture Targets");
        captureButton.getStyleClass().add("toolbar-btn");
        captureButton.setTooltip(new Tooltip("Manage screen / window capture targets"));
        captureButton.setOnAction(e -> {
            if (onManageCaptureTargets != null) onManageCaptureTargets.run();
        });

        HBox group = new HBox(5, captureButton);
        group.setAlignment(Pos.CENTER);
        return group;
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

        HBox group = new HBox(5, runButton, debugButton, unifiedStopButton, stepOverButton, continueButton);
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