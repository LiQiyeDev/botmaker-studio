package com.botmaker.studio.services.record;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Headless input-recording engine: the reusable core extracted from the (retired) standalone macro recorder.
 * It owns the passive global {@link InputListener} (X11 XRecord — Linux only), buffers observed
 * {@link InputEvent}s while recording, and on {@link #stop()} returns them for translation by
 * {@link MacroTranslator}. It carries no UI and does not decide <em>where</em> the resulting blocks land —
 * the caller (the overlay editor) drives the buttons and inserts the translated blocks at its cursor.
 *
 * <p>Two behaviours matter for the overlay merge:
 * <ul>
 *   <li><b>Action count callback</b> — {@code onActionCount} fires on the native listener thread for each
 *       button/key press so the caller can update a status label (it must marshal to the FX thread itself,
 *       as the old {@code MacroRecorder} did).</li>
 *   <li><b>Exclusion region</b> — because the overlay sits <em>inside</em> the target window (unlike the old
 *       recorder toolbar, which floated above it), clicking the overlay's own Record/Stop buttons would be
 *       recorded as clicks on the app. {@code exclusion} supplies the overlay's current screen bounds; pointer
 *       events inside it are dropped from the returned stream.</li>
 * </ul>
 */
public final class RecordingSession {

    private final Supplier<Rectangle> exclusion;      // overlay screen bounds to drop events over; may be null
    private final IntConsumer onActionCount;          // called on the native thread with the running count

    private final List<InputEvent> buffer = Collections.synchronizedList(new ArrayList<>());
    private InputListener listener;
    private volatile boolean recording;
    private volatile boolean paused;
    private volatile int actionCount;

    public RecordingSession(Supplier<Rectangle> exclusion, IntConsumer onActionCount) {
        this.exclusion = exclusion;
        this.onActionCount = onActionCount;
    }

    /** Whether input recording is available on this platform (Linux/X11 only). */
    public static boolean isSupported() {
        return InputListenerFactory.isSupported();
    }

    /** Starts a fresh recording (no-op if already recording). Throws if the listener can't be created/started. */
    public void start() {
        if (recording) return;
        listener = InputListenerFactory.create();
        listener.start(this::onEvent);
        buffer.clear();
        actionCount = 0;
        recording = true;
        paused = false;
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isRecording() {
        return recording;
    }

    public int actionCount() {
        return actionCount;
    }

    /** Stops the listener and returns the buffered events, minus any that fall inside the exclusion region. */
    public List<InputEvent> stop() {
        recording = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception ignored) {
                // idempotent close; nothing to recover
            }
            listener = null;
        }
        return filterExcluded(new ArrayList<>(buffer));
    }

    /** Called on the native listener thread — keep it cheap; the caller marshals UI updates to the FX thread. */
    private void onEvent(InputEvent e) {
        if (!recording || paused) return;
        buffer.add(e);
        if (e instanceof InputEvent.ButtonPress || e instanceof InputEvent.KeyPress) {
            actionCount++;
            if (onActionCount != null) onActionCount.accept(actionCount);
        }
    }

    private List<InputEvent> filterExcluded(List<InputEvent> events) {
        Rectangle ex = exclusion != null ? exclusion.get() : null;
        if (ex == null) return events;
        List<InputEvent> out = new ArrayList<>(events.size());
        for (InputEvent e : events) {
            if (!insideExclusion(e, ex)) out.add(e);
        }
        return out;
    }

    /** True for pointer events whose absolute coordinates fall inside {@code ex}; key events are never excluded. */
    private static boolean insideExclusion(InputEvent e, Rectangle ex) {
        return switch (e) {
            case InputEvent.ButtonPress b -> ex.contains(b.x(), b.y());
            case InputEvent.ButtonRelease b -> ex.contains(b.x(), b.y());
            case InputEvent.Motion m -> ex.contains(m.x(), m.y());
            case InputEvent.KeyPress ignored -> false;
            case InputEvent.KeyRelease ignored -> false;
        };
    }
}
