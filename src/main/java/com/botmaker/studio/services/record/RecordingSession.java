package com.botmaker.studio.services.record;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 *       recorded as clicks on the app. {@code exclusion} supplies the overlay's current screen bounds, and
 *       pointer events inside it are dropped as they arrive — see {@link #onEvent}. It is polled from the
 *       native listener thread, so it must not read JavaFX properties directly.</li>
 * </ul>
 */
public final class RecordingSession {

    private final Supplier<Rectangle> exclusion;      // overlay screen bounds to drop events over; may be null
    private final IntConsumer onActionCount;          // called on the native thread with the running count

    private final List<InputEvent> buffer = Collections.synchronizedList(new ArrayList<>());
    private InputListener listener;
    private volatile boolean recording;
    private volatile boolean paused;
    /** Incremented from the native listener thread — an {@code int++} there loses presses under a fast burst. */
    private final AtomicInteger actionCount = new AtomicInteger();

    public RecordingSession(Supplier<Rectangle> exclusion, IntConsumer onActionCount) {
        this.exclusion = exclusion;
        this.onActionCount = onActionCount;
    }

    /** Whether input recording is available on this platform (Linux/X11 only). */
    public static boolean isSupported() {
        return InputListenerFactory.isSupported();
    }

    /**
     * Starts a fresh recording (no-op if already recording). Throws if the listener can't be created/started.
     *
     * <p><b>Arm before starting the listener, not after.</b> The listener delivers on its own native thread the
     * moment it is started, so starting it first left a window in which {@code recording} was still false (the
     * user's first click after pressing Record was dropped) and the previous run's {@code buffer} had not yet
     * been cleared (its leftovers could be appended to). Both are why recording appeared to work only some of
     * the time.
     */
    public void start() {
        if (recording) return;
        buffer.clear();
        actionCount.set(0);
        paused = false;
        recording = true;
        try {
            listener = InputListenerFactory.create();
            listener.start(this::onEvent);
        } catch (RuntimeException | Error e) {
            recording = false;   // never leave the session armed with no listener behind it
            listener = null;
            throw e;
        }
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
        return actionCount.get();
    }

    /**
     * Stops the listener and returns the buffered events.
     *
     * <p>The listener is closed <em>first</em>, so no further event can be appended, and the copy is then taken
     * while holding the buffer's own monitor: {@code Collections.synchronizedList} guards each mutation, not the
     * iteration a copy-constructor performs, so copying it alongside a live native thread could throw
     * {@code ConcurrentModificationException} and lose the whole recording.
     */
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
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /**
     * Called on the native listener thread — keep it cheap; the caller marshals UI updates to the FX thread.
     *
     * <p>The exclusion region is applied <em>here</em> rather than over the finished buffer, because the HUD is
     * draggable: filtering at {@code stop()} tested every event against the overlay's <em>final</em> position,
     * so blocks recorded before a drag were kept and real clicks over where the HUD ended up were thrown away.
     */
    private void onEvent(InputEvent e) {
        if (!recording || paused) return;
        Rectangle ex = exclusion != null ? exclusion.get() : null;
        if (ex != null && insideExclusion(e, ex)) return;
        buffer.add(e);
        if (e instanceof InputEvent.ButtonPress || e instanceof InputEvent.KeyPress) {
            int count = actionCount.incrementAndGet();
            if (onActionCount != null) onActionCount.accept(count);
        }
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
