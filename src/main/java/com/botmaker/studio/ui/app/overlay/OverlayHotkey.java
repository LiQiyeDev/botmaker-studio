package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;
import javafx.application.Platform;

/**
 * The overlay's <b>global</b> record hotkey: one passive key watcher, live for as long as the HUD is open, that
 * fires no matter which window has focus.
 *
 * <p>It has to be global. Recording exists to capture what you do <em>in the game</em>, so the game is what has
 * keyboard focus while a session runs — a JavaFX accelerator on the HUD's own scene would only ever fire when
 * the user had already clicked away from the thing they are recording, and clicking the HUD's Record button
 * costs the same focus. Pressing {@value #KEY_NAME} without leaving the game is the whole point.
 *
 * <p>It is the same X11 XRecord machinery the recorder itself uses, on its own connection
 * ({@code InputListenerFactory} hands out a fresh listener per call, each owning its display connections), so
 * the hotkey watcher and a running recording coexist. The one thing that must not happen is the hotkey landing
 * <em>in</em> the recording — that is why {@code RecordingSession.ignoreKeysym} exists and why this class
 * publishes {@link #KEYSYM}: the key that stops a recording would otherwise be its final recorded action.
 *
 * <p>Off Linux/X11 {@link #start()} is a silent no-op, exactly as recording itself is.
 */
final class OverlayHotkey implements AutoCloseable {

    /** X keysym {@code XK_F9}. Chosen because no game binds it as often as F1–F5 and no desktop grabs it. */
    static final long KEYSYM = 0xFFC6L;

    /** How the hotkey is written in tooltips and status lines. */
    static final String KEY_NAME = "F9";

    private final Runnable onPressed;
    private InputListener listener;

    OverlayHotkey(Runnable onPressed) {
        this.onPressed = onPressed;
    }

    /** Whether a global hotkey can be watched on this platform at all. */
    static boolean isSupported() {
        return InputListenerFactory.isSupported();
    }

    /**
     * Begins watching. Failure is deliberately swallowed: the hotkey is an accelerator for a button that is
     * right there on the HUD, so a machine that won't give us a second XRecord client should lose the shortcut,
     * not the overlay.
     */
    void start() {
        if (listener != null || !isSupported()) return;
        try {
            InputListener l = InputListenerFactory.create();
            l.start(this::onEvent);
            listener = l;
        } catch (RuntimeException | Error e) {
            listener = null;
        }
    }

    /** Called on the native listener thread — hand the work straight to FX and return. */
    private void onEvent(InputEvent e) {
        if (e instanceof InputEvent.KeyPress k && k.keysym() == KEYSYM) {
            Platform.runLater(onPressed);
        }
    }

    @Override
    public void close() {
        if (listener == null) return;
        try {
            listener.close();
        } catch (Exception ignored) {
            // idempotent close; nothing to recover
        }
        listener = null;
    }
}
