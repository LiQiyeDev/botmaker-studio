package com.botmaker.studio.emulator;

import com.botmaker.shared.device.ScrcpyDevice;
import com.botmaker.shared.emulator.EmulatorInstance;

import java.awt.image.BufferedImage;

/**
 * An {@link EmulatorSurface} over a live scrcpy session: a continuously-encoded screen and directly-injected
 * input, in place of {@code screencap} per frame and an {@code app_process} per tap.
 *
 * <p><b>It is not a replacement for {@link AdbEmulatorSurface} — it holds one.</b> The fast path can fail to
 * start (no scrcpy installed, a phone that refuses the push, an unreadable handshake) and it can fail
 * <em>later</em> (the session drops, the phone sleeps), and neither is a reason for the pilot to show nothing.
 * So this class is the fast path plus the floor underneath it, and the switch is per call: a frame or a
 * gesture goes to the scrcpy session while one is alive and to ADB otherwise, and a dead session is retried on
 * a timer rather than never or every tick.
 *
 * <p>That per-call switch is also what makes it safe to build the fast path against a protocol this repo has
 * never yet exchanged a byte over: the worst outcome of the transcription being wrong is the performance the
 * previous phase already delivered.
 *
 * <p><b>The coordinate space is the same in both.</b> The stream is native resolution — no {@code max_size},
 * per the pipeline doc's §3 sizing invariant — so a point that was right for a {@code screencap} frame is
 * right for a scrcpy frame, and switching between them mid-session cannot move where a tap lands.
 */
public final class ScrcpyEmulatorSurface implements EmulatorSurface {

    /**
     * How long to wait before trying the fast path again after it failed. Bring-up costs a push and a process
     * start, so retrying every frame on a device that will never accept it would be slower than not trying at
     * all; half a minute makes a phone that was asleep or unplugged recover on its own.
     */
    private static final long RETRY_MS = 30_000;

    private final EmulatorInstance instance;
    private final AdbEmulatorSurface floor;

    /** The live session, or null while the floor is carrying it. Guarded by {@code this}. */
    private ScrcpyDevice fast;
    private long nextAttempt;
    private boolean closed;

    public ScrcpyEmulatorSurface(EmulatorInstance instance) {
        this.instance = instance;
        this.floor = new AdbEmulatorSurface(instance);
    }

    public EmulatorInstance instance() {
        return instance;
    }

    @Override
    public String instanceName() {
        return instance.name();
    }

    /** Whether the fast path is carrying this surface right now — for a status line, not for routing. */
    public synchronized boolean streaming() {
        return fast != null && fast.alive();
    }

    @Override
    public synchronized BufferedImage grab() {
        ScrcpyDevice device = session();
        if (device != null) {
            BufferedImage frame = device.grab();
            // Null here is the ordinary startup case — the session is up but no picture has been decoded yet.
            // Falling through to ADB for those first frames is better than showing nothing.
            if (frame != null) {
                return frame;
            }
        }
        return floor.grab();
    }

    @Override
    public synchronized void tap(int x, int y) {
        ScrcpyDevice device = session();
        if (device != null) {
            device.tap(x, y);
            return;
        }
        floor.tap(x, y);
    }

    @Override
    public synchronized void drag(int x1, int y1, int x2, int y2, long durationMs) {
        ScrcpyDevice device = session();
        if (device != null) {
            device.swipe(x1, y1, x2, y2, durationMs);
            return;
        }
        floor.drag(x1, y1, x2, y2, durationMs);
    }

    @Override
    public synchronized void scroll(int x, int y, int amount) {
        if (amount == 0) {
            return;
        }
        ScrcpyDevice device = session();
        if (device != null) {
            device.scroll(x, y, amount);
            return;
        }
        floor.scroll(x, y, amount);
    }

    /**
     * The live session, starting one if it is time to try. Null means "use the floor".
     *
     * <p>Bring-up happens on the calling thread, which for the pilot is the frame thread: it costs a push and
     * a process start, once, and doing it on a helper thread would only mean the first frames go to the floor
     * while a second caller starts a second session.
     */
    private ScrcpyDevice session() {
        if (closed) {
            return null;
        }
        if (fast != null && !fast.alive()) {
            drop();
        }
        if (fast == null && System.currentTimeMillis() >= nextAttempt) {
            nextAttempt = System.currentTimeMillis() + RETRY_MS;
            fast = start();
        }
        return fast;
    }

    /**
     * Opens a session, or null. It gets its <b>own</b> ADB connection rather than sharing the floor's: the
     * floor drops and reopens its connection on any failure, and a scrcpy session cannot survive having its
     * transport swapped underneath it. {@code connect} ties the two lifetimes together so a failed attempt
     * cannot leak one — which matters here, where attempts repeat on a timer.
     */
    private ScrcpyDevice start() {
        try {
            return ScrcpyDevice.connect(instance.adb());
        } catch (Throwable t) {
            return null;
        }
    }

    private void drop() {
        if (fast != null) {
            try {
                fast.close();
            } catch (Throwable ignored) {
                // best-effort teardown
            }
            fast = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        drop();
        floor.close();
    }
}
