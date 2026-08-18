package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.game.GameLibraries;
import com.botmaker.studio.game.InstalledGame;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.project.launch.QuickLaunch;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

public class ToolbarManager {

    /** Longest window title shown on the Capture button before it's ellipsized. */
    private static final int CAPTURE_LABEL_MAX = 26;
    /** Edge length of the launch target's cover thumbnail on its toolbar button. */
    private static final int LAUNCH_ICON_PX = 20;
    /**
     * Fixed width for the two buttons whose label tracks project state (capture target, launch target).
     * Sized for {@link #CAPTURE_LABEL_MAX} characters plus the icon and padding. These are the buttons that
     * change text <em>after</em> the bar is laid out — on a target switch, and again when
     * {@link #resolveLaunchArtwork} 's background scan lands with the real game title — so leaving them to
     * size themselves makes the toolbar re-wrap at moments the user reads as "the window moved on its own".
     */
    private static final int TARGET_BTN_WIDTH = 200;

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
    /** "▶ Launch" — starts the configured target without running the bot. Rebound when the target changes. */
    private Button quickLaunchButton;
    /** The project's resources dir, pushed in by {@link UIManager}; what quick launch reads its target from. */
    private java.nio.file.Path resourcesDir;
    private Label resolutionLabel;

    /** Opens the Project Setup checklist hub; wired by {@link UIManager}. */
    private Runnable onProjectSetup;
    /** Opens the Project Settings dialog; the same action the Project menu fires. */
    private Runnable onProjectSettings;
    /** Opens the Manage Capture Targets dialog; wired by {@link UIManager}. */
    private Runnable onManageCaptureTargets;
    /** Opens the Launch Target dialog (what the bot launches); wired by {@link UIManager}. */
    private Runnable onManageLaunchTarget;
    private Runnable onActivityFlow;
    /** Opens the Parameters dialog; the same action the Project menu fires. */
    private Runnable onParameters;

    /** Turns the reader-mode preview on and off, and what it is when the bar is built. */
    private Runnable onPreviewAsUser;
    /** Persists the debug-output toggle to the project; wired by {@link UIManager}. */
    private Consumer<Boolean> onToggleDebugOutput;
    /** The debug-output toggle's initial (persisted) state — read by {@link UIManager} before building the bar. */
    private boolean debugOutputInitial = true;
    /** Opens the Input &amp; Clicks dialog over the project's settings; wired by {@link UIManager}. */
    private Runnable onConfigureInput;
    /** Starts the remote pilot server and shows the pairing dialog; wired by {@link UIManager}. */
    private Runnable onEnableRemotePilot;
    /** Opens the live overlay template-capture over the default window; wired by {@link UIManager}. */
    private Runnable onCaptureTemplates;
    /** Opens the program-shape overlay authoring editor; wired by {@link UIManager}. */
    private Runnable onOverlayEditor;
    /** Opens the overlay editor already recording; wired by {@link UIManager}. */
    private Runnable onRecordMacro;
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
     * Creates the left-side group: Undo and Redo, and nothing else. Compile used to live here and now sits with
     * Run in {@link #createExecutionGroup()} — it is the first step of the same "make this bot go" sequence, and
     * keeping it here made the left group wide enough to matter when the window narrows. Undo/Redo stay because
     * they're two glyph-sized buttons that edit code rather than run it.
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

        HBox group = new HBox(5, undoButton, redoButton);
        group.setAlignment(Pos.CENTER_LEFT);
        group.setPadding(new Insets(0, 10, 0, 0));
        return group;
    }

    /** Sets the callback invoked when the toolbar's Project Setup button is clicked. */
    public void setOnProjectSetup(Runnable callback) {
        this.onProjectSetup = callback;
    }

    /** Sets the callback invoked when the toolbar's Project Settings button is clicked. */
    public void setOnProjectSettings(Runnable callback) {
        this.onProjectSettings = callback;
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

    /**
     * Sets the callback that opens the Input &amp; Clicks dialog. It replaced a {@code 🖱 Game} toggle that
     * carried the real-input flag alone: the button now has no state of its own to seed, because the values
     * live in the project's own settings and the dialog reads them when it opens.
     */
    public void setOnConfigureInput(Runnable callback) {
        this.onConfigureInput = callback;
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

    /** Sets the callback invoked when the toolbar's Parameters button is clicked. */
    public void setOnParameters(Runnable callback) {
        this.onParameters = callback;
    }

    /**
     * Wires the reader-mode toggle. Reader mode renders the bot the way somebody who only wants to <em>run</em>
     * it sees it — full colour, no controls — and until now an author could not get to it at all: the only
     * switch was the banner reader mode itself draws, which is to say it was reachable only from a project that
     * had already been installed from the gallery.
     *
     * @param initial whether the project is in reader mode already (an installed bot opens that way)
     */
    /** @see MenuBarManager#setOnPreviewAsUser */
    public void setOnPreviewAsUser(Runnable onPreview) {
        this.onPreviewAsUser = onPreview;
    }

    /** Sets the callback invoked when the toolbar's Record button is clicked (overlay + recording). */
    public void setOnRecordMacro(Runnable callback) {
        this.onRecordMacro = callback;
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
        Button projectSetupButton = new Button("🧭 Setup");
        projectSetupButton.getStyleClass().add("toolbar-btn");
        projectSetupButton.setTooltip(new Tooltip(
                "Set the project up to run: launch target, capture target, resolution and templates in one checklist"));
        projectSetupButton.setOnAction(e -> {
            if (onProjectSetup != null) onProjectSetup.run();
        });

        Button projectSettingsButton = new Button("⚙ Settings");
        projectSettingsButton.getStyleClass().add("toolbar-btn");
        projectSettingsButton.setTooltip(new Tooltip(
                "Project settings: the standard resolution templates are authored at, and favourite methods"));
        projectSettingsButton.setOnAction(e -> {
            if (onProjectSettings != null) onProjectSettings.run();
        });

        captureButton = new Button(captureButtonText());
        captureButton.getStyleClass().add("toolbar-btn");
        captureButton.setTooltip(new Tooltip("Manage screen / window capture targets (current default shown)"));
        captureButton.setOnAction(e -> {
            if (onManageCaptureTargets != null) onManageCaptureTargets.run();
        });
        pinWidth(captureButton);

        launchTargetButton = new Button(launchTargetText(launchTargetSpec));
        launchTargetButton.getStyleClass().add("toolbar-btn");
        launchTargetButton.setTooltip(new Tooltip(
                "Choose what the bot launches at startup — a Steam/Epic game, an executable, or an emulator app"));
        launchTargetButton.setOnAction(e -> {
            if (onManageLaunchTarget != null) onManageLaunchTarget.run();
        });
        pinWidth(launchTargetButton);
        resolveLaunchArtwork(launchTargetSpec);

        // Starts the configured target without compiling and running the bot. Its label is deliberately
        // constant ("▶ Launch" never becomes "Launching…"), so unlike its two neighbours it cannot change
        // width mid-session and re-wrap the bar — the reason those two are pinned. Progress is reported on
        // the status line instead.
        quickLaunchButton = QuickLaunch.button(resourcesDir, this::reportQuickLaunch);
        quickLaunchButton.setText("▶ Launch");
        quickLaunchButton.getStyleClass().add("toolbar-btn");

        Button activityFlowButton = new Button("🔀 Flow");
        activityFlowButton.getStyleClass().add("toolbar-btn");
        activityFlowButton.setTooltip(new Tooltip(
                "Define the bot's activities and wire the order they run in"));
        activityFlowButton.setOnAction(e -> {
            if (onActivityFlow != null) onActivityFlow.run();
        });

        Button parametersButton = new Button("🎚 Parameters");
        parametersButton.getStyleClass().add("toolbar-btn");
        parametersButton.setTooltip(new Tooltip(
                "The project's variables: every value the bot reads, with its tag and its editor"));
        parametersButton.setOnAction(e -> {
            if (onParameters != null) onParameters.run();
        });

        Button remotePilotButton = new Button("🎮 Pilot");
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

        // The same action as View ▸ Preview as user, not a second thing that sounds like it. This used to be
        // a "Reader mode" toggle that hid the editor's controls in place — a third rendering of the project
        // that answered "what does a user see?" with something no user ever sees. The Runner is the answer.
        Button previewAsUserButton = new Button("👁 Preview");
        previewAsUserButton.getStyleClass().add("toolbar-btn");
        previewAsUserButton.setTooltip(new Tooltip(
                "Open this bot the way someone who only runs it sees it — the Runner window, with the "
                        + "switches and values you chose to expose. Its header brings you back."));
        previewAsUserButton.setOnAction(e -> {
            if (onPreviewAsUser != null) onPreviewAsUser.run();
        });

        Button inputConfigButton = new Button("🖱 Input");
        inputConfigButton.getStyleClass().add("toolbar-btn");
        inputConfigButton.setTooltip(new Tooltip(
                "Configure how the bot clicks and looks: click delays, match confidence, and whether to drive "
                        + "the real mouse and keyboard.\n\n"
                        + "Turn real input on when the target is a game. Games ignore the quiet background "
                        + "clicks BotMaker sends by default, so it drives the real mouse and keyboard instead — "
                        + "the pointer moves to each click and returns, and the game window is raised.\n\n"
                        + "The settings are saved with your project, so they travel with the code and apply "
                        + "when the bot runs outside the Studio."));
        inputConfigButton.setOnAction(e -> {
            if (onConfigureInput != null) onConfigureInput.run();
        });

        Button captureTemplatesButton = new Button("✂ Templates");
        captureTemplatesButton.getStyleClass().add("toolbar-btn");
        captureTemplatesButton.setTooltip(new Tooltip(
                "Cut and manage the template images the bot matches against"));
        captureTemplatesButton.setOnAction(e -> {
            if (onCaptureTemplates != null) onCaptureTemplates.run();
        });

        Button overlayEditorButton = new Button("⧉ Overlay");
        overlayEditorButton.getStyleClass().add("toolbar-btn");
        overlayEditorButton.setTooltip(new Tooltip(
                "Build the bot over the running game: a compact block tree on top of the target window"));
        overlayEditorButton.setOnAction(e -> {
            if (onOverlayEditor != null) onOverlayEditor.run();
        });

        // The same overlay, opened straight into recording. Its own button because "record what I do in the
        // game" is how the tool is reached for, and routing it through ⧉ Overlay → ● Record made the feature
        // look absent: the standalone Record Macro button was dropped in 2026-07 and nothing replaced it.
        Button recordButton = new Button("⏺ Record");
        recordButton.getStyleClass().add("toolbar-btn");
        recordButton.setTooltip(new Tooltip(
                "Open the overlay and start recording real clicks and keys into the current activity"));
        recordButton.setOnAction(e -> {
            if (onRecordMacro != null) onRecordMacro.run();
        });

        Button resourcesButton = new Button("🗂 Resources");
        resourcesButton.getStyleClass().add("toolbar-btn");
        resourcesButton.setTooltip(new Tooltip("Browse the project's resource files"));
        resourcesButton.setOnAction(e -> {
            if (onAccessResources != null) onAccessResources.run();
        });

        resolutionLabel = new Label(resolutionText());
        resolutionLabel.getStyleClass().add("toolbar-resolution");
        // A Label's default minimum is "as small as the row will make me", which is what clipped the text
        // once the bar was under pressure; the padding that keeps it level with its button siblings lives
        // in .toolbar-resolution.
        resolutionLabel.setMinHeight(Region.USE_PREF_SIZE);
        resolutionLabel.setTooltip(new Tooltip("Project standard resolution · primary screen resolution"));

        FlowPane group = new FlowPane(Orientation.HORIZONTAL, 5, 5,
                // Launch before Capture: you pick what the bot opens, then where it looks — and a game's
                // window can only be picked as a capture target once the game is actually up.
                // Settings sits next to Setup: the checklist is the guided path, this is the same project's
                // stored values (the resolution the label at the end of this bar is reading) in one dialog.
                projectSetupButton, projectSettingsButton, launchTargetButton, quickLaunchButton, captureButton,
                // Flow then Parameters: the activities are drawn first, and their values are what the drawing
                // reads. Both are Project-menu actions with no button until now.
                activityFlowButton, parametersButton,
                remotePilotButton,
                debugOutputButton, previewAsUserButton, inputConfigButton, captureTemplatesButton,
                overlayEditorButton, recordButton,
                resourcesButton, resolutionLabel);
        group.setAlignment(Pos.CENTER);
        group.setMinWidth(0);
        return group;
    }

    /** Locks a button to {@link #TARGET_BTN_WIDTH}, ellipsizing a label too long to fit rather than growing. */
    private static void pinWidth(Button button) {
        button.setMinWidth(TARGET_BTN_WIDTH);
        button.setPrefWidth(TARGET_BTN_WIDTH);
        button.setMaxWidth(TARGET_BTN_WIDTH);
        button.setTextOverrun(OverrunStyle.ELLIPSIS);
    }

    /**
     * The debug-output toggle's label for a given state. The two states are deliberately the <em>same
     * length</em> (a filled vs hollow dot, not "on"/"off"): an unequal pair changes the button's width on
     * click, which re-wraps the bar and — because a wrapped row raises the scene root's minimum height —
     * used to grow the whole window. See the min-size clamps in {@code UIManager.createScene()}.
     */
    private static String debugOutputText(boolean on) {
        return on ? "🐞 Debug ●" : "🐞 Debug ○";
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
        // The quick-launch button reads the target off disk, so it has to be rebound whenever it changes —
        // otherwise it stays disabled after the very first target is set, or launches the previous one.
        if (quickLaunchButton != null) {
            QuickLaunch.bind(quickLaunchButton, resourcesDir, this::reportQuickLaunch);
        }
    }

    /**
     * The project's resources dir, needed before {@link #createCaptureGroup()} so quick launch can read
     * {@code launch.target}. Set by {@link UIManager} when it wires the bar.
     */
    public void setResourcesDir(java.nio.file.Path resourcesDir) {
        this.resourcesDir = resourcesDir;
    }

    /** Quick launch has no status label of its own up here, so it reports on the shared status line. */
    private void reportQuickLaunch(boolean ok, String message) {
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent((ok ? "" : "⚠ ") + message));
    }

    /** "🚀 " + the target's short name, or "🚀 Launch Target" when none is set. */
    private static String launchTargetText(String spec) {
        return launchTargetText(spec, null);
    }

    private static String launchTargetText(String spec, String displayName) {
        String name = LaunchSpec.shortLabel(spec, displayName);
        if (name.length() > CAPTURE_LABEL_MAX) name = name.substring(0, CAPTURE_LABEL_MAX - 1) + "…";
        return "🚀 " + name;
    }

    /**
     * Resolves a {@code <platform>:<id>} spec to its installed game so the button can show the real title and a
     * small cover. The scan reads JSON/VDF off disk, so it runs on a daemon thread and hops back to the FX
     * thread; a spec that resolves to nothing (a plain exe, an uninstalled game) simply leaves the label as-is.
     */
    private void resolveLaunchArtwork(String spec) {
        LaunchSpec parsed = LaunchSpec.parse(spec);
        if (parsed == null || parsed.kind() == LaunchKind.UNKNOWN) return;
        Button button = launchTargetButton;
        Thread scan = new Thread(() -> {
            InstalledGame game = GameLibraries.findGame(parsed.kind().id(), parsed.token()).orElse(null);
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
     * Creates the right-side group: Compile, Run, Debug, Follow, Stop, Step, Continue — the whole "make this bot
     * go" sequence in the order you'd use it.
     *
     * <p>A {@link FlowPane}, like {@link #createCaptureGroup()}, and for the same reason turned inside out: as an
     * {@code HBox} this cluster held one line at any width, so a narrow window took the space out of the
     * <em>centre</em> group instead, which then wrapped onto three or four rows and pushed the bar's height up.
     * Wrapping here lets the two groups share the shortfall a row at a time. {@code minWidth = 0} for the same
     * reason the capture group sets it: the group's preferred width must not become a floor on the stage's.
     *
     * <p>What makes the wrap actually happen is {@code prefWrapLength}, bound to a share of the toolbar width in
     * {@code UIManager.createScene()} — a {@code BorderPane} hands its right child that child's <em>preferred</em>
     * width, so a FlowPane left to compute its own would report one row's worth and never be squeezed.
     */
    public FlowPane createExecutionGroup() {
        Button compileButton = new Button("⚙ Compile");
        compileButton.getStyleClass().add("toolbar-btn");
        compileButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.CompilationRequestedEvent()));

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

        FlowPane group = new FlowPane(Orientation.HORIZONTAL, 5, 5,
                compileButton, runButton, debugButton, followButton, unifiedStopButton, stepOverButton, continueButton);
        group.setAlignment(Pos.CENTER_RIGHT);
        group.setMinWidth(0);
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
