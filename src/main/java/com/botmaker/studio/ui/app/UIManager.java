package com.botmaker.studio.ui.app;

import com.botmaker.studio.config.VersionInfo;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectMode;
import com.botmaker.studio.project.ProjectOpenMigrations;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.pilot.RemotePilotUi;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;
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
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.EnumMap;
import java.util.function.Consumer;

/**
 * The application shell: it assembles the main window out of the panel managers, and releases what that window
 * acquired when the project it belongs to goes away.
 *
 * <p>It is a coordinator, not a container of features. Each area of the window is a collaborator built here and
 * handed callbacks — {@link EditorCanvas}, {@link DiagnosticsPanel}, {@link IdentityCluster},
 * {@link VcsPanel}, {@link RemotePilotUi} — and none of them holds a reference back. The actions behind the
 * menus and the toolbar live in {@link StudioActions}.
 */
public class UIManager implements ProjectWindow {

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

    private final ProjectSettingsService projectSettingsService;
    private final ToolbarManager toolbarManager;
    private final EventLogManager eventLogManager;
    private final MenuBarManager menuBarManager;
    private final FileExplorerManager fileExplorerManager;
    /** Remote Pilot in full: the server, the private-display launcher, and every dialog they put on screen. */
    private final RemotePilotUi remotePilot;
    /** Every menu/toolbar action, and the GitHub services that back the sharing ones. */
    private final StudioActions actions;

    // Theme management
    private Scene scene;
    private Parent root;
    /** Kept so {@link #dispose()} can drop it from {@link BlockTheme}'s <b>static</b> listener list — otherwise
     *  every project switch leaves a lambda holding the previous window's whole scene graph alive. */
    private final Consumer<BlockTheme.ThemeType> themeListener;
    /** Built with the scene; holds the second of this window's two theme listeners. */
    private IdentityCluster identityCluster;

    private VcsPanel vcsPanel;

    /** The centre column — block canvas, Reader banner, scrolling. Built by {@link #createScene()}. */
    private EditorCanvas editorCanvas;
    /** The Errors bottom tab. Built by {@link #createScene()}. */
    private DiagnosticsPanel diagnosticsPanel;
    /** The bottom tool window's tabs, keyed by the closed set so nothing selects one by index. */
    private final EnumMap<BottomTab, Tab> bottomTabs = new EnumMap<>(BottomTab.class);
    /** Restores the dividers and the open tab at open, and writes them back from {@link #dispose()}. */
    private WorkspaceLayoutStore workspaceLayout;

    private Label statusLabel;
    private TextArea outputArea;
    private TabPane bottomTabPane;
    private Consumer<Void> onSelectProject;
    /** View ▸ Preview as user — hands the project to the Runner window for this session only. */
    private Runnable onPreviewAsUser;

    /**
     * Builds the window for {@code ctx}'s project on {@code primaryStage}.
     *
     * <p>Two parameters, because there are exactly two things here: the project, and the window it is shown
     * in. This took eleven — the project's services listed one by one, four of them (the analyzer, the
     * library/activity services, the execution service) never becoming fields at all, present only to be
     * handed to {@link StudioActions} and {@link FileExplorerManager} one layer down.
     */
    public UIManager(StudioContext ctx, Stage primaryStage) {
        this.eventBus = ctx.eventBus();
        this.codeEditorService = ctx.codeEditorService();
        this.diagnosticsManager = ctx.diagnosticsManager();
        this.primaryStage = primaryStage;
        this.config = ctx.config();
        this.state = ctx.state();

        // Editor settings (capture targets + default) — the project's own, not a second one over the same
        // (config, state, eventBus). The capture service honors the default target so pickers stop re-asking
        // which screen to use.
        this.projectSettingsService = ctx.projectSettingsService();
        ScreenCaptureService screenCaptureService = new ScreenCaptureService(projectSettingsService);
        this.remotePilot = new RemotePilotUi(
                primaryStage, eventBus, config, projectSettingsService, ctx.codeExecutionService());

        this.toolbarManager = new ToolbarManager(eventBus, projectSettingsService);
        this.eventLogManager = new EventLogManager(eventBus);
        this.menuBarManager = new MenuBarManager(primaryStage);

        // Startup banner: which local builds are actually running (distinct from the GitHub update check).
        System.out.println(VersionInfo.banner(config.projectPath()));

        // Before the file explorer exists, which is the point: a migration can delete a file the tree would
        // otherwise go on listing.
        ProjectOpenMigrations.run(config, state, eventBus);

        this.fileExplorerManager = new FileExplorerManager(ctx);

        this.actions = new StudioActions(ctx, primaryStage, screenCaptureService,
                remotePilot, menuBarManager, toolbarManager,
                () -> ProjectRecoveryAction.recover(ctx, fileExplorerManager::refreshTree));
        this.actions.wire();

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
    @Override
    public void dispose() {
        if (workspaceLayout != null) {
            workspaceLayout.save();
            workspaceLayout = null;
        }
        remotePilot.close();
        BlockTheme.removeThemeChangeListener(themeListener);
        if (identityCluster != null) {
            identityCluster.dispose();
            identityCluster = null;
        }
        eventLogManager.shutdown();
    }

    /**
     * Opens the Project Setup checklist hub — the auto-open-on-creation target, called from
     * {@code BotMakerStudio.finishOpen}, which is why it is public here as well as on {@link StudioActions}.
     */
    public void openProjectSetup() {
        actions.openProjectSetup();
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

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.OpenResourceManagerEvent.class,
                e -> actions.openResourceManager(), true);
        eventBus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, event -> {
            if (editorCanvas != null) editorCanvas.handleBlocksUpdate(event);
        }, true);
        eventBus.subscribe(CoreApplicationEvents.OutputAppendedEvent.class, event -> {
            // getLength(), not getText().length(): the latter copies the whole console buffer to measure it,
            // once per line of bot output.
            if (outputArea.getLength() > 10_000) {
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
            if (diagnosticsPanel != null) diagnosticsPanel.update(diagnosticsManager.getDiagnostics());
            statusLabel.setText(diagnosticsManager.getErrorSummary());
        }, true);
        eventBus.subscribe(CoreApplicationEvents.ProgramStartedEvent.class,
                e -> selectBottomTab(BottomTab.TERMINAL), true);
        eventBus.subscribe(CoreApplicationEvents.DebugSessionStartedEvent.class,
                e -> selectBottomTab(BottomTab.TERMINAL), true);
        eventBus.subscribe(CoreApplicationEvents.InputRequestedEvent.class, this::promptForInput, true);
    }

    /** Shows a modal prompt when the running bot blocks on stdin, then sends the entered line to the program. */
    private void promptForInput(CoreApplicationEvents.InputRequestedEvent event) {
        TextInputDialog dialog = new TextInputDialog();
        ThemedWindows.apply(dialog);
        dialog.initOwner(primaryStage);
        dialog.setTitle("Bot needs input");
        dialog.setHeaderText("The bot is waiting for input");
        dialog.setContentText(event.kind() == null ? "Enter input:" : event.kind().prompt());
        dialog.showAndWait().ifPresent(value ->
                eventBus.publish(new CoreApplicationEvents.SendInputEvent(value)));
    }

    @Override
    public Scene createScene() {
        menuBarManager.setOnSelectProject(v -> { if (onSelectProject != null) onSelectProject.accept(null); });
        menuBarManager.setOnPreviewAsUser(() -> { if (onPreviewAsUser != null) onPreviewAsUser.run(); });
        // The toolbar's 👁 is the same action, not a lookalike — see ToolbarManager for what it replaced.
        toolbarManager.setOnPreviewAsUser(() -> { if (onPreviewAsUser != null) onPreviewAsUser.run(); });

        // --- 1. Top Bar Construction (edit controls left, project actions centered, run controls right) ---
        // A BorderPane, not a FlowPane. Only the *center* group wraps; left and right are pinned to their
        // edges and never move when it does. A FlowPane cannot centre its middle child — it packs all three
        // units from the leading edge — which is why the project buttons used to sit left-aligned and why the
        // run cluster drifted with them. Each group is still an indivisible unit (its own HBox/FlowPane).
        HBox editControls = toolbarManager.createEditGroup();
        editControls.setAlignment(Pos.CENTER_LEFT);

        OverflowBar executionControls = toolbarManager.createExecutionGroup();
        this.identityCluster = new IdentityCluster(primaryStage, actions.gitHubAuth(), actions.gitHubClient(),
                () -> selectBottomTab(BottomTab.VCS));
        HBox rightContainer = new HBox(10, executionControls, identityCluster.node());
        rightContainer.setAlignment(Pos.CENTER_RIGHT);
        rightContainer.setMinWidth(0);
        // Breathing room against the window edge, mirroring the padding createEditGroup() applies on its side.
        rightContainer.setPadding(new Insets(0, 10, 0, 0));

        OverflowBar captureControls = toolbarManager.createCaptureGroup();

        BorderPane topBar = new BorderPane();
        topBar.setLeft(editControls);
        topBar.setCenter(captureControls);
        topBar.setRight(rightContainer);
        BorderPane.setAlignment(editControls, Pos.CENTER_LEFT);
        BorderPane.setAlignment(rightContainer, Pos.CENTER_RIGHT);
        // Padding lives in .main-toolbar (blocks.css), not here: a setPadding call marks the property as
        // set by the author, which CSS may no longer override — so styling the bar's chrome in one place
        // requires *not* also setting it inline.
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
        // child's *preferred* width, and a wrapping group's preferred width is "whatever fits on one row".
        // Left alone it would therefore behave exactly like the HBox it replaced — pinned to one line, with
        // the centre group absorbing every pixel the window loses. Tying it to a share of the bar means it
        // stays one row while there is room for one (the share exceeds the cluster's natural width on any
        // normal window) and starts wrapping only once the bar is genuinely tight — which is the point at
        // which the centre group would otherwise have been wrapping alone.
        executionControls.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(EXEC_MIN_WRAP_PX, topBar.getWidth() * EXEC_WIDTH_SHARE), topBar.widthProperty()));
        // The centre group gets whatever the two edges leave, which BorderPane hands it without being asked.
        // It needs no width binding of its own: an OverflowBar answers a width-less height query against the
        // width it is currently laid out at, and — unlike the FlowPane it replaced — the answer is bounded by
        // its row cap, so the height this bar reserves can no longer be the height of four rows of buttons.
        topBar.setPrefHeight(Region.USE_COMPUTED_SIZE);
        topBar.setMinHeight(Region.USE_PREF_SIZE);
        // And the belt to that brace: a Region does not clip, so any future disagreement about the bar's
        // height paints over the menu bar rather than being cut off at it. This is the cut.
        Rectangle topBarClip = new Rectangle();
        topBarClip.widthProperty().bind(topBar.widthProperty());
        topBarClip.heightProperty().bind(topBar.heightProperty());
        topBar.setClip(topBarClip);

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
        editorCanvas = new EditorCanvas(codeEditorService, eventBus, state.isReaderMode(),
                config.projectName(), this::switchToEditorMode);

        // --- 4. Bottom Panel: Terminal/Errors ---
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.getStyleClass().add("console-area");
        outputArea.setContextMenu(consoleContextMenu(outputArea));

        diagnosticsPanel = new DiagnosticsPanel(diagnosticsManager, editorCanvas::scrollToBlock,
                () -> selectBottomTab(BottomTab.ERRORS));

        // VCS tool window — IntelliJ's Commit view docked beside Terminal (VcsPanel, shared with the dialog).
        vcsPanel = new VcsPanel(primaryStage, config.projectName(), config.projectPath(), actions.botPublisher(),
                actions.gitHubAuth(), actions.gitHubClient(), eventBus, actions::openPublishDialog);

        bottomTabs.clear();
        bottomTabs.put(BottomTab.TERMINAL, bottomTab(BottomTab.TERMINAL, outputArea));
        bottomTabs.put(BottomTab.ERRORS, bottomTab(BottomTab.ERRORS, diagnosticsPanel.node()));
        bottomTabs.put(BottomTab.EVENT_LOG, bottomTab(BottomTab.EVENT_LOG, eventLogManager.getView()));
        bottomTabs.put(BottomTab.VCS, bottomTab(BottomTab.VCS, vcsPanel.getView()));

        bottomTabPane = new TabPane();
        bottomTabPane.getTabs().addAll(bottomTabs.values());
        // Keep the changed-files tree fresh whenever the user opens the tab.
        Tab vcsTab = bottomTabs.get(BottomTab.VCS);
        bottomTabPane.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == vcsTab && vcsPanel != null) vcsPanel.refresh();
        });

        // --- 5. Layout Assembly ---
        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(editorCanvas.node(), bottomTabPane);
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

        // Both splits and every tab exist by now, so the remembered arrangement can go straight over the
        // defaults set above — before the scene is shown, so nothing is seen to jump into place.
        workspaceLayout = new WorkspaceLayoutStore(
                projectSettingsService, mainSplit, verticalSplit, bottomTabPane, bottomTabs);
        workspaceLayout.restore();

        statusLabel = new Label("Ready");
        statusLabel.setId("status-label");
        // Fill, hairline and padding come from blocks.css: unstyled, the last row of the window was the one
        // place Modena's own background showed through a dark theme.
        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        VBox root = new VBox(menuBarManager.getMenuBar(), toolbarColumn, mainSplit, statusLabel);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        // Initialize with the current theme - add the appropriate theme class
        ThemedWindows.applyThemeClass(root);

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

        // Unsized, like the loading screen and the Runner. A Scene built with a width and height carries that
        // size onto the Stage it is set on — and when the Stage is maximized the window manager refuses the
        // resize while the Scene keeps its 1000×700 anyway, so the editor laid itself out at 1000×700 inside a
        // full-screen window and the rest was the black nothing behind it. That was the black border.
        Scene scene = new Scene(root);
        this.scene = scene;

        // Block "state" styling (highlight / error / breakpoint / read-only) via pseudo-classes.
        ThemedWindows.addStylesheet(scene);

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

    /**
     * "Improve this bot" — flips the installed bot from Reader to Editor mode. Requires no GitHub account (the
     * fork/branch only materializes at PR time): it drops the local opt-in marker, commits the current state
     * locally so there's a restore point, then reloads so every block re-renders with its controls.
     */
    private void switchToEditorMode() {
        try {
            ProjectMode.switchToEditor(config.projectPath());
        } catch (IOException ex) {
            ThemedWindows.alert(Alert.AlertType.ERROR, "Couldn't switch to Editor mode: " + ex.getMessage(),
                    ButtonType.OK).showAndWait();
            return;
        }
        state.setReaderMode(false);
        // Commit the as-installed state locally (best-effort) so "Editor mode" has a clean starting point.
        // Daemon: a git commit that hangs must not keep the JVM alive after the user closes the window.
        Thread commit = new Thread(() -> {
            try {
                new ProjectVcs(config.projectPath()).commit("Start editing (switched from Reader mode)");
            } catch (Exception ignored) {
                // A missing/again-committed repo is fine; the reload below is what matters.
            }
            Platform.runLater(() ->
                    eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent()));
        }, "reader-to-editor");
        commit.setDaemon(true);
        commit.start();
    }

    /** Applies the current theme CSS class to the stored scene root. */
    private void applyThemeToScene() {
        ThemedWindows.applyThemeClass(root);
    }

    /** A closable-free bottom tab carrying its title from the closed set. */
    private static Tab bottomTab(BottomTab which, Node content) {
        Tab tab = new Tab(which.title(), content);
        tab.setClosable(false);
        return tab;
    }

    /** Copy / Clear for the console. The Errors list has its own, built by {@link DiagnosticsPanel}. */
    private static ContextMenu consoleContextMenu(TextArea console) {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> console.copy());
        MenuItem clear = new MenuItem("Clear");
        clear.setOnAction(e -> console.clear());
        return new ContextMenu(copy, new SeparatorMenuItem(), clear);
    }

    /** Raises a bottom tab. A no-op before {@code createScene()} has built them. */
    private void selectBottomTab(BottomTab which) {
        Tab tab = bottomTabs.get(which);
        if (bottomTabPane != null && tab != null) bottomTabPane.getSelectionModel().select(tab);
    }

    /**
     * Puts the canvas in its "Loading project…" state, for the gap between the window appearing and the entry
     * point being parsed. A no-op before {@code createScene()} has built the canvas.
     */
    public void showEditorLoading() {
        if (editorCanvas != null) editorCanvas.showLoading();
    }

    public void setOnSelectProject(Consumer<Void> callback) { this.onSelectProject = callback; }

    /**
     * Sets what View ▸ Preview as user does. The shell owns it rather than this window, because the answer is
     * to build a <em>different</em> window — see {@link ProjectWindow}.
     */
    public void setOnPreviewAsUser(Runnable callback) { this.onPreviewAsUser = callback; }
}
