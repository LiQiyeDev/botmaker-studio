package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.ActionContext;
import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.api.ToolbarItem;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.HostActionContext;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class ToolbarManager {

    /**
     * How many rows either toolbar group may wrap onto before the rest goes into its {@code »} menu. Two,
     * because the bar sits between the menu bar and the canvas: a third row is already more toolbar than most
     * windows have room for above the code.
     */
    private static final int TOOLBAR_MAX_ROWS = 2;

    private final EventBus eventBus;
    private final ProjectSettingsService settings;
    // Controls
    private Button undoButton, redoButton;
    private Button runButton, debugButton, followButton, unifiedStopButton;
    private Button stepOverButton, continueButton;
    private Label resolutionLabel;

    /** Opens the Project Settings dialog; the same action the Project menu fires. */
    private Runnable onProjectSettings;
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
            // Every item re-reads itself, not just this one: a supplier is a field read by contract, so
            // refreshing the bar costs less than knowing which button a settings change touched.
            refreshItems();
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
            if (undoButton != null) {
                undoButton.setDisable(!event.canUndo());
                undoButton.setTooltip(new Tooltip(tip("Undo", event.canUndo() ? event.undoLabel() : "", "Ctrl+Z")));
            }
            if (redoButton != null) {
                redoButton.setDisable(!event.canRedo());
                redoButton.setTooltip(new Tooltip(tip("Redo", event.canRedo() ? event.redoLabel() : "", "Ctrl+Y")));
            }
        }, true);
    }

    /** "Undo the change to loadTargets, in 4 files (Ctrl+Z)" — or just the verb, when there is nothing to say. */
    private static String tip(String verb, String what, String accelerator) {
        String head = what == null || what.isBlank() ? verb : verb + " " + what;
        return head + " (" + accelerator + ")";
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

    /** Sets the callback invoked when the toolbar's Project Settings button is clicked. */
    public void setOnProjectSettings(Runnable callback) {
        this.onProjectSettings = callback;
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
     * Creates the center group: every project-level action as its own visible button, wrapping onto a second
     * row when the window narrows and into a {@code »} menu after that ({@link OverflowBar}).
     *
     * <p>There used to be a "⋯ More" menu here that hid Capture Templates / Overlay Editor / Resources
     * <em>at every width</em>, and dropping it for a wrapping pane was right: an action you can't see is an
     * action you don't know exists, and there was room for those three. What the pane then did was wrap
     * without limit, so at narrow widths the bar grew rows until it painted over the menu bar. The cap is the
     * middle position — nothing is hidden while there is room for it, and the overflow appears only at a width
     * where the alternative was a toolbar taller than the canvas.
     */
    public OverflowBar createCaptureGroup() {
        // Studio's own items, spelled the way a plugin would have to spell them. Going through the same
        // record and the same builder is the point rather than a tidiness: an item built by a second path is
        // an item that drifts, and the first thing anybody would notice is that the host's buttons are the
        // ones that line up. The orders below reproduce, exactly, the sequence this bar was hand-arranged
        // into before any of it was data — see the reading-order note on the return.
        List<Placed> placed = new ArrayList<>();
        ActionContext ctx = actionContext();

        // 🧭 Setup stood first in this group until 2026-08-31 and is the SDK plugin's 📋 Project Setup item
        // now, placed by the same merge as the pilot's and the capture items'. Every row of that checklist
        // reads a file the plugin owns, so there is nothing left for the shell to wire.

        place(placed, ctx, ToolbarItem.of("settings", "⚙ Settings",
                "Project settings: the standard resolution templates are authored at, and favourite methods",
                ToolbarGroup.PROJECT, 20, c -> run(onProjectSettings)));

        // 🎯 Capture Targets stood here at PROJECT/30 until 2026-08-31 and is the SDK plugin's item now,
        // placed by the same merge as any other plugin's. The list it manages is capture.json, which is the
        // plugin's file — so the shell no longer reads it and can no longer put the current default's name on
        // the button. That label is what the move cost: toolbarItems() is called with no StudioServices, so a
        // plugin's item has no project to name.

        place(placed, ctx, ToolbarItem.of("flow", "🔀 Flow",
                "Define the bot's activities and wire the order they run in",
                ToolbarGroup.AUTHORING, 10, c -> run(onActivityFlow)));

        place(placed, ctx, ToolbarItem.of("parameters", "🎚 Parameters",
                "The project's variables: every value the bot reads, with its tag and its editor",
                ToolbarGroup.AUTHORING, 20, c -> run(onParameters)));

        // 🎮 Pilot stood here until 2026-08-30 and is the SDK plugin's item now, placed by the same merge as
        // any other plugin's. It is the case this surface was added for: a whole feature behind one button,
        // where the host owns the bar and the plugin owns everything the press opens.

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
        // Not a ToolbarItem, and deliberately: it is a ToggleButton with two equal-width states, and there is
        // no toggle kind in the contract because no plugin item needs one yet. Adding one for the host's own
        // button would be designing a surface against its only implementor, which is the mistake the Assets
        // and SourceChoice reversal of 2026-08-27 recorded. Debug also stays Studio's for now — "how a bot is
        // debugged" is a plugin's answer to give, and there is no second plugin to give one.
        placed.add(new Placed(ToolbarGroup.RUN, 20, "debug-output", debugOutputButton, null));

        // The same action as View ▸ Preview as user, not a second thing that sounds like it. This used to be
        // a "Reader mode" toggle that hid the editor's controls in place — a third rendering of the project
        // that answered "what does a user see?" with something no user ever sees. The Runner is the answer.
        place(placed, ctx, ToolbarItem.of("preview", "👁 Preview",
                "Open this bot the way someone who only runs it sees it — the Runner window, with the "
                        + "switches and values you chose to expose. Its header brings you back.",
                ToolbarGroup.RUN, 30, c -> run(onPreviewAsUser)));

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
        // The one tooltip too long to read as a record argument. It stays a hand-built button until the
        // action itself moves, at which point the sentence moves with it — a five-paragraph explanation of
        // how a bot clicks belongs to whoever owns the clicking.
        placed.add(new Placed(ToolbarGroup.TOOLS, 10, "input", inputConfigButton, null));

        // ✂ Templates stood here at TOOLS/20 until 2026-08-31 and is the SDK plugin's ✂ Capture Templates
        // now, merged into this same slot. It is the second whole feature to leave through this surface, and
        // the clearest case for it: the host owns the bar, the plugin owns the capture target it reads, the
        // pixels it grabs and the picture folder it writes.

        place(placed, ctx, ToolbarItem.of("overlay", "⧉ Overlay",
                "Build the bot over the running game: a compact block tree on top of the target window",
                ToolbarGroup.TOOLS, 30, c -> run(onOverlayEditor)));

        // The same overlay, opened straight into recording. Its own button because "record what I do in the
        // game" is how the tool is reached for, and routing it through ⧉ Overlay → ● Record made the feature
        // look absent: the standalone Record Macro button was dropped in 2026-07 and nothing replaced it.
        place(placed, ctx, ToolbarItem.of("record", "⏺ Record",
                "Open the overlay and start recording real clicks and keys into the current activity",
                ToolbarGroup.TOOLS, 40, c -> run(onRecordMacro)));

        place(placed, ctx, ToolbarItem.of("resources", "🗂 Resources",
                "Browse the project's resource files",
                ToolbarGroup.TOOLS, 50, c -> run(onAccessResources)));

        resolutionLabel = new Label(resolutionText());
        resolutionLabel.getStyleClass().add("toolbar-resolution");
        // A Label's default minimum is "as small as the row will make me", which is what clipped the text
        // once the bar was under pressure; the padding that keeps it level with its button siblings lives
        // in .toolbar-resolution.
        resolutionLabel.setMinHeight(Region.USE_PREF_SIZE);
        resolutionLabel.setTooltip(new Tooltip("Project standard resolution · primary screen resolution"));
        // A read-out, not an action, and the reason ToolbarItem has no kind for one: a plugin that wants to
        // state a fact on the bar has not asked for that yet, and inventing the surface before it does is how
        // a contract acquires a member with one implementor.
        placed.add(new Placed(ToolbarGroup.STUDIO, 100, "resolution", resolutionLabel, null));

        // Every plugin's items, merged into the same groups. PluginHost has already sorted them and already
        // refused anything claiming ToolbarGroup.STUDIO, so what arrives here is only ever placeable.
        for (ToolbarItem item : PluginHost.toolbarItems()) place(placed, ctx, item);

        // The reading order the bar was hand-arranged into, now stated as four groups rather than as an
        // argument list — and it comes out identical, which is the acceptance test for this change.
        //
        // PROJECT: Launch target before Capture target — you pick what the bot opens, then where it looks,
        // and a game's window can only be picked as a capture target once the game is actually up. Settings
        // sits next to Setup: the checklist is the guided path, this is the same project's stored values
        // (the resolution the label at the end of this bar is reading) in one dialog.
        // AUTHORING: Flow then Parameters — the activities are drawn first, and their values are what the
        // drawing reads.
        // RUN, then TOOLS: what is observed, then what observes it.
        //
        // The order is also the order things drop into the » menu: what is furthest right goes first, so the
        // readout and the least-reached-for tools are what a narrow window costs you, not Project Setup.
        placed.sort(Comparator.comparing(Placed::group)
                .thenComparingInt(Placed::order)
                .thenComparing(Placed::id));
        this.items = List.copyOf(placed);
        Node[] nodes = new Node[placed.size()];
        for (int i = 0; i < placed.size(); i++) nodes[i] = placed.get(i).node();
        return new OverflowBar(5, 5, TOOLBAR_MAX_ROWS, HPos.CENTER, nodes);
    }

    /**
     * One thing on the bar: an item and the node built from it, or a node the host built by hand.
     *
     * <p>The second case is not a loose end. A {@link ToolbarItem} describes a button and there are three
     * things up here that are not one — a control {@code QuickLaunch} builds and rebinds itself, a toggle,
     * and a read-out — so the placement model has to hold a bare {@link Node} beside a described one. Every
     * attempt to make the record cover all three would have added a member with exactly one implementor.
     */
    private record Placed(ToolbarGroup group, int order, String id, Node node, ToolbarItem item) {}

    /** What is currently on the bar, in draw order, so {@link #refreshItems()} can re-read the suppliers. */
    private List<Placed> items = List.of();

    /** Builds {@code item}'s button, records where it goes, and hands the button back for any pinning. */
    private Button place(List<Placed> into, ActionContext ctx, ToolbarItem item) {
        Button button = ToolbarItems.button(item, ctx);
        into.add(new Placed(item.group(), item.order(), item.id(), button, item));
        return button;
    }

    /** Runs a wired callback, or does nothing when the bar was built before that callback was set. */
    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }

    /**
     * Re-reads every item's label and icon.
     *
     * <p>Cheap by contract — a supplier is a field read and a format — so this runs on the events that
     * already redraw parts of the bar rather than on a timer.
     */
    private void refreshItems() {
        for (Placed p : items) {
            if (p.item() != null && p.node() instanceof Button button) ToolbarItems.refresh(button, p.item());
        }
    }

    /**
     * The facts a toolbar click is handed.
     *
     * <p>Built once and reused: {@link HostActionContext} reads everything at call time, so one context is
     * correct for every project opened after it — which a captured one would not be, and a button outlives
     * the project it was built under.
     */
    private ActionContext actionContext() {
        return new HostActionContext(
                () -> settings == null ? null : settings.projectConfig(),
                () -> "");
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

    /**
     * "🖵 W×H": the primary screen's resolution.
     *
     * <p>It used to lead with "Std W×H", the project's standard capture resolution, which the editor has
     * stopped holding (2026-09-01) — that size describes the pictures, so the plugin that takes them owns it
     * and states it on its own toolbar item. What is left is a fact about this machine, which is the host's.
     */
    private String resolutionText() {
        javafx.geometry.Rectangle2D sb = javafx.stage.Screen.getPrimary().getBounds();
        return "🖵 " + (int) sb.getWidth() + "×" + (int) sb.getHeight();
    }

    /**
     * Creates the right-side group: Compile, Run, Debug, Follow, Stop, Step, Continue — the whole "make this bot
     * go" sequence in the order you'd use it.
     *
     * <p>An {@link OverflowBar}, like {@link #createCaptureGroup()}, and for the same reason turned inside out:
     * as an {@code HBox} this cluster held one line at any width, so a narrow window took the space out of the
     * <em>centre</em> group instead, which then wrapped onto three or four rows and pushed the bar's height up.
     * Wrapping here lets the two groups share the shortfall a row at a time.
     *
     * <p>What makes the wrap actually happen is the preferred width bound to a share of the toolbar in
     * {@code UIManager.createScene()} — a {@code BorderPane} hands its right child that child's <em>preferred</em>
     * width, so a group left to compute its own would report one row's worth and never be squeezed.
     */
    public OverflowBar createExecutionGroup() {
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

        // These two carry .toolbar-btn like the other five. Without it they fell back to Modena's own button,
        // which in a dark theme is a shape with no fill — the "Compile/Follow group renders as a bare line".
        stepOverButton = new Button("⤵ Step");
        stepOverButton.getStyleClass().add("toolbar-btn");
        stepOverButton.setDisable(true);
        stepOverButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugStepOverRequestedEvent()));

        continueButton = new Button("⏩ Cont");
        continueButton.getStyleClass().add("toolbar-btn");
        continueButton.setDisable(true);
        continueButton.setOnAction(e -> eventBus.publish(new CoreApplicationEvents.DebugContinueRequestedEvent()));

        return new OverflowBar(5, 5, TOOLBAR_MAX_ROWS, HPos.RIGHT,
                compileButton, runButton, debugButton, followButton, unifiedStopButton, stepOverButton, continueButton);
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
