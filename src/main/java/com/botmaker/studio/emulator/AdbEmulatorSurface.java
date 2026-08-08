package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;

import java.awt.image.BufferedImage;

/**
 * An {@link EmulatorSurface} over ADB: {@code exec:screencap -p} for frames and {@code input tap|swipe} for
 * gestures, against <b>one</b> connection held for the surface's lifetime.
 *
 * <p>The held connection is the whole point. {@link EmulatorProbe} opens and closes a connection per call,
 * which is correct for a picker asking one question and would pay a full ADB handshake per frame here. The
 * cost of holding one is that it can go stale — the emulator stops, the container freezes, the trust prompt
 * is revoked — so every failure closes and forgets the device and the next call reconnects. That makes a
 * stopped-and-restarted emulator recover on its own rather than needing the pilot restarted.
 *
 * <p><b>Frame rate is the transport's, not ours.</b> A grab is a full-frame PNG decoded on this thread, which
 * is far slower than the pilot's nominal 12 fps; the frame loop runs at a fixed <em>delay</em> so this simply
 * yields the rate ADB can sustain instead of building a backlog.
 *
 * <p>Synchronized because the pilot's frame thread grabs while its WebSocket thread taps, and one {@code Dadb}
 * connection is not two.
 */
public final class AdbEmulatorSurface implements EmulatorSurface {

    /** Pixels per unit of scroll — one wheel notch is a swipe of twice this, centred on the pointer. */
    private static final int SCROLL_STEP = 120;

    /** How long a scroll's swipe takes. Long enough that Android reads it as a scroll, not a fling. */
    private static final long SCROLL_MS = 200;

    private final EmulatorInstance instance;

    /** The held connection, opened on first use and dropped on any failure. Guarded by {@code this}. */
    private AdbDevice device;

    public AdbEmulatorSurface(EmulatorInstance instance) {
        this.instance = instance;
    }

    /** The instance this surface talks to — for a status line, and for the route's staleness check. */
    public EmulatorInstance instance() {
        return instance;
    }

    @Override
    public String instanceName() {
        return instance.name();
    }

    @Override
    public synchronized BufferedImage grab() {
        AdbDevice d = device();
        if (d == null) return null;
        try {
            return d.screencap();
        } catch (Throwable t) {
            drop();
            return null;
        }
    }

    @Override
    public synchronized void tap(int x, int y) {
        run(d -> d.tap(x, y));
    }

    @Override
    public synchronized void drag(int x1, int y1, int x2, int y2, long durationMs) {
        run(d -> d.swipe(x1, y1, x2, y2, durationMs));
    }

    @Override
    public synchronized void scroll(int x, int y, int amount) {
        if (amount == 0) return;
        // Positive scrolls up, which on a touch screen means the finger travels *down* the surface: it drags
        // the content down and so reveals what was above. Negative is the mirror image.
        int travel = amount * SCROLL_STEP;
        run(d -> d.swipe(x, y - travel, x, y + travel, SCROLL_MS));
    }

    @Override
    public synchronized void close() {
        drop();
    }

    /** The connection, opening it if needed; {@code null} when the emulator can't be reached. */
    private AdbDevice device() {
        if (device != null && !device.isConnected()) drop();
        if (device == null) {
            try {
                device = AdbDevice.connect(instance.adb());
            } catch (Throwable t) {
                device = null;
            }
        }
        return device;
    }

    /** Runs one gesture, forgetting the connection if it fails so the next call reconnects. */
    private void run(Gesture gesture) {
        AdbDevice d = device();
        if (d == null) return;
        try {
            gesture.on(d);
        } catch (Throwable t) {
            drop();
        }
    }

    private void drop() {
        if (device != null) {
            try {
                device.close();
            } catch (Throwable ignored) {
                // Closing a connection that already failed is expected to fail too.
            }
            device = null;
        }
    }

    /** One gesture expressed against a connected device; may throw, which {@link #run} turns into a reconnect. */
    @FunctionalInterface
    private interface Gesture {
        void on(AdbDevice device);
    }
}
