package com.botmaker.studio.emulator;

import java.awt.image.BufferedImage;

/**
 * A live emulator screen the editor can watch and touch: one held connection, a frame grab, and the three
 * gestures the remote pilot can produce. The pilot's third route (beside the real {@code :0} desktop and a
 * nested {@code :N} session) is one of these.
 *
 * <p><b>Why an interface, and why not {@link EmulatorProbe}.</b> {@code EmulatorProbe} answers one-shot
 * questions and opens a fresh ADB connection per call, which is right for a picker decorating a row and wrong
 * for a frame loop. This is the held-connection counterpart. It is an interface so the pilot's routing and
 * gesture tests can drive a recording double instead of an emulator — the same reason
 * {@code PilotFakes.FakeSession} exists for a {@code DesktopSession}.
 *
 * <p><b>Coordinates are the emulator's own framebuffer</b>, the same space {@link #grab()} returns and the
 * same one the project's image templates were captured in. Nothing here refers to a host screen, which is
 * exactly why this route is background-safe: no host cursor is moved, so a gesture can never fight the user
 * for their own pointer.
 *
 * <p>Best-effort like the rest of {@code emulator/}: a dropped connection or a refused ADB is a {@code null}
 * frame and a swallowed gesture, never an exception into a frame loop.
 */
public interface EmulatorSurface extends AutoCloseable {

    /** The instance name this surface is bound to — what the capture source / launch target named. */
    String instanceName();

    /** One frame of the emulator's screen, or {@code null} when it can't be grabbed right now. */
    BufferedImage grab();

    /** A single tap at framebuffer coordinates. */
    void tap(int x, int y);

    /** One continuous drag. Android has no pointer to move, so a drag is this single call, never a stream. */
    void drag(int x1, int y1, int x2, int y2, long durationMs);

    /**
     * A scroll centred on {@code (x, y)}. {@code amount} follows the pilot's wire convention — <b>positive
     * scrolls up</b>, negative down (the client sends {@code deltaY > 0 ? -1 : 1}).
     */
    void scroll(int x, int y, int amount);

    /** Releases the held connection. Safe to call twice; never throws. */
    @Override
    void close();
}
