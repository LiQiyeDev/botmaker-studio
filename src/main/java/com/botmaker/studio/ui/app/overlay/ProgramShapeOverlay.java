package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.services.record.MacroTranslator;
import com.botmaker.studio.services.record.RecordingSession;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.events.CoreApplicationEvents.ActivitiesChangedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.UIBlocksUpdatedEvent;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.ui.render.menu.StatementMenu;
import com.botmaker.studio.util.MethodSignature;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.LABEL;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.PANEL;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.applyThemeClass;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.dimLabel;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.iconButton;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.info;
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
 * <p>An {@link InsertionCursor} (kept on {@link ProjectState}) marks the <em>focused</em> block; the toolbar's
 * <b>step</b> buttons move it and the palette inserts a new block just beneath it. The palette has two modes:
 * <b>Basic</b> exposes only the core bot actions ({@link BlockCatalog#botActions()}); <b>Advanced</b> adds an
 * <em>Add block</em> button opening the full categorized statement menu (control flow, variables, print, …).
 *
 * <p><b>Record mode</b> (Linux/X11 only) merges the former standalone macro recorder: pressing Record observes
 * real clicks/keys/waits via a {@link RecordingSession}, and Stop translates them ({@link MacroTranslator}) and
 * inserts the resulting blocks <em>at the cursor</em>, progressively — so recording grows the same tree that
 * hand-authoring does.
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
    private HBox header;
    /** The compact tree — the rows themselves, their look and their controls. See {@link OverlayTreeView}. */
    private final OverlayTreeView tree = new OverlayTreeView(
            new OverlayTreeView.Callbacks(this::move, this::delete, this::openConfig),
            () -> { if (stage != null) Platform.runLater(stage::sizeToScene); });
    private CodeBlock root;
    /** {@link #root}'s one-walk structural index, rebuilt lazily by {@link #index()} when the tree changes. */
    private CodeBlock indexedRoot;
    private BlockTree.Index index;
    private java.awt.Rectangle windowBounds;

    /** A specific overload requested from the palette bar, applied once the inserted call is re-parsed. */
    private MethodSignature pendingOverload;

    /** The open per-argument config popover (if any), tracked so it can be hidden while a capture overlay is up. */
    private Stage configDlg;
    /**
     * Where the open popover's call sits, and the pane holding its rows — the two halves of keeping it live
     * across a re-parse. Every picker writes through {@code CodeEditor}, which republishes the whole tree, so
     * the {@code MethodInvocationBlock} and every argument node the popover was built from are dead the moment
     * the first argument is set. The popover used to keep them anyway: it showed stale values and dropped every
     * edit after the first. {@link #refreshConfig} re-resolves the call from these coordinates and rebuilds the
     * rows in place, so the window itself (and its position) survives.
     */
    private BlockTree.Position configTarget;
    private ScrollPane configScroll;
    /** Unsubscribes the capture-overlay visibility listener when the overlay closes. */
    private AutoCloseable captureVisibility;
    /** Event-bus subscriptions taken out in {@link #show}, dropped on close so a reopen doesn't stack another set. */
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    /** While true, a {@code stage.hide()} is a temporary capture-hide, not a real close — skip teardown. */
    private boolean suppressHideTeardown;

    /** When on, inserting a call opens its argument config popover as soon as the re-parsed block is available. */
    private CheckBox autoFillArgs;

    /** The activity being authored into; picking one switches the editor to its file and re-homes the cursor. */
    private ComboBox<String> activityBox;

    /** The method the tree is scoped to (its statements are the only ones rendered); picking one re-homes the cursor. */
    private ComboBox<String> methodBox;
    /** Name of the method currently rendered/edited, or {@code null} to fall back to every top-level body. */
    private String selectedMethod;

    // ── Record mode ──────────────────────────────────────────────────────────────────────────────────────
    private RecordingSession session;
    private Button recordBtn;
    private Button stopBtn;
    private Label recStatus;
    /** Set when the overlay should begin recording as soon as it is shown (opened via the Record Macro button). */
    private boolean autoStartRecording;
    /** Blocks from a finished recording, drained one-per-pulse; {@link #draining} guards the continuation. */
    private final Deque<BlockType> recordQueue = new ArrayDeque<>();
    private boolean draining;
    /** While true, inserts skip the auto-fill config popover (recorded calls already carry concrete args). */
    private boolean suppressAutoFill;

    /**
     * A just-requested insertion, resolved after the next {@link UIBlocksUpdatedEvent}. It is a
     * {@link BlockTree.Position} rather than a block reference because the pre-insert block objects are all
     * replaced on re-parse; the position is what lets the cursor be re-homed onto the inserted block and its
     * config popover opened.
     */
    private BlockTree.Position pendingInsert;

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

    /** Set while a coalesced record-status refresh is already queued, so one FX runnable serves a burst of input. */
    private final java.util.concurrent.atomic.AtomicBoolean recStatusQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    private ProgramShapeOverlay(CodeEditorService context, ProjectSettingsService settings,
                                ScreenCaptureService capture, ActivityService activities, CaptureTarget target) {
        this.context = context;
        this.state = context.getState();
        this.settings = settings;
        this.capture = capture;
        this.activities = activities;
        this.target = target;
        this.tree.setDiagnostics(context.getDiagnosticsManager());
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
            if (startRecording && active.session != null && !active.session.isRecording()) active.startRecording();
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
        session = new RecordingSession(this::overlayScreenBounds, count -> requestRecStatusRefresh());

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
        stage.setOnHidden(e -> {
            if (suppressHideTeardown) return;   // a temporary capture-hide, not a real close
            if (captureVisibility != null) {
                try { captureVisibility.close(); } catch (Exception ignored) {}
                captureVisibility = null;
            }
            subscriptions.forEach(EventBus.Subscription::close);
            subscriptions.clear();
            if (session != null && session.isRecording()) session.stop();
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
                case UP -> move(CursorNavigator.stepBack(cursor()));
                case DOWN -> move(CursorNavigator.stepOver(cursor()));
                case ENTER -> {
                    if (focusedStatement() instanceof MethodInvocationBlock mib) openConfig(mib);
                }
                case DELETE, BACK_SPACE -> deleteFocused();
                default -> { return; }
            }
            e.consume();
        });

        stage.show();
        updateHudBounds();                             // the listeners above only fire on later changes
        OverlayToolbars.installDrag(header, stage);   // borderless: drag by the header bar
        // Stay above fullscreen games (X11) — but stand down while the argument-config popover is open, or the
        // periodic re-raise puts the HUD back on top of the very window it just opened.
        OverlayToolbars.promoteAboveFullscreen(stage, () -> configDlg == null);

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
                Platform.runLater(this::refreshActivityBox);
            }
        }));

        // The handler is installed *after* the initial value, so the one explicit call below is the only one —
        // ComboBox.setValue's action-firing behaviour is not something to have two code paths depend on.
        String initial = preferredTarget();
        if (initial != null) {
            activityBox.setValue(initial);
            selectActivity(initial);
        }
        activityBox.setOnAction(e -> selectActivity(activityBox.getValue()));

        root = context.getRootBlock().orElse(null);
        refreshMethodBox();
        ensureCursor();
        render();

        if (autoStartRecording && RecordingSession.isSupported()) startRecording();
    }

    /** The translucent HUD root: a header bar, the controls panel, and the program-tree panel, with gaps. */
    private VBox buildRoot() {
        VBox rootPane = new VBox(6, buildHeader(), buildControls(), buildTreePanel());
        rootPane.setPadding(new Insets(8));
        rootPane.setStyle("-fx-background-color: transparent;");
        return rootPane;
    }

    private HBox buildHeader() {
        Label title = new Label("Overlay Editor");
        title.setStyle("-fx-text-fill: #c9d4e6; -fx-font-weight: bold;");
        // Current window/screen resolution so the author knows the size they're building against.
        Label res = new Label(com.botmaker.studio.ui.app.ResolutionChoices.readout(windowBounds));
        res.setStyle("-fx-text-fill: #8fa3bf; -fx-font-size: 11px;");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close overlay"));
        close.setOnAction(e -> { if (stage != null) stage.close(); });
        header = new HBox(8, title, res, spring, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 8, 6, 10));
        header.setStyle(PANEL);
        return header;
    }

    /** Palette (SDK category bar) + step nav + record controls + options, all in one translucent panel. */
    private VBox buildControls() {
        VBox paletteBar = buildPaletteBar();

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

        // Record controls (merged macro recorder).
        recordBtn = new Button("● Record");
        recordBtn.setOnAction(e -> toggleRecordPrimary());
        stopBtn = new Button("■ Stop");
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> stopRecordingAndInsert());
        recStatus = new Label("");
        recStatus.setStyle(LABEL);
        if (!RecordingSession.isSupported()) {
            recordBtn.setDisable(true);
            recordBtn.setTooltip(new Tooltip("Recording is available on Linux (X11) only"));
        } else if (!(target instanceof CaptureTarget.WindowTarget)) {
            // Recording translates clicks to window-relative coordinates, so it needs a window target.
            recordBtn.setDisable(true);
            recordBtn.setTooltip(new Tooltip("Recording targets a window — set a window as the default capture target"));
        } else if (activityNames().isEmpty()) {
            // Say so up front. There is nowhere editable to put the blocks, and the failure downstream is silent.
            recordBtn.setDisable(true);
            recordBtn.setTooltip(new Tooltip(
                    "Nowhere to record into — add an activity in Project ▸ Activity Flow first"));
        } else {
            recordBtn.setTooltip(new Tooltip("Record real clicks/keys and insert them at the cursor"));
        }
        HBox recordRow = new HBox(6, recordBtn, stopBtn, recStatus);
        recordRow.setAlignment(Pos.CENTER_LEFT);

        autoFillArgs = new CheckBox("Fill arguments after adding");
        autoFillArgs.setSelected(true);
        autoFillArgs.setStyle(LABEL);
        autoFillArgs.setTooltip(new Tooltip(
                "When on, adding an action immediately opens its argument editor (draw rect / pick template)"));

        VBox controls = new VBox(6, buildActivityRow(), buildMethodRow(), paletteBar, stepRow, recordRow, autoFillArgs);
        controls.setPadding(new Insets(8));
        controls.setStyle(PANEL);
        return controls;
    }

    // ── target activity ─────────────────────────────────────────────────────────────────────────────────

    /** The picker naming the activity every insert goes into, plus a nudge when the project has none. */
    private HBox buildActivityRow() {
        List<String> items = targetNames();
        activityBox = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(items));
        activityBox.setTooltip(new Tooltip("The activity that new and recorded blocks are inserted into"));
        activityBox.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                boolean header = SCAFFOLD_HEADER.equals(item);
                setDisable(header);   // a caption, not a choice
                setStyle(header ? "-fx-font-style: italic; -fx-opacity: 0.7;" : "");
            }
        });
        HBox row = new HBox(6, label("Activity:"), activityBox);
        activityBox.setDisable(items.isEmpty());
        if (activityNames().isEmpty()) {
            activityBox.setPromptText("none yet");
            row.getChildren().add(dimLabel("add one in Project ▸ Activity Flow"));
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** The non-archived activities, in flow order — the ones that have a stub file and actually run. */
    private List<String> activityNames() {
        return activities.current().liveActivities().stream().map(ActivityDefinition::name).toList();
    }

    /** Caption separating the flow's activities from the scaffold hooks; disabled in the list, never selectable. */
    private static final String SCAFFOLD_HEADER = "— scaffolds —";

    /**
     * Everything the overlay can author into: the activities, then the supervised scaffold hooks
     * ({@code GoHome}, {@code Popups}) that exist on disk. The hooks are as much a place for blocks as any
     * activity — their {@code run()} body is the user's by {@link com.botmaker.studio.project.MethodLock}'s
     * design, and {@code Popups} in particular is where the popup-dismissal steps belong — but they have no
     * {@link ActivityDefinition}, so a list built from the flow alone could never reach them.
     */
    private List<String> targetNames() {
        List<String> out = new ArrayList<>(activityNames());
        List<String> hooks = hookNames();
        if (!hooks.isEmpty()) {
            out.add(SCAFFOLD_HEADER);
            out.addAll(hooks);
        }
        return out;
    }

    /** The scaffold hooks present in this project, by class name. Empty for a template that has none. */
    private List<String> hookNames() {
        java.nio.file.Path dir = context.getConfig().mainSourceFile().getParent();
        if (dir == null) return List.of();
        return com.botmaker.studio.project.MethodLock.superviseHookFiles().stream()
                .sorted()
                .filter(f -> java.nio.file.Files.isRegularFile(dir.resolve(f)))
                .map(f -> f.substring(0, f.length() - ".java".length()))
                .toList();
    }

    /**
     * Refreshes {@link #activityBox}'s items after an {@link ActivitiesChangedEvent} (an activity was
     * created/renamed/removed elsewhere). Leaves an already-valid selection alone — creating an unrelated
     * activity shouldn't yank the user off what they're editing — but picks a default when the box was
     * previously empty or its selection no longer exists.
     */
    private void refreshActivityBox() {
        if (activityBox == null) return;
        List<String> items = targetNames();
        activityBox.getItems().setAll(items);
        activityBox.setDisable(items.isEmpty());
        if (items.isEmpty()) {
            activityBox.setPromptText("none yet");
            return;
        }
        // A scaffold hook is a valid selection, so the still-valid test is against the whole item list —
        // otherwise creating an unrelated activity would yank a user editing Popups back into the flow.
        String current = activityBox.getValue();
        if (current == null || !items.contains(current)) {
            String next = preferredTarget();
            activityBox.setValue(next);
            selectActivity(next);
        }
    }

    /**
     * Which target to open on: the one last authored into (activity <em>or</em> scaffold hook — the last-used
     * one is remembered per project so reopening the overlay resumes where the last session stopped), else the
     * flow's start node, else the first activity, else a hook for a project that has no activities yet.
     */
    private String preferredTarget() {
        String last = settings.current().lastRecordedActivity();
        if (last != null && !SCAFFOLD_HEADER.equals(last) && targetNames().contains(last)) return last;
        List<String> names = activityNames();
        if (names.isEmpty()) {
            List<String> hooks = hookNames();
            return hooks.isEmpty() ? null : hooks.get(0);
        }
        String start = activities.current().flow().resolvedStart(names);
        return names.contains(start) ? start : names.get(0);
    }

    /**
     * Switches the editor to the picked target's file, parks the cursor in its {@code run()}, remembers it.
     * The file is {@code activities/<name>.java} for an activity and {@code <name>.java} beside the main source
     * for a scaffold hook — {@link #targetNames} offers both, so this resolves both.
     */
    private void selectActivity(String name) {
        if (name == null || SCAFFOLD_HEADER.equals(name)) return;
        java.nio.file.Path file = context.getConfig().activitiesPackageDir().resolve(name + ".java");
        if (!java.nio.file.Files.isRegularFile(file)) {
            java.nio.file.Path pkg = context.getConfig().mainSourceFile().getParent();
            if (pkg != null) file = pkg.resolve(name + ".java");
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            warn(stage, "Couldn't open " + name + ".java.\n\nUse File ▸ Recover Project Files to restore it.");
            return;
        }
        context.switchToFile(file);   // synchronous: re-parses, republishes the tree, and returns
        root = context.getRootBlock().orElse(null);
        selectedMethod = null;        // a different file — the previous selection doesn't apply here
        refreshMethodBox();
        render();
        settings.update(settings.current().withLastRecordedActivity(name));
    }

    // ── target method ───────────────────────────────────────────────────────────────────────────────────

    /** The picker naming the method whose statements are the only ones rendered/edited. */
    private HBox buildMethodRow() {
        methodBox = new ComboBox<>();
        methodBox.setTooltip(new Tooltip("Show only this method's blocks — new/recorded blocks land here too"));
        methodBox.setOnAction(e -> selectMethod(methodBox.getValue()));
        HBox row = new HBox(6, label("Method:"), methodBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Every method declared in the current file, in tree (DFS) order. */
    private List<String> methodNames() {
        return root == null ? List.of() : index().methodNames();
    }

    /** Switches which method's blocks are rendered/edited, re-homing the cursor into it. */
    private void selectMethod(String name) {
        selectedMethod = name;
        InsertionCursor c = index().methodCursor(name);
        if (c != null) state.setInsertionCursor(c);
        render();
    }

    /**
     * Repopulates {@link #methodBox}'s items from the current file. Leaves an already-valid selection alone
     * (so an edit elsewhere in the file doesn't yank the view away from what the user is looking at); picks
     * {@code run} (or the first method) when the selection is unset or no longer exists.
     */
    private void refreshMethodBox() {
        List<String> names = methodNames();
        if (methodBox != null) methodBox.getItems().setAll(names);
        if (selectedMethod == null || !names.contains(selectedMethod)) {
            selectedMethod = names.contains("run") ? "run" : (names.isEmpty() ? null : names.get(0));
            InsertionCursor c = index().methodCursor(selectedMethod);
            if (c != null) state.setInsertionCursor(c);
        }
        if (methodBox != null) methodBox.setValue(selectedMethod);
    }

    /**
     * The palette bar: one hover-expanding chip per SDK facade category laid out in a line — hovering a chip
     * lists its methods, and a method with several overloads fans out into its overloads (favourite methods
     * first). Picking a method inserts its call below the cursor, defaulting to the fewest-argument overload
     * (or the exact overload picked). A trailing "＋ Add block" opens the full categorized statement menu for
     * everything else (control flow, variables, print, …).
     */
    private VBox buildPaletteBar() {
        FlowPane chips = new FlowPane(6, 6);
        for (String facade : com.botmaker.studio.palette.SdkApi.MENU_FACADE_CLASSES) {
            chips.getChildren().add(facadeMenuButton(facade));
        }
        Button addBlock = new Button("＋ Add block");
        addBlock.setTooltip(new Tooltip("Insert any block (control flow, variables, print, …) below the cursor"));
        addBlock.setOnAction(e -> addBelow(addBlock));
        chips.getChildren().add(addBlock);

        return new VBox(4, label("Blocks:"), chips);
    }

    /** A category chip for one SDK facade; on show it lists its methods → overloads (favourites first). */
    private javafx.scene.control.MenuButton facadeMenuButton(String facade) {
        javafx.scene.control.MenuButton mb = new javafx.scene.control.MenuButton(facade);
        mb.setOnShowing(e -> {
            mb.getItems().clear();
            java.util.Map<String, List<MethodSignature>> byName = context.getProjectAnalyzer().getMethods(facade, true).stream()
                    .collect(java.util.stream.Collectors.groupingBy(MethodSignature::name,
                            java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
            if (byName.isEmpty()) {
                javafx.scene.control.MenuItem none = new javafx.scene.control.MenuItem("(SDK not indexed yet)");
                none.setDisable(true);
                mb.getItems().add(none);
                return;
            }
            // Favourite methods for this class first (Project Settings), then the rest alphabetically.
            List<String> favs = settings.current().favoriteMethodsFor(facade);
            List<String> ordered = new java.util.ArrayList<>();
            for (String f : favs) if (byName.containsKey(f) && !ordered.contains(f)) ordered.add(f);
            byName.keySet().stream().filter(n -> !ordered.contains(n)).sorted().forEach(ordered::add);

            for (String mName : ordered) {
                List<MethodSignature> sigs = byName.get(mName);
                if (sigs.size() == 1) {
                    javafx.scene.control.MenuItem it = new javafx.scene.control.MenuItem(mName);
                    it.setOnAction(a -> insertLibraryCall(facade, mName, null));
                    mb.getItems().add(it);
                } else {
                    javafx.scene.control.Menu sub = new javafx.scene.control.Menu(mName);
                    for (MethodSignature sig : sigs) {
                        javafx.scene.control.MenuItem si = new javafx.scene.control.MenuItem(sig.toString());
                        si.setOnAction(a -> insertLibraryCall(facade, mName, sig));
                        sub.getItems().add(si);
                    }
                    mb.getItems().add(sub);
                }
            }
        });
        return mb;
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

    // ── Record mode ─────────────────────────────────────────────────────────────────────────────────────

    private void toggleRecordPrimary() {
        if (session == null) return;
        if (!session.isRecording()) {
            startRecording();
        } else {
            session.setPaused(!session.isPaused());
            recordBtn.setText(session.isPaused() ? "▶ Resume" : "⏸ Pause");
            updateRecStatus();
        }
    }

    private void startRecording() {
        if (session == null || session.isRecording() || !RecordingSession.isSupported()) return;
        if (target instanceof CaptureTarget.WindowTarget wt) {
            capture.raiseWindow(wt);   // interact with the target window, not whatever was focused
        }
        try {
            session.start();
        } catch (Exception ex) {
            warn(stage, "Couldn't start input recording: " + ex.getMessage());
            return;
        }
        recordBtn.setText("⏸ Pause");
        stopBtn.setDisable(false);
        updateRecStatus();
    }

    /** Stops recording, translates the buffered input, and inserts the blocks at the cursor progressively. */
    private void stopRecordingAndInsert() {
        if (session == null || !session.isRecording()) return;
        List<InputEvent> events = session.stop();
        recordBtn.setText("● Record");
        stopBtn.setDisable(true);
        updateRecStatus();

        String title = (target instanceof CaptureTarget.WindowTarget wt) ? wt.titleSubstring() : null;
        MacroTranslator.WindowRef ref = new MacroTranslator.WindowRef(
                title, windowBounds.x, windowBounds.y, windowBounds.width, windowBounds.height);
        insertRecordedBatch(MacroTranslator.translate(events, ref));
    }

    private void updateRecStatus() {
        if (recStatus == null) return;
        recStatus.setText(session == null || !session.isRecording()
                ? ""
                : (session.isPaused() ? "Paused" : "Recording") + " — " + session.actionCount() + " actions");
    }

    /**
     * Queues one status refresh per FX pulse. The recorder reports every press from its native thread, and a
     * {@code Platform.runLater} apiece floods the FX queue during a fast burst — starving the same queue the
     * insert/re-parse handoff runs on. The count is read when the runnable finally executes, so coalescing
     * loses nothing but the intermediate frames.
     */
    private void requestRecStatusRefresh() {
        if (recStatusQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                recStatusQueued.set(false);
                updateRecStatus();
            });
        }
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
        if (c == null || type == null) return;
        // The caret should never be parked in scaffolding (CursorNavigator skips read-only bodies), but the
        // overlay reaches CodeEditor without going through a block, so don't rely on that alone.
        if (c.body().isReadOnly()) return;
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        pendingInsert = new BlockTree.Position(index().ordinalOf(c.body()), insertIndex);
        context.getCodeEditor().addStatement(c.body(), type, insertIndex);
    }

    /** Removes the block the cursor sits on (Delete/Backspace), leaving the caret on the slot above it. */
    private void deleteFocused() {
        InsertionCursor c = cursor();
        StatementBlock stmt = focusedStatement();
        if (c != null && stmt != null) delete(stmt, c.body(), c.index());
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
        if (stmt.isReadOnly() || s == null) return;
        state.setInsertionCursor(new InsertionCursor(body, Math.max(-1, index - 1)));
        context.getCodeEditor().deleteStatement(s);
    }

    private void addBelow(javafx.scene.Node anchor) {
        InsertionCursor c = cursor();
        if (c == null) return;
        var menu = StatementMenu.create(
                context.getProjectAnalyzer(), c.body().getAstNode(), this::insertBelowCursor);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    /** Queues a recorded block sequence for one-per-pulse insertion at the cursor, without arg popovers. */
    private void insertRecordedBatch(List<BlockType> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            info(stage, "Nothing to insert — no recognizable actions were recorded.");
            return;
        }
        recordQueue.addAll(blocks);
        draining = true;
        suppressAutoFill = true;
        drainRecordQueue();
    }

    private void drainRecordQueue() {
        BlockType next = recordQueue.poll();
        if (next == null) {
            draining = false;
            suppressAutoFill = false;
            return;
        }
        insertBelowCursor(next);   // continuation happens in onBlocksUpdated once the re-parse lands
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
        if (c == null || !index().contains(c.body())) {
            state.setInsertionCursor(CursorNavigator.defaultCursor(root));
        }
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
        if (hide) {
            suppressHideTeardown = true;
            if (stage != null) stage.hide();
            if (configDlg != null) configDlg.setOpacity(0);
        } else {
            if (stage != null) { stage.show(); stage.toFront(); }
            if (configDlg != null) { configDlg.setOpacity(1); configDlg.toFront(); }
            suppressHideTeardown = false;
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────────────

    /** Handles a republished block tree: re-render, resolve any pending insertion, continue a recorded batch. */
    private void onBlocksUpdated() {
        refreshMethodBox();   // pick up a method added/removed by hand; keeps a still-valid selection as-is
        render();
        // Before the pending handling below, which may open a popover of its own over the top of this one.
        refreshConfig();
        if (pendingInsert != null) {
            BlockTree.Position p = pendingInsert;
            pendingInsert = null;
            MethodSignature ov = pendingOverload;   // consume the palette-requested overload (if any)
            pendingOverload = null;
            BodyBlock body = index().bodyAt(p.bodyOrdinal());
            if (body != null) {
                List<StatementBlock> statements = body.getStatements();
                if (p.index() >= 0 && p.index() < statements.size()) {
                    // Re-home the cursor onto the freshly inserted block so subsequent adds continue below it.
                    state.setInsertionCursor(new InsertionCursor(body, p.index()));
                    render();
                    StatementBlock inserted = statements.get(p.index());
                    if (ov != null && inserted instanceof MethodInvocationBlock mib) {
                        // A specific overload was picked in the palette bar — apply it to the fresh call. That
                        // is another edit, so the block we'd open the popover on is about to be replaced;
                        // defer to the next pulse instead of opening a popover onto a dead block.
                        mib.switchToOverload(context, ov);
                        if (autoFillEnabled()) pendingConfig = p;
                    } else if (autoFillEnabled() && inserted instanceof MethodInvocationBlock mib) {
                        openConfig(mib);
                    }
                }
            }
        } else if (pendingConfig != null) {
            BlockTree.Position p = pendingConfig;
            pendingConfig = null;
            if (index().statementAt(p) instanceof MethodInvocationBlock mib) openConfig(mib);
        }
        // Continue draining a recorded batch after the cursor has re-homed onto the last insert.
        if (draining) {
            if (!recordQueue.isEmpty()) Platform.runLater(this::drainRecordQueue);
            else { draining = false; suppressAutoFill = false; }
        }
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
        BodyBlock scoped = index().methodBody(selectedMethod);
        if (scoped != null) {
            tree.render(List.of(scoped), cursor());
            return;
        }
        List<BodyBlock> tops = index().topLevelBodies();
        if (tops.isEmpty()) tree.showMessage("Program is empty.");
        else tree.render(tops, cursor());
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
        return !suppressAutoFill && autoFillArgs != null && autoFillArgs.isSelected();
    }

    /**
     * The call config popover: an <b>overload selector</b> (when the method has more than one) plus a row per
     * parameter. Each parameter gets its specialized editor when one applies ({@code Rect} → draw a rectangle,
     * {@code ImageTemplate}/{@code ImageTemplateGroup} → pick/capture, {@code CaptureSource}/{@code Window} →
     * chooser — via {@link PickerRegistry}), otherwise a generic expression picker, so <em>every</em> argument
     * is editable — not only the drawable ones. Opens as a small always-on-top window so drawing overlays it
     * while the target app stays visible.
     *
     * <p>It stays open until dismissed: the rows are rebuilt by {@link #refreshConfig} after each edit rather
     * than the window being closed, so several arguments can be filled in one visit and each shows its new
     * value as it lands.
     */
    private void openConfig(MethodInvocationBlock mib) {
        // One popover at a time; a second would orphan the first (its onHidden clears the tracking fields, so
        // it must close before the new target is recorded).
        if (configDlg != null) configDlg.close();
        configTarget = index().locate(mib);

        // Capped in a ScrollPane so a call with many parameters (e.g. Fill) scrolls instead of growing the
        // popover taller than the screen, with the bottom rows landing off-screen and unreachable.
        ScrollPane scroll = new ScrollPane(configContent(mib));
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        double maxHeight = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.7;
        scroll.setMaxHeight(maxHeight);
        configScroll = scroll;

        Stage dlg = new Stage();
        dlg.setTitle("Configure arguments");
        dlg.setAlwaysOnTop(true);
        Scene sc = new Scene(scroll);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) sc.getStylesheets().add(css.toExternalForm());
        applyThemeClass(sc.getRoot());
        dlg.setScene(sc);
        configDlg = dlg;
        dlg.setOnHidden(e -> {
            if (configDlg == dlg) { configDlg = null; configScroll = null; configTarget = null; }
            // A popover closed while dimmed for a capture would otherwise leave the flag armed, and the next
            // real close of the HUD would skip its teardown entirely.
            suppressHideTeardown = false;
        });
        dlg.show();
        // After show(): the dialog has no width/height to place against until it has been sized to its scene.
        placeBesideHud(dlg);
        // The HUD stands down from its own re-raise while this is open (see show()), so promoting the popover
        // is what actually keeps it above both the HUD and a fullscreen game.
        OverlayToolbars.promoteAboveFullscreen(dlg);
        dlg.toFront();
    }

    /**
     * The popover's rows for {@code mib}: the header, the overload selector, one editor per argument and the
     * Done button. Built fresh on open and again after every re-parse, because each picker's write replaces the
     * block and all of its argument nodes.
     */
    private VBox configContent(MethodInvocationBlock mib) {
        List<ExpressionBlock> args = mib.getArgumentBlocks();
        List<ResolvedType> paramTypes = mib.resolveParamTypes(context);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setStyle(PANEL);
        content.getChildren().add(label("Configure  " + mib.getScope() + "." + mib.getMethodName() + "(…)"));

        // Overload selector: switch this call to a different overload. The re-parse that follows replaces the
        // block, and the rebuild redraws these rows against the new overload's parameters.
        List<MethodSignature> overloads = mib.overloadSignatures(context);
        if (overloads.size() > 1) {
            ComboBox<MethodSignature> overloadBox =
                    new ComboBox<>(javafx.collections.FXCollections.observableArrayList(overloads));
            overloadBox.setValue(mib.currentSignature(context));
            overloadBox.setMaxWidth(Double.MAX_VALUE);
            overloadBox.setOnAction(e -> {
                MethodSignature sel = overloadBox.getValue();
                if (sel != null && !sel.equals(mib.currentSignature(context))) mib.switchToOverload(context, sel);
            });
            HBox line = new HBox(8, label("Overload:"), overloadBox);
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(overloadBox, Priority.ALWAYS);
            content.getChildren().add(line);
        }

        for (int i = 0; i < args.size(); i++) {
            ResolvedType pt = i < paramTypes.size() ? paramTypes.get(i) : ResolvedType.UNKNOWN;
            ExpressionBlock arg = args.get(i);
            PickerContext ctx = new PickerContext(context, arg, pt, mib.getScope(), mib.getMethodName(), i);
            javafx.scene.Node editor = PickerRegistry.pickerNodeFor(ctx);
            if (editor == null) editor = genericArgEditor(mib, arg, pt);   // every arg editable, not just drawable ones
            String name = paramLabel(mib, i, pt);
            HBox line = new HBox(8, label(name + ":"), editor);
            line.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(line);
        }
        if (args.isEmpty()) {
            content.getChildren().add(dimLabel("This call takes no arguments."));
        }

        // An explicit dismissal. The window's own title bar is the only other way out, and between
        // setAlwaysOnTop and promoteAboveFullscreen there are window managers that don't leave one.
        Button done = new Button("Done");
        done.setDefaultButton(true);
        done.setOnAction(e -> { if (configDlg != null) configDlg.close(); });
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox actions = new HBox(8, spring, done);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(actions);
        return content;
    }

    /**
     * Rebuilds the open popover against the re-parsed tree, or closes it when its call is gone (deleted, or its
     * body edited out from under it). Same {@link Stage} either way — the window keeps its position, so filling
     * a second argument doesn't move the popover out from under the pointer.
     */
    private void refreshConfig() {
        if (configDlg == null || configScroll == null || configTarget == null) return;
        if (index().statementAt(configTarget) instanceof MethodInvocationBlock mib) {
            configScroll.setContent(configContent(mib));
        } else {
            configDlg.close();
        }
    }

    /**
     * Puts the config popover immediately to the <b>right</b> of the HUD, top-aligned with it — the HUD is
     * tucked into the target window's top-left corner, so the space to its right is the one place a second
     * window neither covers the HUD nor the region the user is about to draw on. Falls back to the HUD's left
     * when there isn't room, and finally clamps into the screen so no row lands off-display.
     */
    private void placeBesideHud(Stage dlg) {
        if (stage == null) return;
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getScreensForRectangle(
                        stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()).stream()
                .findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();

        double x = stage.getX() + stage.getWidth() + 12;
        if (x + dlg.getWidth() > screen.getMaxX()) {
            double left = stage.getX() - dlg.getWidth() - 12;
            x = (left >= screen.getMinX()) ? left : screen.getMaxX() - dlg.getWidth();
        }
        double y = Math.min(stage.getY(), screen.getMaxY() - dlg.getHeight());
        dlg.setX(Math.max(screen.getMinX(), x));
        dlg.setY(Math.max(screen.getMinY(), y));
    }

    /**
     * A generic editor for a parameter that has no specialized picker: a button showing the current expression
     * that opens the type-aware expression menu and rewrites the argument via {@link ExpressionMenu}.
     * The re-parse a pick triggers replaces the argument node; {@link #refreshConfig} redraws this row against
     * the new one rather than the popover closing.
     */
    private javafx.scene.Node genericArgEditor(MethodInvocationBlock mib, ExpressionBlock arg, ResolvedType paramType) {
        ASTNode node = arg.getAstNode();
        String current = (node != null) ? node.toString() : "";
        boolean empty = current == null || current.isBlank() || "null".equals(current);
        Button b = new Button(empty ? "Set…" : current);
        b.setMaxWidth(240);
        b.setOnAction(e -> {
            if (!(arg.getAstNode() instanceof org.eclipse.jdt.core.dom.Expression expr)) return;
            var menu = ExpressionMenu.create(
                    paramType == null ? ResolvedType.UNKNOWN : paramType, false, context, mib.getAstNode(), null,
                    sel -> ExpressionMenu.applySelection(context, expr, sel));
            menu.show(b, Side.BOTTOM, 0, 0);
        });
        return b;
    }

    /** A "{@code Type name}" label for parameter {@code i}, from the current overload's names when available. */
    private String paramLabel(MethodInvocationBlock mib, int i, ResolvedType pt) {
        MethodSignature sig = mib.currentSignature(context);
        if (sig != null && i < sig.paramNames().size()) {
            String typeName = (pt != null && pt.simpleName() != null) ? pt.simpleName()
                    : (i < sig.paramTypes().size() ? sig.paramTypes().get(i).simpleName() : "arg");
            return typeName + " " + sig.paramNames().get(i);
        }
        return (pt != null && pt.simpleName() != null) ? pt.simpleName() : ("arg " + i);
    }

}
