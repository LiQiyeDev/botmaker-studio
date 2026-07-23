package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.session.Capability;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Phase 5 routing: an active nested session makes {@link TargetCapture} preview that session's {@code :N}
 * window — its own frame, tagged with the window's {@code :N} rect so capture and Interact share one
 * coordinate space — in preference to any {@code :0} telemetry/default target.
 */
class TargetCaptureTest {

    @Test
    void anActiveSessionsFrameAndRectAreWhatGetsCaptured() {
        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        GenericWindow win = new GenericWindow(1, "Nested Game", new Rectangle(0, 0, 640, 480));
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.windowFrame = frame;

        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, win, frame, EnumSet.of(Capability.BACKGROUND_CLICK)));
        TargetCapture capture = new TargetCapture(null, session);

        // lastTarget is null (idle on :0) — the session must still win.
        TargetCapture.Capture cap = capture.resolve(null);

        assertNotNull(cap, "an active session with an attached window always yields a frame");
        assertSame(frame, cap.img(), "the session's own :N frame is streamed");
        assertEquals(0, cap.sx());
        assertEquals(0, cap.sy());
        assertEquals(640, cap.sw());
        assertEquals(480, cap.sh());
    }
}
