package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
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
import com.botmaker.studio.validation.BlockValidator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
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

    /** Rounded, semi-opaque panel background shared by every HUD panel (mirrors the capture/record toolbars). */
    private static final String PANEL = "-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;";
    private static final String LABEL = "-fx-text-fill: #c9d4e6;";

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
    private final VBox rows = new VBox(2);
    private CodeBlock root;
    private java.awt.Rectangle windowBounds;

    /** A specific overload requested from the palette bar, applied once the inserted call is re-parsed. */
    private MethodSignature pendingOverload;

    /** The open per-argument config popover (if any), tracked so it can be hidden while a capture overlay is up. */
    private Stage configDlg;
    /** Unsubscribes the capture-overlay visibility listener when the overlay closes. */
    private AutoCloseable captureVisibility;
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

    /** The tree's visible-row-count control, and the pixel height one row (incl. spacing) costs. */
    private static final double ROW_HEIGHT_PX = 24;
    private Spinner<Integer> visibleLines;

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
     * A just-requested insertion, resolved after the next {@link UIBlocksUpdatedEvent}: the DFS ordinal of the
     * target body among all bodies (stable across a re-parse that only adds a bodiless statement) and the slot
     * the new statement lands in. Used to re-home the cursor onto the inserted block and (optionally) open its
     * config popover, since the pre-insert block objects are replaced on re-parse.
     */
    private record PendingInsert(int bodyOrdinal, int index) {}
    private PendingInsert pendingInsert;

    private ProgramShapeOverlay(CodeEditorService context, ProjectSettingsService settings,
                                ScreenCaptureService capture, ActivityService activities, CaptureTarget target) {
        this.context = context;
        this.state = context.getState();
        this.settings = settings;
        this.capture = capture;
        this.activities = activities;
        this.target = target;
    }

    /**
     * Opens (or focuses) the overlay editor for the active file. Requires the project's default capture target
     * to be a window (warns and does nothing otherwise). When {@code startRecording} is true, recording begins
     * as soon as the overlay is shown (used by the "Record Macro" toolbar button). Must be called on the FX thread.
     */
    public static void open(Window owner, CodeEditorService context, ProjectSettingsService settings,
                            ScreenCaptureService capture, ActivityService activities,
                            java.util.function.LongSupplier sessionWindow, boolean startRecording) {
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
            warn(owner, "Overlay editor needs a capture target.\n\nOpen \"Capture Targets\" and set a window, "
                    + "monitor or the desktop as the default first.");
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
        session = new RecordingSession(this::overlayScreenBounds,
                count -> Platform.runLater(this::updateRecStatus));

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
            if (session != null && session.isRecording()) session.stop();
            if (active == this) active = null;
        });
        // Keyboard navigation of the compact block tree: → step into, ← step out, ↑/↓ move, Enter configure.
        scene.setOnKeyPressed(e -> {
            if (scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) return;  // don't steal typing
            switch (e.getCode()) {
                case RIGHT -> move(CursorNavigator.stepInto(cursor()));
                case LEFT -> move(CursorNavigator.stepOut(cursor(), root));
                case UP -> move(CursorNavigator.stepBack(cursor()));
                case DOWN -> move(CursorNavigator.stepOver(cursor()));
                case ENTER -> {
                    if (focusedStatement() instanceof MethodInvocationBlock mib) openConfig(mib);
                }
                default -> { return; }
            }
            e.consume();
        });

        stage.show();
        OverlayToolbars.installDrag(header, stage);   // borderless: drag by the header bar
        OverlayToolbars.promoteAboveFullscreen(stage); // stay above fullscreen games (X11)

        // Hide the HUD (and any open config popover) while a capture draw surface is up, so it doesn't sit
        // over the region/point/template selection — restored when the overlay closes.
        captureVisibility = ScreenCaptureService.addCaptureOverlayListener(new ScreenCaptureService.CaptureOverlayListener() {
            @Override public void onShown() { hideForCapture(true); }
            @Override public void onHidden() { hideForCapture(false); }
        });

        // Re-render on every editor update; guard so a stale subscription (no unsubscribe API) no-ops.
        context.getEventBus().subscribe(UIBlocksUpdatedEvent.class, e -> {
            if (stage != null && stage.isShowing()) {
                root = e.rootBlock();
                Platform.runLater(this::onBlocksUpdated);
            }
        });

        // Keep the activity picker current when an activity is created/renamed/removed elsewhere in the app —
        // it otherwise only ever reflects the list captured at the moment the overlay was opened.
        context.getEventBus().subscribe(ActivitiesChangedEvent.class, e -> {
            if (stage != null && stage.isShowing()) {
                Platform.runLater(this::refreshActivityBox);
            }
        });

        // The handler is installed *after* the initial value, so the one explicit call below is the only one —
        // ComboBox.setValue's action-firing behaviour is not something to have two code paths depend on.
        String initial = preferredActivity(activityNames());
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
        Button into  = iconButton("⤵", "Step into", () -> move(CursorNavigator.stepInto(cursor())));
        Button out   = iconButton("⤴", "Step out",  () -> move(CursorNavigator.stepOut(cursor(), root)));
        Button up    = iconButton("▲", "Step up",   () -> move(CursorNavigator.stepBack(cursor())));
        Button down  = iconButton("▼", "Step down", () -> move(CursorNavigator.stepOver(cursor())));
        Button refresh = iconButton("⟳", "Refresh", this::render);
        HBox stepRow = new HBox(6, label("Step:"), up, down, into, out, refresh);
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
        List<String> names = activityNames();
        activityBox = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(names));
        activityBox.setTooltip(new Tooltip("The activity that new and recorded blocks are inserted into"));
        HBox row = new HBox(6, label("Activity:"), activityBox);
        if (names.isEmpty()) {
            activityBox.setDisable(true);
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

    /**
     * Refreshes {@link #activityBox}'s items after an {@link ActivitiesChangedEvent} (an activity was
     * created/renamed/removed elsewhere). Leaves an already-valid selection alone — creating an unrelated
     * activity shouldn't yank the user off what they're editing — but picks a default when the box was
     * previously empty or its selection no longer exists.
     */
    private void refreshActivityBox() {
        if (activityBox == null) return;
        List<String> names = activityNames();
        activityBox.getItems().setAll(names);
        activityBox.setDisable(names.isEmpty());
        if (names.isEmpty()) {
            activityBox.setPromptText("none yet");
            return;
        }
        String current = activityBox.getValue();
        if (current == null || !names.contains(current)) {
            String next = preferredActivity(names);
            activityBox.setValue(next);
            selectActivity(next);
        }
    }

    /**
     * Which activity to open on: the one last authored into, else the flow's start node, else the first. The
     * last-used one is remembered per project so reopening the overlay resumes where the last session stopped.
     */
    private String preferredActivity(List<String> names) {
        if (names.isEmpty()) return null;
        String last = settings.current().lastRecordedActivity();
        if (last != null && names.contains(last)) return last;
        String start = activities.current().flow().resolvedStart(names);
        return names.contains(start) ? start : names.get(0);
    }

    /** Switches the editor to {@code activities/<name>.java}, parks the cursor in its {@code run()}, remembers it. */
    private void selectActivity(String name) {
        if (name == null) return;
        java.nio.file.Path file = context.getConfig().activitiesPackageDir().resolve(name + ".java");
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

    /**
     * The caret inside an activity's {@code run()}: the slot just <em>before</em> a trailing {@code return}, so
     * a recorded block lands where it will actually execute — the stub's body is only {@code return
     * Outcome.NEXT;}, and inserting below that would produce unreachable code, which is the same silent
     * nothing-happened this picker exists to fix. Falls back to the tree's default cursor for a stub whose
     * {@code run()} has been renamed or removed by hand.
     */
    // Package-private for ProgramShapeOverlayCursorTest: this rule fails silently when it is wrong.
    static InsertionCursor runCursor(CodeBlock root) {
        return methodCursor(root, "run");
    }

    /** {@link #runCursor(CodeBlock)} generalized to any method name — the caret inside {@code methodName}'s body. */
    static InsertionCursor methodCursor(CodeBlock root, String methodName) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (!(b instanceof MethodDeclarationBlock m) || !java.util.Objects.equals(methodName, m.getMethodName())) continue;
            for (CodeBlock child : m.getChildren()) {
                if (!(child instanceof BodyBlock body) || body.isReadOnly()) continue;
                List<StatementBlock> statements = body.getStatements();
                boolean endsWithReturn = !statements.isEmpty()
                        && statements.get(statements.size() - 1).getAstNode()
                                instanceof org.eclipse.jdt.core.dom.ReturnStatement;
                return new InsertionCursor(body, statements.size() - (endsWithReturn ? 2 : 1));
            }
        }
        return CursorNavigator.defaultCursor(root);
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
        if (root == null) return List.of();
        List<String> names = new ArrayList<>();
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof MethodDeclarationBlock m) names.add(m.getMethodName());
        }
        return names;
    }

    /** The body of the method named {@code methodName}, or {@code null} if no such method exists. */
    private static BodyBlock methodBody(CodeBlock root, String methodName) {
        if (root == null || methodName == null) return null;
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (!(b instanceof MethodDeclarationBlock m) || !methodName.equals(m.getMethodName())) continue;
            for (CodeBlock child : m.getChildren()) {
                if (child instanceof BodyBlock body) return body;
            }
        }
        return null;
    }

    /** Switches which method's blocks are rendered/edited, re-homing the cursor into it. */
    private void selectMethod(String name) {
        selectedMethod = name;
        InsertionCursor c = methodCursor(root, name);
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
            InsertionCursor c = methodCursor(root, selectedMethod);
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
        rows.setPadding(new Insets(6));
        rows.setStyle("-fx-background-color: transparent;");
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        // How many rows are visible before the pane scrolls internally. This is a *preferred* height, not
        // just a cap: with the HUD's fixed Scene size gone (see show()), the window sizes to the sum of its
        // children's preferred heights, so a ScrollPane with no explicit prefHeight falls back to its own
        // tiny default — that's what made only one row visible before this control existed.
        visibleLines = new Spinner<>(3, 30, 8);
        visibleLines.setEditable(true);
        visibleLines.setPrefWidth(60);
        visibleLines.setTooltip(new Tooltip("How many rows are visible at once before the tree scrolls"));
        scroll.setPrefHeight(visibleLines.getValue() * ROW_HEIGHT_PX + 12);
        scroll.setMinHeight(Region.USE_PREF_SIZE);
        visibleLines.valueProperty().addListener((obs, old, val) -> {
            scroll.setPrefHeight(val * ROW_HEIGHT_PX + 12);
            // The Scene only auto-sizes to content once, at first show(); a later pref-height change needs
            // an explicit resize to actually grow/shrink the window instead of just clipping/under-filling.
            if (stage != null) Platform.runLater(stage::sizeToScene);
        });
        HBox linesRow = new HBox(6, label("Show:"), visibleLines, label("lines"));
        linesRow.setAlignment(Pos.CENTER_LEFT);

        VBox treePanel = new VBox(6, linesRow, scroll);
        treePanel.setStyle(PANEL);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(treePanel, Priority.ALWAYS);
        return treePanel;
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

    private java.awt.Rectangle overlayScreenBounds() {
        if (stage == null) return null;
        return new java.awt.Rectangle((int) stage.getX(), (int) stage.getY(),
                (int) stage.getWidth(), (int) stage.getHeight());
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
        pendingInsert = new PendingInsert(bodyOrdinal(c.body()), insertIndex);
        context.getCodeEditor().addStatement(c.body(), type, insertIndex);
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
        if (c == null || !CursorNavigator.collectAll(root).contains(c.body())) {
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
        if (pendingInsert != null) {
            PendingInsert p = pendingInsert;
            pendingInsert = null;
            MethodSignature ov = pendingOverload;   // consume the palette-requested overload (if any)
            pendingOverload = null;
            List<BodyBlock> bodies = allBodies(root);
            if (p.bodyOrdinal() >= 0 && p.bodyOrdinal() < bodies.size()) {
                BodyBlock body = bodies.get(p.bodyOrdinal());
                List<StatementBlock> statements = body.getStatements();
                if (p.index() >= 0 && p.index() < statements.size()) {
                    // Re-home the cursor onto the freshly inserted block so subsequent adds continue below it.
                    state.setInsertionCursor(new InsertionCursor(body, p.index()));
                    render();
                    StatementBlock inserted = statements.get(p.index());
                    if (ov != null && inserted instanceof MethodInvocationBlock mib) {
                        // A specific overload was picked in the palette bar — apply it to the fresh call.
                        mib.switchToOverload(context, ov);
                    } else if (!suppressAutoFill && autoFillArgs != null && autoFillArgs.isSelected()
                            && inserted instanceof MethodInvocationBlock mib) {
                        openConfig(mib);
                    }
                }
            }
        }
        // Continue draining a recorded batch after the cursor has re-homed onto the last insert.
        if (draining) {
            if (!recordQueue.isEmpty()) Platform.runLater(this::drainRecordQueue);
            else { draining = false; suppressAutoFill = false; }
        }
    }

    private void render() {
        rows.getChildren().clear();
        ensureCursor();
        if (root == null) {
            rows.getChildren().add(dimLabel("No open file."));
            return;
        }
        // Scoped to the selected method (buildMethodRow) when there is one, so unrelated methods' statements
        // don't appear mixed into one flat list. Falls back to every top-level body when none is selected
        // (e.g. a file with no methods) — the old "Program is empty." path stays reachable either way.
        BodyBlock scoped = methodBody(root, selectedMethod);
        if (scoped != null) {
            renderBody(scoped, 0);
            return;
        }
        boolean any = false;
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof BodyBlock body && !isNestedInBody(body)) {
                renderBody(body, 0);
                any = true;
            }
        }
        if (!any) rows.getChildren().add(dimLabel("Program is empty."));
    }

    /** All bodies in DFS order (matches {@link CursorNavigator#collectAll}), used for stable ordinal lookup. */
    private List<BodyBlock> allBodies(CodeBlock from) {
        List<BodyBlock> out = new ArrayList<>();
        for (CodeBlock b : CursorNavigator.collectAll(from)) {
            if (b instanceof BodyBlock bb) out.add(bb);
        }
        return out;
    }

    private int bodyOrdinal(BodyBlock body) {
        return allBodies(root).indexOf(body);
    }

    /**
     * True when {@code body} is nested inside <em>another</em> {@code BodyBlock}'s subtree (i.e. it is a
     * control-flow child body reached via {@link #renderBody} recursion, not a top-level method body).
     */
    private boolean isNestedInBody(BodyBlock body) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b == body || !(b instanceof BodyBlock other)) continue;
            if (containsDescendant(other, body)) return true;
        }
        return false;
    }

    /** True when {@code target} appears anywhere in {@code ancestor}'s recursive children. */
    private static boolean containsDescendant(CodeBlock ancestor, CodeBlock target) {
        if (!(ancestor instanceof BlockWithChildren bwc)) return false;
        for (CodeBlock child : bwc.getChildren()) {
            if (child == target || containsDescendant(child, target)) return true;
        }
        return false;
    }

    private void renderBody(BodyBlock body, int depth) {
        InsertionCursor c = cursor();
        var statements = body.getStatements();
        if (statements.isEmpty()) {
            rows.getChildren().add(emptyRow(body, depth));
            return;
        }
        // A caret sitting before the first statement has no row to highlight, so it gets one of its own —
        // otherwise the HUD shows a tree with no focus anywhere and looks like it lost the cursor.
        if (c != null && c.body() == body && c.index() < 0) rows.getChildren().add(caretRow(depth));
        for (int i = 0; i < statements.size(); i++) {
            StatementBlock stmt = statements.get(i);
            boolean focused = c != null && c.body() == body && c.index() == i;
            rows.getChildren().add(statementRow(stmt, body, i, depth, focused));
            // Draw child bodies (if/while/for/lambda) indented under their owner row.
            if (stmt instanceof BlockWithChildren bwc) {
                for (CodeBlock child : bwc.getChildren()) {
                    if (child instanceof BodyBlock childBody) renderBody(childBody, depth + 1);
                }
            }
        }
    }

    private HBox statementRow(StatementBlock stmt, BodyBlock body, int index, int depth, boolean focused) {
        boolean incomplete = BlockValidator.hasEmptySlot(stmt);
        Label text = new Label(compactLabel(stmt) + (incomplete ? "   ⚠ missing value" : ""));
        // An empty argument/condition slot shows red before any compile so it's obvious what still needs filling.
        text.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-text-fill: "
                + (incomplete ? "#ff6b6b;" : "#dfe6f2;"));
        HBox row = new HBox(6, indent(depth), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 6, 3, 6));
        row.setStyle(focused
                ? "-fx-background-color: rgba(74,144,226,0.35); -fx-background-radius: 4; "
                        + "-fx-border-color: #4a90e2; -fx-border-radius: 4;"
                : "-fx-background-color: transparent;");
        row.setOnMouseClicked(e -> move(new InsertionCursor(body, index)));
        row.setPickOnBounds(true);

        // Config (⚙) button for SDK/method calls: lets the user draw the rect / pick the template for the
        // call's arguments without leaving the overlay (reuses the standard argument pickers).
        if (stmt instanceof MethodInvocationBlock mib) {
            Region spring = new Region();
            HBox.setHgrow(spring, Priority.ALWAYS);
            Button config = iconButton("⚙", "Configure arguments (draw rect / pick template)", () -> openConfig(mib));
            config.setMinWidth(26);
            row.getChildren().addAll(spring, config);
        }
        return row;
    }

    /** The caret's own row, drawn when it sits before a body's first statement. */
    private static HBox caretRow(int depth) {
        Label text = new Label("▸ next block goes here");
        text.setStyle("-fx-font-style: italic; -fx-text-fill: #9fc0ff;");
        HBox row = new HBox(6, indent(depth), text);
        row.setPadding(new Insets(3, 6, 3, 6));
        row.setStyle("-fx-background-color: rgba(74,144,226,0.35); -fx-background-radius: 4;");
        return row;
    }

    private HBox emptyRow(BodyBlock body, int depth) {
        InsertionCursor c = cursor();
        boolean focused = c != null && c.body() == body;
        Label text = new Label("· (empty) ·");
        text.setStyle("-fx-font-style: italic; -fx-text-fill: #8b93a1;");
        HBox row = new HBox(6, indent(depth), text);
        row.setPadding(new Insets(3, 6, 3, 6));
        if (focused) row.setStyle("-fx-background-color: rgba(74,144,226,0.35); -fx-background-radius: 4;");
        row.setOnMouseClicked(e -> move(new InsertionCursor(body, 0)));
        return row;
    }

    /**
     * The call config popover: an <b>overload selector</b> (when the method has more than one) plus a row per
     * parameter. Each parameter gets its specialized editor when one applies ({@code Rect} → draw a rectangle,
     * {@code ImageTemplate}/{@code ImageTemplateGroup} → pick/capture, {@code CaptureSource}/{@code Window} →
     * chooser — via {@link PickerRegistry}), otherwise a generic expression picker, so <em>every</em> argument
     * is editable — not only the drawable ones. Opens as a small always-on-top window so drawing overlays it
     * while the target app stays visible.
     */
    private void openConfig(MethodInvocationBlock mib) {
        List<ExpressionBlock> args = mib.getArgumentBlocks();
        List<ResolvedType> paramTypes = mib.resolveParamTypes(context);

        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setStyle(PANEL);
        content.getChildren().add(label("Configure  " + mib.getScope() + "." + mib.getMethodName() + "(…)"));

        // Overload selector: switch this call to a different overload. The re-parse replaces the block, so the
        // popover is closed after switching — the user reopens (⚙ / Enter) to edit the new overload's slots.
        List<MethodSignature> overloads = mib.overloadSignatures(context);
        if (overloads.size() > 1) {
            javafx.scene.control.ComboBox<MethodSignature> overloadBox =
                    new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList(overloads));
            overloadBox.setValue(mib.currentSignature(context));
            overloadBox.setMaxWidth(Double.MAX_VALUE);
            overloadBox.setOnAction(e -> {
                MethodSignature sel = overloadBox.getValue();
                if (sel != null && !sel.equals(mib.currentSignature(context))) {
                    mib.switchToOverload(context, sel);
                    if (configDlg != null) configDlg.close();
                }
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

        // Capped in a ScrollPane so a call with many parameters (e.g. Fill) scrolls instead of growing the
        // popover taller than the screen, with the bottom rows landing off-screen and unreachable.
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        double maxHeight = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.7;
        scroll.setMaxHeight(maxHeight);

        Stage dlg = new Stage();
        dlg.setTitle("Configure arguments");
        dlg.setAlwaysOnTop(true);
        Scene sc = new Scene(scroll);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) sc.getStylesheets().add(css.toExternalForm());
        applyThemeClass(sc.getRoot());
        dlg.setScene(sc);
        if (stage != null) { dlg.setX(stage.getX() + 40); dlg.setY(stage.getY() + 80); }
        configDlg = dlg;
        dlg.setOnHidden(e -> { if (configDlg == dlg) configDlg = null; });
        dlg.show();
    }

    /** Adds the app's current theme style class to {@code node}, mirroring {@code UIManager.applyThemeToScene}. */
    private static void applyThemeClass(javafx.scene.Parent node) {
        String styleClass = switch (com.botmaker.studio.ui.render.theme.BlockTheme.getCurrentThemeType()) {
            case DEFAULT -> "default-theme";
            case DARK -> "dark-theme";
            case BLACK -> "black-theme";
            case HIGH_CONTRAST -> "high-contrast-theme";
        };
        node.getStyleClass().add(styleClass);
    }

    /**
     * A generic editor for a parameter that has no specialized picker: a button showing the current expression
     * that opens the type-aware expression menu and rewrites the argument via {@link ExpressionMenu}.
     * Closes the popover after a pick, since the re-parse replaces the argument node.
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
                    sel -> {
                        ExpressionMenu.applySelection(context, expr, sel);
                        if (configDlg != null) configDlg.close();
                    });
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

    // ── small helpers ────────────────────────────────────────────────────────────────────────────────────

    private static Button iconButton(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setMinWidth(30);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle(LABEL);
        return l;
    }

    private static Label dimLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #8b93a1;");
        return l;
    }

    private static Region indent(int depth) {
        Region r = new Region();
        r.setMinWidth(depth * 16.0);
        r.setPrefWidth(depth * 16.0);
        return r;
    }

    /** One-line summary of a block: the first source line of its AST node, trimmed and truncated. */
    private static String compactLabel(CodeBlock block) {
        ASTNode n = block.getAstNode();
        if (n == null) return block.getClass().getSimpleName();
        String s = n.toString().strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).strip();
        if (s.endsWith("{")) s = s.substring(0, s.length() - 1).strip();
        return s.length() > 70 ? s.substring(0, 67) + "…" : s;
    }

    private static void warn(Window owner, String message) {
        alert(owner, Alert.AlertType.WARNING, message);
    }

    private static void info(Window owner, String message) {
        alert(owner, Alert.AlertType.INFORMATION, message);
    }

    private static void alert(Window owner, Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
    }
}
