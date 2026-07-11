package com.botmaker.studio.services.record;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;
import com.botmaker.studio.blocks.func.MainBlock;
import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.ui.app.record.MacroRecorderToolbar;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "Record Macro" tool: performs a real interaction against the project's default <b>window</b> target and
 * turns the recorded clicks/keys/waits into blocks appended to the bot's main method.
 *
 * <p>A small always-on-top {@link MacroRecorderToolbar} (Record / Pause / Stop) floats above the window and
 * never covers it, so the app stays fully clickable while recording. Real input is observed globally via the
 * shared {@link InputListener} (X11 XRecord — Linux only; passive, so it never blocks the app's input). Events
 * are buffered while recording and translated on Stop by {@link MacroTranslator}; the resulting blocks are
 * inserted one per FX pulse (so each re-parse lands before the next insert), which also renders them
 * progressively.
 *
 * <p>Coordinates are recorded window-relative (clicks outside the window — e.g. on this toolbar — are dropped),
 * using the window's origin probed once at start. Assumes the window isn't moved during a recording.
 */
public final class MacroRecorder {

    private final Window owner;
    private final ScreenCaptureService capture;
    private final EventBus eventBus;
    private final CodeEditor codeEditor;
    private final ProjectState state;
    private final CaptureTarget.WindowTarget target;

    private final List<InputEvent> buffer = Collections.synchronizedList(new ArrayList<>());
    private MacroRecorderToolbar toolbar;
    private InputListener listener;
    private java.awt.Rectangle windowBounds;
    private volatile boolean recording;
    private volatile boolean paused;
    private volatile int actionCount;

    private MacroRecorder(Window owner, ScreenCaptureService capture, EventBus eventBus,
                          CodeEditor codeEditor, ProjectState state, CaptureTarget.WindowTarget target) {
        this.owner = owner;
        this.capture = capture;
        this.eventBus = eventBus;
        this.codeEditor = codeEditor;
        this.state = state;
        this.target = target;
    }

    /**
     * Opens the recorder for the project's default window target. Explains and does nothing when the platform
     * is unsupported, the default target isn't a window, or the window can't be found. Must be called on the FX thread.
     */
    public static void open(Window owner, ProjectConfig config, ProjectSettingsService settings,
                            ScreenCaptureService capture, EventBus eventBus,
                            CodeEditor codeEditor, ProjectState state) {
        if (!InputListenerFactory.isSupported()) {
            warn(owner, "Macro recording is currently only available on Linux (X11).");
            return;
        }
        CaptureTarget target = null;
        try {
            target = settings.defaultTarget();
        } catch (Exception ignored) {
            // no default configured
        }
        if (!(target instanceof CaptureTarget.WindowTarget wt)) {
            warn(owner, "Macro recording needs a window capture target.\n\nOpen \"Capture Targets\" and set a "
                    + "window as the default first.");
            return;
        }
        new MacroRecorder(owner, capture, eventBus, codeEditor, state, wt).start();
    }

    private void start() {
        WindowShot shot = capture.captureWindow(target);
        if (shot == null) {
            warn(owner, "Couldn't find the window \"" + target.titleSubstring() + "\". Is it open?");
            return;
        }
        windowBounds = shot.bounds();
        toolbar = new MacroRecorderToolbar(owner, windowBounds, this::togglePrimary, this::stopAndInsert, this::cancel);
    }

    // ── Recording state machine ──────────────────────────────────────────────────────────────────────────

    private void togglePrimary() {
        if (!recording) startRecording();
        else setPaused(!paused);
    }

    private void startRecording() {
        try {
            listener = InputListenerFactory.create();
            listener.start(this::onEvent);
        } catch (Exception ex) {
            warn(owner, "Couldn't start input recording: " + ex.getMessage());
            listener = null;
            return;
        }
        buffer.clear();
        actionCount = 0;
        recording = true;
        paused = false;
        toolbar.setPrimaryText("⏸ Pause");
        toolbar.enableStop(true);
        updateStatus();
    }

    private void setPaused(boolean value) {
        paused = value;
        toolbar.setPrimaryText(value ? "▶ Resume" : "⏸ Pause");
        updateStatus();
    }

    /** Called on the native listener thread — keep it cheap and marshal UI updates to the FX thread. */
    private void onEvent(InputEvent e) {
        if (!recording || paused) return;
        buffer.add(e);
        if (e instanceof InputEvent.ButtonPress || e instanceof InputEvent.KeyPress) {
            actionCount++;
            Platform.runLater(this::updateStatus);
        }
    }

    private void updateStatus() {
        if (toolbar == null) return;
        String label = !recording ? "Ready to record" : (paused ? "Paused" : "Recording") + " — " + actionCount + " actions";
        toolbar.setStatus(label);
    }

    // ── Stop / cancel ────────────────────────────────────────────────────────────────────────────────────

    private void stopAndInsert() {
        stopListener();
        List<InputEvent> events = new ArrayList<>(buffer);
        MacroTranslator.WindowRef ref = new MacroTranslator.WindowRef(
                target.titleSubstring(), windowBounds.x, windowBounds.y, windowBounds.width, windowBounds.height);
        List<BlockType> blocks = MacroTranslator.translate(events, ref);
        closeToolbar();
        if (blocks.isEmpty()) {
            info(owner, "Nothing to insert — no recognizable actions were recorded.");
            return;
        }
        insertSequentially(blocks, 0);
    }

    private void cancel() {
        stopListener();
        closeToolbar();
    }

    private void stopListener() {
        recording = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception ignored) {
            }
            listener = null;
        }
    }

    private void closeToolbar() {
        if (toolbar != null) {
            toolbar.close();
            toolbar = null;
        }
    }

    // ── Block insertion ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Inserts the blocks one per FX pulse. Each {@link CodeEditor#addStatement} re-parses asynchronously
     * (via {@code Platform.runLater} in the update handler), so we re-resolve the main body and append after
     * each insert rather than caching a body that would go stale.
     */
    private void insertSequentially(List<BlockType> blocks, int index) {
        if (index >= blocks.size()) return;
        BodyBlock body = findMainBody(state);
        if (body == null) {
            warn(owner, "Couldn't find the bot's main method to insert the recorded actions into.");
            return;
        }
        codeEditor.addStatement(body, blocks.get(index), body.getStatements().size());
        Platform.runLater(() -> insertSequentially(blocks, index + 1));
    }

    /** The bot's main-method body (preferred), else the first method body found, else {@code null}. */
    private static BodyBlock findMainBody(ProjectState state) {
        BodyBlock fallback = null;
        for (CodeBlock block : state.getNodeToBlockMap().values()) {
            if (block instanceof MainBlock main) {
                BodyBlock body = bodyOf(main);
                if (body != null) return body;
            } else if (fallback == null && block instanceof MethodDeclarationBlock method) {
                fallback = bodyOf(method);
            }
        }
        return fallback;
    }

    private static BodyBlock bodyOf(MethodDeclarationBlock method) {
        for (CodeBlock child : method.getChildren()) {
            if (child instanceof BodyBlock body) return body;
        }
        return null;
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
