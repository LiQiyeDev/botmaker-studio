package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.record.MacroTranslator;
import com.botmaker.studio.services.record.RecordingSession;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.warn;

/**
 * Record mode: the Record / Pause / Stop buttons, the {@link RecordingSession} behind them, the live action
 * count on the HUD's status line, and the translation of a finished session into blocks
 * ({@link MacroTranslator}). What it produces — a flat {@code List<BlockType>} — leaves through
 * {@link Callbacks#onRecorded}; inserting it is {@code RecordedBatchInserter}'s job, not this class's.
 *
 * <p><b>Why it re-probes the window.</b> The origin a recorded click is made relative to is the target window's
 * top-left corner, and that was probed once when the HUD opened and reused at stop. Moving or resizing the game
 * window mid-session — routine, the HUD is deliberately out of the way — offset every recorded coordinate by
 * the delta, with nothing on screen to say so. The bounds are re-read at {@link #start()} instead, and the
 * fresh ones are published back so the header readout describes the size actually being recorded against.
 */
final class OverlayRecorder {

    /**
     * @param status         the HUD's one-line readout
     * @param onWindowBounds fresh target-window bounds, re-probed at record start
     * @param onRecorded     the translated blocks of a finished session, in order
     * @param hasInsertTarget whether there is an editable method to record into
     * @param hudBounds      the HUD's own screen rectangle, so clicks on its buttons aren't recorded
     * @param owner          the HUD stage, to own a failure alert
     */
    record Callbacks(Consumer<String> status,
                     Consumer<Rectangle> onWindowBounds,
                     Consumer<List<BlockType>> onRecorded,
                     BooleanSupplier hasInsertTarget,
                     Supplier<Rectangle> hudBounds,
                     Supplier<Stage> owner) {}

    private final ScreenCaptureService capture;
    private final CaptureTarget target;
    private final Callbacks callbacks;
    private final RecordingSession session;

    private Button recordBtn;
    private Button stopBtn;

    /** The window origin a recording is relative to. Re-probed at {@link #start()} — see the class note. */
    private Rectangle windowBounds;

    /** Set while a coalesced status refresh is already queued, so one FX runnable serves a burst of input. */
    private final AtomicBoolean statusQueued = new AtomicBoolean();

    OverlayRecorder(ScreenCaptureService capture, CaptureTarget target, Rectangle windowBounds,
                    Callbacks callbacks) {
        this.capture = capture;
        this.target = target;
        this.windowBounds = windowBounds;
        this.callbacks = callbacks;
        this.session = new RecordingSession(callbacks.hudBounds(), count -> requestStatusRefresh());
        // The global hotkey is watched on a second XRecord connection, so this session sees its presses too —
        // and would record the very key the user pressed to stop recording. See OverlayHotkey.
        this.session.ignoreKeysym(OverlayHotkey.KEYSYM);
    }

    /** The Record / Stop row. */
    HBox node() {
        recordBtn = new Button("● Record");
        recordBtn.setOnAction(e -> togglePrimary());
        stopBtn = new Button("■ Stop");
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> stopAndInsert());
        refreshAvailability();
        HBox row = new HBox(6, recordBtn, stopBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    boolean isRecording() {
        return session.isRecording();
    }

    /**
     * The global hotkey's action: begin a session, or finish the running one and insert it. Pausing is left to
     * the button — a shortcut pressed from inside the game has to have one unambiguous meaning, and "stop" is
     * the one worth not having to reach for the HUD to reach.
     */
    void toggle() {
        if (session.isRecording()) {
            stopAndInsert();
            return;
        }
        // Say why nothing happened. The button carries the same explanation in a tooltip, but the point of the
        // hotkey is that the user is not looking at the button.
        if (recordBtn != null && recordBtn.isDisable()) {
            Tooltip why = recordBtn.getTooltip();
            callbacks.status().accept(why != null ? why.getText() : "Recording isn't available here.");
            return;
        }
        start();
    }

    /** Drops the native listener without translating anything — the HUD closing mid-session. */
    void abandon() {
        if (session.isRecording()) session.stop();
    }

    /**
     * Enables Record when there is a supported recorder, a window to record against and somewhere to put the
     * blocks — and when there isn't, says which. Re-run whenever the project's activities change, since the
     * last of those three answers is the one that changes while the HUD is open.
     */
    void refreshAvailability() {
        if (recordBtn == null || session.isRecording()) return;
        String blocker;
        if (!RecordingSession.isSupported()) {
            blocker = "Recording is available on Linux (X11) only";
        } else if (!(target instanceof CaptureTarget.WindowTarget)) {
            // Recording translates clicks to window-relative coordinates, so it needs a window target.
            blocker = "Recording targets a window — set a window as the default capture target";
        } else if (!callbacks.hasInsertTarget().getAsBoolean()) {
            // Say so up front. There is nowhere editable to put the blocks, and the failure downstream is silent.
            blocker = "Nowhere to record into — add an activity in Project ▸ Activity Flow first";
        } else {
            blocker = null;
        }
        recordBtn.setDisable(blocker != null);
        recordBtn.setTooltip(new Tooltip(blocker != null ? blocker
                : "Record real clicks/keys and insert them at the cursor — or press "
                        + OverlayHotkey.KEY_NAME + " from inside the game"));
    }

    private void togglePrimary() {
        if (!session.isRecording()) {
            start();
        } else {
            session.setPaused(!session.isPaused());
            recordBtn.setText(session.isPaused() ? "▶ Resume" : "⏸ Pause");
            updateStatus();
        }
    }

    /** Starts a session against freshly probed window bounds. Safe to call when one is already running. */
    void start() {
        if (session.isRecording() || !RecordingSession.isSupported()) return;
        if (target instanceof CaptureTarget.WindowTarget wt) {
            capture.raiseWindow(wt);   // interact with the target window, not whatever was focused
            // Cheap on purpose: a bounds probe, not captureWindow — this runs on the FX thread and the pixels
            // are of no use here, only the origin the coordinates will be relative to.
            Rectangle fresh = ScreenCaptureService.windowBounds(wt);
            if (fresh != null) {
                windowBounds = fresh;
                callbacks.onWindowBounds().accept(fresh);
            }
        }
        try {
            session.start();
        } catch (Exception ex) {
            warn(callbacks.owner().get(), "Couldn't start input recording: " + ex.getMessage());
            return;
        }
        recordBtn.setText("⏸ Pause");
        stopBtn.setDisable(false);
        updateStatus();
    }

    /** Stops recording and hands the translated blocks to the coordinator. */
    private void stopAndInsert() {
        if (!session.isRecording()) return;
        List<InputEvent> events = session.stop();
        recordBtn.setText("● Record");
        stopBtn.setDisable(true);
        refreshAvailability();

        String title = (target instanceof CaptureTarget.WindowTarget wt) ? wt.titleSubstring() : null;
        MacroTranslator.WindowRef ref = new MacroTranslator.WindowRef(
                title, windowBounds.x, windowBounds.y, windowBounds.width, windowBounds.height);
        callbacks.onRecorded().accept(MacroTranslator.translate(events, ref));
    }

    /** While a session runs the status line is the recorder's; stopping leaves whatever the insert reported. */
    private void updateStatus() {
        if (!session.isRecording()) return;
        callbacks.status().accept(
                (session.isPaused() ? "Paused" : "Recording") + " — " + session.actionCount() + " actions");
    }

    /**
     * Queues one status refresh per FX pulse. The recorder reports every press from its native thread, and a
     * {@code Platform.runLater} apiece floods the FX queue during a fast burst — starving the same queue the
     * insert/re-parse handoff runs on. The count is read when the runnable finally executes, so coalescing
     * loses nothing but the intermediate frames.
     */
    private void requestStatusRefresh() {
        if (statusQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                statusQueued.set(false);
                updateStatus();
            });
        }
    }
}
