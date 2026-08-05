package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.session.Capability;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Route capture: the {@link PilotRoute} the frame loop resolved is the surface {@link TargetCapture} grabs —
 * a nested session's {@code :N} window or an emulator's framebuffer — each tagged with the rect that makes
 * capture and Interact share one coordinate space, in preference to any {@code :0} telemetry/default target.
 */
class TargetCaptureTest {

    @Test
    void anActiveSessionsFrameAndRectAreWhatGetsCaptured() {
        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        GenericWindow win = new GenericWindow(1, "Nested Game", new Rectangle(0, 0, 640, 480));
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.windowFrame = frame;

        PilotRoute route = new PilotRoute.Session(
                new PilotFakes.FakeSession(nc, win, frame, EnumSet.of(Capability.BACKGROUND_CLICK)));

        // lastTarget is null (idle on :0) — the session must still win.
        TargetCapture.Capture cap = new TargetCapture(null).resolve(route, null);

        assertNotNull(cap, "an active session with an attached window always yields a frame");
        assertSame(frame, cap.img(), "the session's own :N frame is streamed");
        assertEquals(0, cap.sx());
        assertEquals(0, cap.sy());
        assertEquals(640, cap.sw());
        assertEquals(480, cap.sh());
    }

    /**
     * An emulator's pixels are on no host screen, so the frame is tagged at its own framebuffer origin. That
     * rect is what Interact is clamped to and what {@code input tap} expects — and it is the space the
     * project's templates were captured in, so the phone sees exactly what the bot matches.
     */
    @Test
    void anEmulatorFrameIsTaggedWithItsOwnFramebufferRect() {
        BufferedImage frame = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(frame);

        TargetCapture.Capture cap = new TargetCapture(null).resolve(new PilotRoute.Emulator(surface), null);

        assertNotNull(cap);
        assertSame(frame, cap.img());
        assertEquals(0, cap.sx());
        assertEquals(0, cap.sy());
        assertEquals(1280, cap.sw());
        assertEquals(720, cap.sh());
        assertEquals(java.util.List.of("grab"), surface.calls, "exactly one grab per frame");
    }
}
