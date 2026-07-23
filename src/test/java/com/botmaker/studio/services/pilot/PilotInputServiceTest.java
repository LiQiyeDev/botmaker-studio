package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.session.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 routing: with a nested session active, {@link PilotInputService} drives that session's {@code :N}
 * controller (never escalates it, and honestly reports background-safe); with none, it falls back to the host
 * {@code :0} path. Uses the {@link PilotFakes} doubles so no real display is touched.
 */
class PilotInputServiceTest {

    private static final PilotInputService.Bounds BOUNDS = new PilotInputService.Bounds(0, 0, 800, 600);

    @AfterEach
    void resetFactory() {
        NativeControllerFactory.setForTesting(null); // don't leak an injected host controller into other tests
    }

    @Test
    void gesturesRouteToTheActiveSessionController() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotInputService input = new PilotInputService(session);

        assertTrue(input.apply(PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        // TAP goes through clickRestoringCursor → move, press, release (cursorPosition() is null so no restore).
        assertEquals(3, nc.calls.size());
        assertEquals("move 100,120", nc.calls.get(0));
        assertEquals("button 1 true", nc.calls.get(1));
        assertEquals("button 1 false", nc.calls.get(2));
        // A nested :N controller must never be escalated — it is already device-level and background-safe.
        assertFalse(nc.reliableCalled, "the :N controller must not be asked to useReliableInput()");
    }

    @Test
    void backgroundInputIsTrueForANestedSessionRegardlessOfControllerFlag() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.background = false; // even a controller that reports false...
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotInputService input = new PilotInputService(session);

        // ...is background-safe because the session advertises BACKGROUND_CLICK (its :N pointer is the bot's alone).
        assertTrue(input.supportsBackgroundInput());
    }

    @Test
    void clearingTheSessionFallsBackToTheHostPath() {
        PilotFakes.RecordingController sessionNc = new PilotFakes.RecordingController();
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc); // the :0 controller the fallback path resolves
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(sessionNc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotInputService input = new PilotInputService(session);

        // While set, the session controller takes the gesture — the host is untouched.
        assertTrue(input.apply(PilotInputService.Kind.MOVE, 10, 20, 1, 0, BOUNDS));
        assertEquals("move 10,20", sessionNc.calls.get(0));
        assertTrue(hostNc.calls.isEmpty());

        // After clearing, the gesture goes to the host :0 controller, not the (now inactive) session one.
        session.clear();
        int sessionSeen = sessionNc.calls.size();
        assertTrue(input.apply(PilotInputService.Kind.MOVE, 30, 40, 1, 0, BOUNDS));
        assertEquals(sessionSeen, sessionNc.calls.size(), "a cleared session's controller must not receive gestures");
        assertEquals("move 30,40", hostNc.calls.get(0), "the host :0 controller now takes the gesture");
        assertTrue(hostNc.reliableCalled, "the host path escalates via useReliableInput() on first use");
    }

    @Test
    void outOfBoundsGesturesAreStillRejectedWithASession() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotInputService input = new PilotInputService(session);

        assertFalse(input.apply(PilotInputService.Kind.TAP, 5000, 5000, 1, 0, BOUNDS));
        assertTrue(nc.calls.isEmpty(), "a gesture outside the shown surface must never reach the controller");
    }
}
