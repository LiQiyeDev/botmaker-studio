package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.StatusMessageEvent;
import com.botmaker.studio.events.CoreApplicationEvents.UIBlocksUpdatedEvent;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.render.menu.StatementMenu;
import com.botmaker.studio.util.MethodSignature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.LABEL;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.PANEL;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.applyThemeClass;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.iconButton;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.label;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.warn;

/**
 * The <b>overlay authoring surface</b>: a small, always-on-top, independently-minimizable <em>translucent
 * HUD</em> that mirrors <em>the current shape of the program</em> as a compact, clickable, scrollable list of
 * one-line rows — one per block — reusing the live {@link CodeBlock} tree (not a second renderer). It is the
 * companion to the capture overlay: while you work in the target app, this shows where you are in the bot and
 * lets you grow it, either by hand or by <b>recording real input</b>.
 *
 * <p>Like the capture tool it is a <b>true overlay over a window target</b>: opening it requires the project's
 * default capture target to be a window, and on open it brings that window to the front and snaps it to the
 * project reference resolution (reusing {@link ScreenCaptureService#raiseWindow} /
 * {@link ScreenCaptureService#resizeTarget}). The stage itself is {@link StageStyle#TRANSPARENT} with rounded
 * semi-opaque panels (matching {@code OverlayToolbars}), so the app shows through the gaps.
 *
 * <p><b>Over a private session.</b> When a nested session is live the overlay targets <em>its</em> host window
 * instead of the configured desktop window — see {@link #sessionTarget}. The session's host window is a real
 * host-desktop window whose pixels an ordinary X capture reads (verified against a gamescope session on the
 * dev box), so nothing about the drawing or the capture path changes. Two things do: the window is named by
 * <em>id</em> rather than title, because gamescope renames its output window after the app inside it; and it is
 * never resized, because gamescope's output and internal sizes are launched equal on purpose and a resize is
 * what would break the 1:1 mapping the recorded coordinates depend on. Input needs no routing — gamescope
 * forwards host input into its Xwayland, so the existing global {@code :0} recording already sees the right
 * coordinates.
 *
 * <p><b>What this class is now.</b> It is the <em>coordinator</em>: the stage and its lifecycle, the event
 * subscriptions, and the FX-thread-confined pending state that sequences an edit against the re-parse it
 * causes ({@link #pendingInsert}, {@link #pendingOverload}, {@link #pendingConfig}). Everything with a shape of
 * its own lives beside it — {@link BlockTree} (the model), {@link OverlayTreeView} (the rows),
 * {@link OverlayTargetPicker} (where blocks go), {@link OverlayPalette} (what goes there),
 * {@link ArgumentConfigPopover}, {@link OverlayRecorder} and {@link RecordedBatchInserter}. None of them holds
 * a reference back to this class; each takes callbacks.
 *
 * <p>An {@link InsertionCursor} (kept on {@link ProjectState}) marks the <em>focused</em> block; the <b>step</b>
 * buttons move it and the palette inserts a new block just beneath it. The palette is the project's SDK
 * facades as chips, plus an <em>＋ Add block</em> button opening the full categorized statement menu (control
 * flow, variables, print, …).
 *
 * <p><b>Record mode</b> (Linux/X11 only) merges the former standalone macro recorder: pressing Record (or the
 * {@link OverlayHotkey global hotkey}) observes real clicks/keys/waits, and Stop translates them
 * ({@code MacroTranslator}) and inserts the resulting blocks <em>at the cursor</em>, progressively — so
 * recording grows the same tree that hand-authoring does.
 *
 * <p><b>Where the blocks land.</b> The HUD names its target: an activity picker switches the editor to
 * {@code activities/<Name>.java} and parks the cursor inside that activity's {@code run()}. It has to, because
 * the target used to be implicit — whatever file was last rendered, at {@link CursorNavigator#defaultCursor}.
 * In a GAME_BOT project every file that opens by default is generated scaffolding, so every body is read-only,
 * the default cursor finds nothing editable, and {@link #insertBelowCursor} returns without a word: recording
 * appeared to do nothing at all. A project with no activities disables recording and says so, rather than
 * repeating that silence.
 */
public final class ProgramShapeOverlay {

    /** Single live instance — pressing the toolbar button again focuses it instead of opening another. */
    private static ProgramShapeOverlay active;

    private final CodeEditorService context;
    private final ProjectState state;
    private final ProjectSettingsService settings;
    private final ScreenCaptureService capture;
    private final ActivityService activities;
    /** The default capture target: a window, a monitor, or the whole desktop. */
    private final CaptureTarget target;

    private Stage stage;
    /** Title bar, drag handle, and the size-being-authored-against readout. See {@link OverlayHeader}. */
    private OverlayHeader header;
    /** The compact tree — the rows themselves, their look and their controls. See {@link OverlayTreeView}. */
    private final OverlayTreeView tree;
    private CodeBlock root;
    /** {@link #root}'s one-walk structural index, rebuilt lazily by {@link #index()} when the tree changes. */
    private CodeBlock indexedRoot;
    private BlockTree.Index index;
    private java.awt.Rectangle windowBounds;

    /** A specific overload requested from the palette bar, applied once the inserted call is re-parsed. */
    private MethodSignature pendingOverload;

    /** The per-argument config popover: opening it, keeping it live across re-parses, placing it. */
    private final ArgumentConfigPopover config;
    /** Unsubscribes the capture-overlay visibility listener when the overlay closes. */
    private AutoCloseable captureVisibility;
    /** Event-bus subscriptions taken out in {@link #show}, dropped on close so a reopen doesn't stack another set. */
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** While true, a {@code stage.hide()} is a temporary capture-hide, not a real close — skip teardown. */
    private boolean suppressHideTeardown;
    /** Whether {@link #hideForCapture} is the reason the HUD is off screen — the only case it may re-show it. */
    private boolean hiddenForCapture;

    /** When on, inserting a call opens its argument config popover as soon as the re-parsed block is available. */
    private CheckBox autoFillArgs;

    /** Where blocks go: the activity file and the method within it. See {@link OverlayTargetPicker}. */
    private final OverlayTargetPicker picker;
    /** What goes there: the SDK facade chips and the ＋ Add block menu. See {@link OverlayPalette}. */
    private final OverlayPalette palette;

    // ── Record mode ──────────────────────────────────────────────────────────────────────────────────────
    /** Record/Pause/Stop and the session behind them; hands finished batches to {@link #inserter}. */
    private OverlayRecorder recorder;
    /** Inserts a recorded batch one block per re-parse. See {@link RecordedBatchInserter}. */
    private final RecordedBatchInserter inserter =
            new RecordedBatchInserter(this::insertBelowCursor, Platform::runLater);
    /**
     * The HUD's one-line readout: what the last action did, or why it did nothing. Recording overwrites it with
     * a live count while a session runs. Every silent return in this class used to be exactly that — silent —
     * on a surface that has no other channel: there is no console, no status bar and no undo visible from here.
     */
    private Label status;
    /** Set when the overlay should begin recording as soon as it is shown (opened via the Record Macro button). */
    private boolean autoStartRecording;

    /**
     * A just-requested edit whose result must be focused after the next {@link UIBlocksUpdatedEvent}.
     *
     * @param at    where the block landed — a {@link BlockTree.Position} rather than a block reference because
     *              the pre-edit block objects are all replaced on re-parse
     * @param fresh whether the block that lands there is <em>new</em>. Only a new one may have its argument
     *              popover opened for it: a moved block is already configured, and re-opening its editor after
     *              every Alt+↓ would make reordering a call unusable.
     */
    private record PendingFocus(BlockTree.Position at, boolean fresh) {}

    private PendingFocus pendingInsert;

    /**
     * The statements whose branches are folded shut, in coordinates that survive a re-parse. Keyed by
     * {@link BlockTree.Position} for exactly that reason — a {@code Set<StatementBlock>} would be emptied of
     * live members by the first edit, silently re-expanding everything the user had collapsed.
     */
    private final Set<BlockTree.Position> collapsed = new HashSet<>();

    /** Starts/stops recording from inside the game, with the HUD unfocused. See {@link OverlayHotkey}. */
    private OverlayHotkey hotkey;

    /**
     * A block whose config popover should open on the <em>next</em> re-parse, rather than this one. Applying a
     * palette-picked overload ({@link #pendingOverload}) is itself an edit, so the block resolved from
     * {@link #pendingInsert} is replaced again before the user could touch it — which is why "Fill arguments
     * after adding" silently did nothing for every method with more than one overload, i.e. most of the palette.
     */
    private BlockTree.Position pendingConfig;

    /**
     * The HUD's screen bounds, republished from the FX thread whenever the stage moves or resizes. The recorder
     * polls this from its native listener thread to drop clicks on the HUD's own buttons, and JavaFX properties
     * cannot be read from there — so it is a plain snapshot, not a live {@code stage.getX()} read.
     */
    private volatile java.awt.Rectangle hudBounds;

    private ProgramShapeOverlay(CodeEditorService context, ProjectSettingsService settings,
                                ScreenCaptureService capture, ActivityService activities, CaptureTarget target) {
        this.context = context;
        this.state = context.getState();
        this.settings = settings;
        this.capture = capture;
        this.activities = activities;
        this.target = target;
        this.picker = new OverlayTargetPicker(context, settings, activities,
                new OverlayTargetPicker.Callbacks(this::openTargetFile, this::scopeToMethod, this::status));
        this.palette = new OverlayPalette(context, settings,
                new OverlayPalette.Callbacks(this::insertLibraryCall, this::addBelow));
        this.config = new ArgumentConfigPopover(context, this::index, () -> stage);
        // After `config`: the row's ⚙ opens the popover, and a field initializer could not see it yet.
        this.tree = new OverlayTreeView(
                new OverlayTreeView.Callbacks(this::move, this::delete, config::open, this::moveStatement,
                        this::toggleFold),
                () -> { if (stage != null) Platform.runLater(stage::sizeToScene); });
        this.tree.setDiagnostics(context.getDiagnosticsManager());
    }

    /** Sets the HUD's one-line readout. See {@link #status}. */
    private void status(String message) {
        if (status != null) status.setText(message == null ? "" : message);
    }

    /**
     * Opens (or focuses) the overlay editor for the active file. When {@code startRecording} is true, recording
     * begins as soon as the overlay is shown (used by the "Record Macro" toolbar button). Must be called on the
     * FX thread.
     *
     * @param chooseTarget invoked when nothing can be drawn over, with a callback to re-attempt the open; the
     *                     caller shows the Launch Target dialog and runs it once that closes. The retry passes
     *                     {@code null} here, so a user who closes the dialog without choosing gets one
     *                     explanatory warning rather than the same dialog again.
     */
    public static void open(Window owner, CodeEditorService context, ProjectSettingsService settings,
                            ScreenCaptureService capture, ActivityService activities,
                            java.util.function.LongSupplier sessionWindow, boolean startRecording,
                            java.util.function.Consumer<Runnable> chooseTarget) {
        if (active != null && active.stage != null && active.stage.isShowing()) {
            active.stage.toFront();
            if (startRecording && active.recorder != null) active.recorder.start();
            return;
        }
        CaptureTarget target = sessionTarget(sessionWindow);
        if (target == null) {
            try {
                target = settings.defaultTarget();
            } catch (Exception ignored) {
                // no default configured
            }
        }
        if (target == null) {
            // Nothing to draw over: no private session is up and no default capture target is configured. Send
            // the user to the Launch Target dialog and come back when it closes, rather than the dead-end
            // warning this used to be — the button's whole job is "let me author against the running game", and
            // on a fresh app run the session path always misses (the launcher is created lazily elsewhere).
            if (chooseTarget != null) {
                chooseTarget.accept(() -> open(owner, context, settings, capture, activities,
                        sessionWindow, startRecording, null));
                return;
            }
            warn(owner, "Overlay editor needs something to draw over.\n\nPick what the bot launches in "
                    + "\"Launch Target\" (and start it), or set a default window in \"Capture Targets\".");
            return;
        }
        ProgramShapeOverlay overlay = new ProgramShapeOverlay(context, settings, capture, activities, target);
        overlay.autoStartRecording = startRecording;
        active = overlay;
        overlay.start(owner);
    }

    /**
     * The live private session's host window as a capture target, or {@code null} when no session is running (or
     * its window isn't up yet) — in which case the project's configured default target is used as before.
     *
     * <p>A running session <em>outranks</em> the configured default deliberately: while a session is up, that is
     * where the game is, and the configured window target names something on the real desktop that either isn't
     * running or isn't the thing the user is looking at. The target carries the window <em>id</em> because a
     * gamescope host window cannot be named by title — gamescope renames it after whatever app is inside it, and
     * a second window of its own carries the same {@code WM_CLASS}. The title here is a label, not a key.
     */
    private static CaptureTarget sessionTarget(java.util.function.LongSupplier sessionWindow) {
        if (sessionWindow == null) {
            return null;
        }
        long id = sessionWindow.getAsLong();
        return id == 0 ? null : new CaptureTarget.WindowTarget("private session", id);
    }

    /**
     * Off the FX thread: raise the target window and snap it to the reference resolution, then show the overlay
     * positioned near the window's bounds. Mirrors {@code OverlayTemplateCapture.start()} (probe once, seed the
     * reference resolution from the live size the first time), adding the resize so the window is at its
     * canonical size beneath the overlay.
     */
    private void start(Window owner) {
        // A window target is raised + snapped to the reference resolution beneath the HUD; a screen/desktop
        // target is used at its native bounds (no raise/resize).
        if (target instanceof CaptureTarget.WindowTarget wt) {
            Thread t = new Thread(() -> {
                WindowShot shot = capture.captureWindow(wt);   // restores + raises + focuses the window
                if (shot == null) {
                    Platform.runLater(() -> {
                        if (active == this) active = null;
                        warn(owner, "Couldn't find the window \"" + wt.titleSubstring() + "\". Is it open?");
                    });
                    return;
                }
                StudioProjectSettings.Resolution ref = settings.current().referenceResolution();
                if (ref == null) {
                    ref = new StudioProjectSettings.Resolution(shot.bounds().width, shot.bounds().height);
                    settings.update(settings.current().withReferenceResolution(ref));
                }
                // Never resize a private session's host window. gamescope is launched with its output size
                // (-W/-H) and its internal size (-w/-h) both set to the project resolution, which is what makes
                // the captured pixels 1:1 with what the bot sees and its click coordinates need no mapping.
                // Resizing the host window changes one of those two and silently breaks that identity — the
                // overlay would still draw, the clicks would just land somewhere else.
                if (wt.windowId() == null) {
                    capture.resizeTarget(wt, ref.width(), ref.height());
                }
                WindowShot after = capture.captureWindow(wt);
                java.awt.Rectangle bounds = after != null ? after.bounds() : shot.bounds();
                Platform.runLater(() -> show(bounds));
            }, "overlay-editor-open");
            t.setDaemon(true);
            t.start();
        } else {
            capture.captureDefaultTargetAsync(owner, shot -> {
                if (shot == null) {
                    if (active == this) active = null;
                    warn(owner, "Couldn't capture the target. Is the screen available?");
                    return;
                }
                show(shot.bounds());
            });
        }
    }

    private void show(java.awt.Rectangle windowBounds) {
        this.windowBounds = windowBounds;
        recorder = new OverlayRecorder(capture, target, windowBounds, new OverlayRecorder.Callbacks(
                this::status, this::onRecordWindowBounds, this::insertRecordedBatch,
                picker::hasTargets, this::overlayScreenBounds, () -> stage));

        VBox rootPane = buildRoot();
        rootPane.setMinWidth(340);
        rootPane.setPrefWidth(340);
        rootPane.setMaxWidth(340);

        // No fixed height: the window sizes to content (header + controls + tree), so a growing controls
        // panel doesn't squeeze the tree panel out — the tree's own ScrollPane (buildTreePanel) is capped
        // instead, so a long program scrolls internally rather than pushing the window past the screen.
        Scene scene = new Scene(rootPane, Color.TRANSPARENT);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        applyThemeClass(scene.getRoot());

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);              // stays above the target app
        stage.setScene(scene);
        // Deliberately NOT owned by the Studio window, so Studio can be minimized independently (matches the
        // capture/record overlays). Tucked just inside the target window's top-left corner.
        stage.setX(windowBounds.x + 12);
        stage.setY(windowBounds.y + 12);
        restoreSavedState();
        stage.setOnHidden(e -> {
            if (suppressHideTeardown) return;   // a temporary capture-hide, not a real close
            saveState();
            if (hotkey != null) {
                hotkey.close();
                hotkey = null;
            }
            if (captureVisibility != null) {
                try { captureVisibility.close(); } catch (Exception ignored) {}
                captureVisibility = null;
            }
            subscriptions.forEach(EventBus.Subscription::close);
            subscriptions.clear();
            if (recorder != null) recorder.abandon();
            if (active == this) active = null;
        });
        // The recorder polls hudBounds from its native thread, so keep the snapshot current here — on the FX
        // thread — for every way the HUD can move or resize: the header drag, sizeToScene, the Show-lines spinner.
        stage.xProperty().addListener((o, a, b) -> updateHudBounds());
        stage.yProperty().addListener((o, a, b) -> updateHudBounds());
        stage.widthProperty().addListener((o, a, b) -> updateHudBounds());
        stage.heightProperty().addListener((o, a, b) -> updateHudBounds());
        // Keyboard navigation of the compact block tree: → step into, Shift+→ cycle to the next branch of the
        // same block (else / case / otherwise — otherwise reachable only by clicking its row), ← step out,
        // ↑/↓ move, Enter configure, Delete/Backspace remove. Without the last pair a mis-recorded or
        // mis-picked block could only be removed by leaving the overlay for the main block editor.
        scene.setOnKeyPressed(e -> {
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;  // don't steal typing
            switch (e.getCode()) {
                case RIGHT -> move(e.isShiftDown()
                        ? CursorNavigator.stepIntoNext(cursor(), root)
                        : CursorNavigator.stepInto(cursor()));
                case LEFT -> move(CursorNavigator.stepOut(cursor(), root));
                // Alt+↑/↓ reorders the focused block; plain ↑/↓ moves the caret over it. The main editor has
                // no keyboard move at all (it is drag-and-drop only), so this is the only one in the app.
                case UP -> { if (e.isAltDown()) moveFocused(-1); else move(CursorNavigator.stepBack(cursor())); }
                case DOWN -> { if (e.isAltDown()) moveFocused(+1); else move(CursorNavigator.stepOver(cursor())); }
                case ENTER -> {
                    if (focusedStatement() instanceof MethodInvocationBlock mib) config.open(mib);
                }
                case DELETE, BACK_SPACE -> deleteFocused();
                default -> { return; }
            }
            e.consume();
        });

        stage.show();
        updateHudBounds();                             // the listeners above only fire on later changes
        OverlayToolbars.installDrag(header.node(), stage);   // borderless: drag by the header bar
        // Stay above fullscreen games (X11) — but stand down while the argument-config popover is open, or the
        // periodic re-raise puts the HUD back on top of the very window it just opened.
        OverlayToolbars.promoteAboveFullscreen(stage, () -> !config.isOpen());

        // Hide the HUD (and any open config popover) while a capture draw surface is up, so it doesn't sit
        // over the region/point/template selection — restored when the overlay closes.
        captureVisibility = ScreenCaptureService.addCaptureOverlayListener(new ScreenCaptureService.CaptureOverlayListener() {
            @Override public void onShown() { hideForCapture(true); }
            @Override public void onHidden() { hideForCapture(false); }
        });

        // Re-render on every editor update. Both subscriptions are kept and closed in setOnHidden — the overlay
        // is opened and closed repeatedly over one project's lifetime, so without that every reopen left another
        // live handler on the bus re-rendering a dead HUD.
        subscriptions.add(context.getEventBus().subscribe(UIBlocksUpdatedEvent.class, e -> {
            if (stage != null && stage.isShowing()) {
                root = e.rootBlock();
                Platform.runLater(this::onBlocksUpdated);
            }
        }));

        // Keep the activity picker current when an activity is created/renamed/removed elsewhere in the app —
        // it otherwise only ever reflects the list captured at the moment the overlay was opened.
        subscriptions.add(context.getEventBus().subscribe(ActivitiesChangedEvent.class, e -> {
            if (stage != null && stage.isShowing()) {
                Platform.runLater(() -> {
                    picker.refreshActivities();
                    // Record's disabled state was computed once, at build time, so adding the project's first
                    // activity left the button permanently grey behind a tooltip that no longer applied.
                    recorder.refreshAvailability();
                });
            }
        }));

        // Refusals raised inside CodeEditor — chiefly the pinned trailing `return` a block may not be moved
        // past — are published, not thrown, and the HUD has no other window on them: the main editor's status
        // bar is not on screen while the overlay is, so the edit simply appeared not to happen.
        subscriptions.add(context.getEventBus().subscribe(StatusMessageEvent.class, e -> {
            if (stage != null && stage.isShowing()) Platform.runLater(() -> status(e.message()));
        }));

        picker.selectInitialTarget();

        root = context.getRootBlock().orElse(null);
        refreshMethods();
        ensureCursor();
        render();

        hotkey = new OverlayHotkey(() -> recorder.toggle());
        hotkey.start();

        if (autoStartRecording) recorder.start();
    }

    /**
     * Restores the HUD's remembered position and tree height. The position is only honoured when it still
     * lands on an attached screen: a HUD restored onto a monitor that has since been unplugged is a window the
     * user cannot see, cannot move, and — since it is borderless and unowned — has no taskbar entry to find it
     * by. When it is rejected the default placement (inside the target window's top-left corner) stands.
     */
    private void restoreSavedState() {
        StudioProjectSettings.OverlayState saved = settings.current().overlayState();
        if (saved == null) return;
        if (saved.visibleLines() > 0) tree.setVisibleLineCount(saved.visibleLines());
        if (onSomeScreen(saved.x(), saved.y())) {
            stage.setX(saved.x());
            stage.setY(saved.y());
        }
    }

    /** Whether {@code (x, y)} is inside one of the currently attached screens' visual bounds. */
    private static boolean onSomeScreen(double x, double y) {
        return javafx.stage.Screen.getScreens().stream().anyMatch(s -> s.getVisualBounds().contains(x, y));
    }

    /** Records where the HUD ended up and how tall its tree was, for the next open of this project. */
    private void saveState() {
        if (stage == null) return;
        settings.update(settings.current().withOverlayState(new StudioProjectSettings.OverlayState(
                (int) stage.getX(), (int) stage.getY(), tree.visibleLineCount())));
    }

    /** The translucent HUD root: a header bar, the controls panel, and the program-tree panel, with gaps. */
    private VBox buildRoot() {
        VBox rootPane = new VBox(6, buildHeader(), buildControls(), buildTreePanel());
        rootPane.setPadding(new Insets(8));
        rootPane.setStyle("-fx-background-color: transparent;");
        return rootPane;
    }

    private HBox buildHeader() {
        header = new OverlayHeader(() -> { if (stage != null) stage.close(); });
        header.showSize(windowBounds, settings.current().referenceResolution());
        return header.node();
    }

    /** Fresh target-window bounds re-probed by the recorder when a session starts (see {@link OverlayRecorder}). */
    private void onRecordWindowBounds(java.awt.Rectangle bounds) {
        windowBounds = bounds;
        header.showSize(bounds, settings.current().referenceResolution());
    }

    /** Palette (SDK category bar) + step nav + record controls + options, all in one translucent panel. */
    private VBox buildControls() {
        VBox paletteBar = palette.node();

        // Step navigation.
        Button into  = iconButton("⤵", "Step into (→)", () -> move(CursorNavigator.stepInto(cursor())));
        Button branch = iconButton("⇄", "Next branch — else / case / otherwise (Shift+→)",
                () -> move(CursorNavigator.stepIntoNext(cursor(), root)));
        Button out   = iconButton("⤴", "Step out (←)",  () -> move(CursorNavigator.stepOut(cursor(), root)));
        Button up    = iconButton("▲", "Step up (↑)",   () -> move(CursorNavigator.stepBack(cursor())));
        Button down  = iconButton("▼", "Step down (↓)", () -> move(CursorNavigator.stepOver(cursor())));
        Button refresh = iconButton("⟳", "Refresh", this::render);
        HBox stepRow = new HBox(6, label("Step:"), up, down, into, branch, out, refresh);
        stepRow.setAlignment(Pos.CENTER_LEFT);

        HBox recordRow = recorder.node();

        status = new Label("");
        status.setStyle(LABEL);
        status.setWrapText(true);

        autoFillArgs = new CheckBox("Fill arguments after adding");
        autoFillArgs.setSelected(true);
        autoFillArgs.setStyle(LABEL);
        autoFillArgs.setTooltip(new Tooltip(
                "When on, adding an action immediately opens its argument editor (draw rect / pick template)"));

        VBox controls = new VBox(6, picker.activityRow(), picker.methodRow(), paletteBar, stepRow, recordRow,
                autoFillArgs, status);
        controls.setPadding(new Insets(8));
        controls.setStyle(PANEL);
        return controls;
    }

    // ── where blocks go ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the file the activity picker resolved and re-homes onto its default method. {@code switchToFile} is
     * synchronous: it re-parses, republishes the tree and returns, so the tree read back here is the new one.
     */
    private void openTargetFile(java.nio.file.Path file) {
        context.switchToFile(file);
        root = context.getRootBlock().orElse(null);
        refreshMethods();
        render();
    }

    /** Scopes the tree to a method the user picked, parking the caret inside it. */
    private void scopeToMethod(String label) {
        InsertionCursor c = index().methodCursor(label);
        if (c != null) state.setInsertionCursor(c);
        render();
    }

    /**
     * Repopulates the method picker from the current file, re-homing the caret when that changed which method
     * is scoped — the picker owns the selection, but only the tree index can say where inside it to park.
     */
    private void refreshMethods() {
        if (picker.refreshMethods(root == null ? List.of() : index().methodLabels())) {
            InsertionCursor c = index().methodCursor(picker.selectedMethod());
            if (c != null) state.setInsertionCursor(c);
        }
    }

    /**
     * Inserts a fresh SDK call for {@code facade.method} below the cursor. When {@code overload} is given it is
     * applied once the re-parsed block is available (via {@link #pendingOverload}); otherwise the creation path
     * defaults to the fewest-argument overload (or the project favourite).
     */
    private void insertLibraryCall(String facade, String method, MethodSignature overload) {
        pendingOverload = overload;
        BlockType.LibraryCall block = new BlockType.LibraryCall(
                "OVL_" + facade + "_" + method, method, com.botmaker.studio.palette.BlockCategory.INPUT,
                facade, method, List.of());
        insertBelowCursor(block);
    }

    private VBox buildTreePanel() {
        return tree.node();
    }

    /** Republishes {@link #hudBounds} from the FX thread; see that field for why it is a snapshot. */
    private void updateHudBounds() {
        hudBounds = (stage == null) ? null
                : new java.awt.Rectangle((int) stage.getX(), (int) stage.getY(),
                        (int) stage.getWidth(), (int) stage.getHeight());
    }

    /** The HUD's last known screen bounds. Called from the recorder's native thread — no FX property reads. */
    private java.awt.Rectangle overlayScreenBounds() {
        return hudBounds;
    }

    // ── insertion ────────────────────────────────────────────────────────────────────────────────────────

    /** Inserts {@code type} in the slot just below the cursor, arming the post-reparse cursor/config handoff. */
    private void insertBelowCursor(BlockType type) {
        InsertionCursor c = cursor();
        if (type == null) return;
        if (c == null) {
            status("Nowhere to insert — click a row to place the caret first.");
            return;
        }
        // The caret should never be parked in scaffolding (CursorNavigator skips read-only bodies), but the
        // overlay reaches CodeEditor without going through a block, so don't rely on that alone.
        if (c.body().isReadOnly()) {
            status("Can't insert here — this is generated code. Pick an activity method.");
            return;
        }
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        pendingInsert = new PendingFocus(new BlockTree.Position(index().ordinalOf(c.body()), insertIndex), true);
        context.getCodeEditor().addStatement(c.body(), type, insertIndex);
    }

    /** Removes the block the cursor sits on (Delete/Backspace), leaving the caret on the slot above it. */
    private void deleteFocused() {
        InsertionCursor c = cursor();
        StatementBlock stmt = focusedStatement();
        if (c == null || stmt == null) {
            status("Nothing to delete — the caret is on an empty slot.");
            return;
        }
        delete(stmt, c.body(), c.index());
    }

    /**
     * Removes {@code stmt} through the same {@code CodeEditor.deleteStatement} the main editor's per-block ✕
     * uses, so its read-only / pinned-return guards ({@code canDelete}) apply here too rather than being
     * re-implemented. The caret is re-homed onto the slot <em>above</em> first: the re-parse replaces every
     * block object, and a caret left on the index the deleted block occupied would silently point at whatever
     * slid up into it.
     *
     * <p>The target is resolved with {@link CodeBlock#enclosingStatement()}. Testing the block's <em>own</em>
     * node for {@code instanceof Statement} — what this did — is false for every method-call row, because
     * {@code MethodInvocationBlock} holds the {@code MethodInvocation} rather than its {@code ExpressionStatement}:
     * both ✕ and Delete returned here without a word on exactly the rows the overlay is used to build.
     */
    private void delete(StatementBlock stmt, BodyBlock body, int index) {
        org.eclipse.jdt.core.dom.Statement s = stmt.enclosingStatement();
        if (stmt.isReadOnly()) {
            status("Can't delete generated code — it's maintained by Studio.");
            return;
        }
        if (s == null) {
            status("Can't delete this row on its own.");
            return;
        }
        state.setInsertionCursor(new InsertionCursor(body, Math.max(-1, index - 1)));
        context.getCodeEditor().deleteStatement(s);
    }

    /** Alt+↑/↓: reorders the block the caret sits on. */
    private void moveFocused(int delta) {
        InsertionCursor c = cursor();
        StatementBlock stmt = focusedStatement();
        if (c == null || stmt == null) {
            status("Nothing to move — the caret is on an empty slot.");
            return;
        }
        moveStatement(stmt, c.body(), c.index(), delta);
    }

    /**
     * Reorders {@code stmt} within its own body by one slot ({@code delta} −1 up, +1 down), through the same
     * {@code CodeEditor.moveStatement} the main editor's drag-and-drop uses — so its read-only and
     * pinned-return guards apply here rather than being re-implemented against a second set of rules.
     *
     * <p>The insert index is the drop index a drag would produce, not the destination slot: moving <em>down</em>
     * means "insert before the element after the one I am swapping with", i.e. one further along, because the
     * removal of the original is still pending in the same rewrite. The caret is re-homed onto the block's new
     * slot through the ordinary {@link #pendingInsert} handoff, marked not-fresh so no argument popover opens.
     */
    private void moveStatement(StatementBlock stmt, BodyBlock body, int index, int delta) {
        if (stmt == null || body == null) return;
        int destination = index + delta;
        if (destination < 0 || destination >= body.getStatements().size()) {
            status(delta < 0 ? "Already the first block here." : "Already the last block here.");
            return;
        }
        if (stmt.isReadOnly() || body.isReadOnly()) {
            status("Can't move generated code — it's maintained by Studio.");
            return;
        }
        pendingInsert = new PendingFocus(new BlockTree.Position(index().ordinalOf(body), destination), false);
        context.getCodeEditor().moveStatement(stmt, body, body, delta < 0 ? destination : destination + 1);
    }

    /** ▸/▾: hides or re-shows a control-flow block's branches. See {@link #collapsed}. */
    private void toggleFold(StatementBlock stmt) {
        BlockTree.Position at = index().locate(stmt);
        if (at == null) return;
        if (!collapsed.remove(at)) collapsed.add(at);
        render();
    }

    /** Whether {@code stmt}'s branches are folded shut right now — the predicate {@link OverlayTreeView} renders with. */
    private boolean isCollapsed(StatementBlock stmt) {
        BlockTree.Position at = index().locate(stmt);
        return at != null && collapsed.contains(at);
    }

    private void addBelow(javafx.scene.Node anchor) {
        InsertionCursor c = cursor();
        if (c == null) return;
        var menu = StatementMenu.create(
                context.getProjectAnalyzer(), c.body().getAstNode(), this::insertBelowCursor);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    /** Queues a recorded block sequence for one-per-re-parse insertion at the cursor, without arg popovers. */
    private void insertRecordedBatch(List<BlockType> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            status("Nothing to insert — no recognizable actions were recorded.");
            return;
        }
        // Before the enqueue: it inserts the batch's first block straight away, and that insert may have a
        // more specific thing to say (a read-only body, no caret) which this line must not overwrite.
        status("Recorded — inserting " + blocks.size() + (blocks.size() == 1 ? " block" : " blocks") + "…");
        inserter.enqueue(blocks);
    }

    // ── cursor ───────────────────────────────────────────────────────────────────────────────────────────

    private InsertionCursor cursor() {
        return state.getInsertionCursor().orElse(null);
    }

    /** The statement the cursor currently sits on, or {@code null} if the cursor is at an empty/end slot. */
    private StatementBlock focusedStatement() {
        InsertionCursor c = cursor();
        if (c == null) return null;
        List<StatementBlock> statements = c.body().getStatements();
        return (c.index() >= 0 && c.index() < statements.size()) ? statements.get(c.index()) : null;
    }

    /** Seeds the cursor from the tree if none is set (or the current one dangles after a re-parse). */
    private void ensureCursor() {
        InsertionCursor c = cursor();
        BodyBlock scope = index().methodBody(picker.selectedMethod());
        boolean live = c != null && index().contains(c.body());
        // Staying inside the scoped method matters as much as being live. Reseeding with defaultCursor could
        // land the caret in a *different* method than the tree is scoped to: the render then showed no focus
        // anywhere — the caret was real, just not on screen — and every insert landed in the wrong method.
        if (live && (scope == null || BlockTree.containsDescendant(scope, c.body()))) return;
        state.setInsertionCursor(scope != null
                ? index().methodCursor(picker.selectedMethod())
                : CursorNavigator.defaultCursor(root));
    }

    private void move(InsertionCursor next) {
        if (next != null) state.setInsertionCursor(next);
        render();
    }

    /**
     * Hides / restores the HUD (and any open config popover) around a capture draw surface. The HUD stage is
     * {@code hide()}d (guarded so its close handler doesn't tear the overlay down); the config popover — which
     * owns the modal capture overlay — is only dimmed to {@code opacity 0} so its modal child stays alive.
     */
    private void hideForCapture(boolean hide) {
        config.dim(hide);
        if (hide) {
            if (stage == null || !stage.isShowing()) return;   // nothing of ours on screen to take down
            hiddenForCapture = true;
            suppressHideTeardown = true;
            stage.hide();
        } else {
            // Only re-show a HUD this method is the reason for hiding. It used to re-show unconditionally,
            // which resurrected a HUD the user had closed — any capture surface opened from elsewhere in
            // Studio (the capture tool's own overlays fire this listener too) brought the dead HUD back, with
            // its event subscriptions still torn down, so it rendered nothing and never updated again.
            if (!hiddenForCapture) return;
            hiddenForCapture = false;
            suppressHideTeardown = false;
            stage.show();
            stage.toFront();
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────────────

    /** Handles a republished block tree: re-render, resolve any pending insertion, continue a recorded batch. */
    private void onBlocksUpdated() {
        refreshMethods();   // pick up a method added/removed by hand; keeps a still-valid selection as-is
        render();
        // Before the pending handling below, which may open a popover of its own over the top of this one.
        config.refresh();
        if (pendingInsert != null) {
            BlockTree.Position p = pendingInsert.at();
            boolean fresh = pendingInsert.fresh();
            pendingInsert = null;
            MethodSignature ov = fresh ? pendingOverload : null;   // consume the palette-requested overload
            pendingOverload = null;
            BodyBlock body = index().bodyAt(p.bodyOrdinal());
            if (body != null) {
                List<StatementBlock> statements = body.getStatements();
                if (p.index() >= 0 && p.index() < statements.size()) {
                    // Re-home the cursor onto the freshly inserted block so subsequent adds continue below it.
                    state.setInsertionCursor(new InsertionCursor(body, p.index()));
                    render();
                    StatementBlock inserted = statements.get(p.index());
                    if (!inserter.isDraining()) {
                        status((fresh ? "Inserted " : "Moved ") + OverlayTreeView.compactLabel(inserted));
                    }
                    if (ov != null && inserted instanceof MethodInvocationBlock mib) {
                        // A specific overload was picked in the palette bar — apply it to the fresh call. That
                        // is another edit, so the block we'd open the popover on is about to be replaced;
                        // defer to the next pulse instead of opening a popover onto a dead block.
                        mib.switchToOverload(context, ov);
                        if (autoFillEnabled()) pendingConfig = p;
                    } else if (fresh && autoFillEnabled() && inserted instanceof MethodInvocationBlock mib) {
                        config.open(mib);
                    }
                }
            }
        } else if (pendingConfig != null) {
            BlockTree.Position p = pendingConfig;
            pendingConfig = null;
            if (index().statementAt(p) instanceof MethodInvocationBlock mib) config.open(mib);
        }
        // Continue draining a recorded batch after the cursor has re-homed onto the last insert.
        inserter.tick();
    }

    private void render() {
        ensureCursor();
        if (root == null) {
            tree.showMessage("No open file.");
            return;
        }
        // Scoped to the selected method (buildMethodRow) when there is one, so unrelated methods' statements
        // don't appear mixed into one flat list. Falls back to every top-level body when none is selected
        // (e.g. a file with no methods) — the old "Program is empty." path stays reachable either way.
        BodyBlock scoped = index().methodBody(picker.selectedMethod());
        if (scoped != null) {
            tree.render(List.of(scoped), cursor(), this::isCollapsed);
            return;
        }
        List<BodyBlock> tops = index().topLevelBodies();
        if (tops.isEmpty()) tree.showMessage("Program is empty.");
        else tree.render(tops, cursor(), this::isCollapsed);
    }

    /** {@link #root}'s structural index, rebuilt once per published tree rather than per lookup. */
    private BlockTree.Index index() {
        if (index == null || indexedRoot != root) {
            indexedRoot = root;
            index = BlockTree.index(root);
        }
        return index;
    }

    /** Whether a freshly inserted call should have its argument editor opened for it. */
    private boolean autoFillEnabled() {
        return !inserter.suppressesAutoFill() && autoFillArgs != null && autoFillArgs.isSelected();
    }
}
