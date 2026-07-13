package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.services.record.MacroTranslator;
import com.botmaker.studio.services.record.RecordingSession;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.events.CoreApplicationEvents.UIBlocksUpdatedEvent;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;
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
 * <p>An {@link InsertionCursor} (kept on {@link ProjectState}) marks the <em>focused</em> block; the toolbar's
 * <b>step</b> buttons move it and the palette inserts a new block just beneath it. The palette has two modes:
 * <b>Basic</b> exposes only the core bot actions ({@link BlockCatalog#botActions()}); <b>Advanced</b> adds an
 * <em>Add block</em> button opening the full categorized statement menu (control flow, variables, print, …).
 *
 * <p><b>Record mode</b> (Linux/X11 only) merges the former standalone macro recorder: pressing Record observes
 * real clicks/keys/waits via a {@link RecordingSession}, and Stop translates them ({@link MacroTranslator}) and
 * inserts the resulting blocks <em>at the cursor</em>, progressively — so recording grows the same tree that
 * hand-authoring does.
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
                                ScreenCaptureService capture, CaptureTarget target) {
        this.context = context;
        this.state = context.getState();
        this.settings = settings;
        this.capture = capture;
        this.target = target;
    }

    /**
     * Opens (or focuses) the overlay editor for the active file. Requires the project's default capture target
     * to be a window (warns and does nothing otherwise). When {@code startRecording} is true, recording begins
     * as soon as the overlay is shown (used by the "Record Macro" toolbar button). Must be called on the FX thread.
     */
    public static void open(Window owner, CodeEditorService context, ProjectSettingsService settings,
                            ScreenCaptureService capture, boolean startRecording) {
        if (active != null && active.stage != null && active.stage.isShowing()) {
            active.stage.toFront();
            if (startRecording && active.session != null && !active.session.isRecording()) active.startRecording();
            return;
        }
        CaptureTarget target = null;
        try {
            target = settings.defaultTarget();
        } catch (Exception ignored) {
            // no default configured
        }
        if (target == null) {
            warn(owner, "Overlay editor needs a capture target.\n\nOpen \"Capture Targets\" and set a window, "
                    + "monitor or the desktop as the default first.");
            return;
        }
        ProgramShapeOverlay overlay = new ProgramShapeOverlay(context, settings, capture, target);
        overlay.autoStartRecording = startRecording;
        active = overlay;
        overlay.start(owner);
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
                capture.resizeTarget(wt, ref.width(), ref.height());
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

        Scene scene = new Scene(rootPane, 340, 480, Color.TRANSPARENT);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

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

        root = context.getRootBlock().orElse(null);
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
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close overlay"));
        close.setOnAction(e -> { if (stage != null) stage.close(); });
        header = new HBox(8, title, spring, close);
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

        VBox controls = new VBox(6, paletteBar, stepRow, recordRow, autoFillArgs);
        controls.setPadding(new Insets(8));
        controls.setStyle(PANEL);
        return controls;
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
        for (String facade : com.botmaker.studio.palette.SdkApi.FACADE_CLASSES) {
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
        VBox treePanel = new VBox(scroll);
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
        int insertIndex = Math.min(c.index() + 1, c.body().getStatements().size());
        pendingInsert = new PendingInsert(bodyOrdinal(c.body()), insertIndex);
        context.getCodeEditor().addStatement(c.body(), type, insertIndex);
    }

    private void addBelow(javafx.scene.Node anchor) {
        InsertionCursor c = cursor();
        if (c == null) return;
        var menu = ExpressionMenuFactory.createStatementMenu(this::insertBelowCursor);
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
        // Render each render-root body (a body not nested inside another body); nested control-flow bodies are
        // drawn indented by renderBody's recursion. This makes method bodies (children of a method block, not of
        // a body) the entry points — otherwise no body qualifies and the list shows empty.
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
        content.getChildren().add(new Label("Configure  " + mib.getScope() + "." + mib.getMethodName() + "(…)"));

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
            HBox line = new HBox(8, new Label("Overload:"), overloadBox);
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
            HBox line = new HBox(8, new Label(name + ":"), editor);
            line.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(line);
        }
        if (args.isEmpty()) {
            content.getChildren().add(dimLabel("This call takes no arguments."));
        }

        Stage dlg = new Stage();
        dlg.setTitle("Configure arguments");
        dlg.setAlwaysOnTop(true);
        Scene sc = new Scene(content);
        java.net.URL css = getClass().getResource("/css/blocks.css");
        if (css != null) sc.getStylesheets().add(css.toExternalForm());
        dlg.setScene(sc);
        if (stage != null) { dlg.setX(stage.getX() + 40); dlg.setY(stage.getY() + 80); }
        configDlg = dlg;
        dlg.setOnHidden(e -> { if (configDlg == dlg) configDlg = null; });
        dlg.show();
    }

    /**
     * A generic editor for a parameter that has no specialized picker: a button showing the current expression
     * that opens the type-aware expression menu and rewrites the argument via {@link ExpressionMenuFactory}.
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
            var menu = ExpressionMenuFactory.createExpressionTypeMenu(
                    paramType == null ? ResolvedType.UNKNOWN : paramType, false, context, mib.getAstNode(), null,
                    sel -> {
                        ExpressionMenuFactory.applySelection(context, expr, sel);
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
