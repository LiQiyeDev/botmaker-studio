package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.session.Capability;
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

        // TAP on a session goes through click() → move, press, release, with no warp back.
        assertEquals(3, nc.calls.size());
        assertEquals("move 100,120", nc.calls.get(0));
        assertEquals("button 1 true", nc.calls.get(1));
        assertEquals("button 1 false", nc.calls.get(2));
        // A nested :N controller must never be escalated — it is already device-level and background-safe.
        assertFalse(nc.reliableCalled, "the :N controller must not be asked to useReliableInput()");
    }

    /**
     * The session tap must leave the pointer on the target. Warping it back is what leaves a game rendering a
     * hover highlight instead of registering the click — and on {@code :N} there is no user cursor to return.
     */
    @Test
    void aSessionTapDoesNotWarpThePointerBack() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.cursor = new java.awt.Point(7, 9); // a readable origin: the restoring path would warp here
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));

        assertTrue(new PilotInputService(session).apply(PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 100,120", "button 1 true", "button 1 false"), nc.calls);
    }

    /**
     * The same rule at the end of a drag, which the {@code UP} branch used to break: it restored {@code dragOrigin}
     * unconditionally, so a drag in a session ended with the pointer warped off the target — the tap's bug one
     * gesture over. On {@code :0} the restore must still happen (asserted below).
     */
    @Test
    void aSessionDragEndsWhereItEnded() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.cursor = new java.awt.Point(7, 9);
        PilotSession session = new PilotSession();
        session.set(new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
        PilotInputService input = new PilotInputService(session);

        assertTrue(input.apply(PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(PilotInputService.Kind.MOVE, 40, 40, 1, 0, BOUNDS));
        assertTrue(input.apply(PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 10,10", "button 1 true", "move 40,40", "move 50,50",
                "button 1 false"), nc.calls);
    }

    /** And on {@code :0} the drag hands the cursor back, since there it is the user's. */
    @Test
    void aHostDragPutsTheUsersCursorBack() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);
        PilotInputService input = new PilotInputService(new PilotSession());

        assertTrue(input.apply(PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals("move 7,9", hostNc.calls.get(hostNc.calls.size() - 1), hostNc.calls.toString());
    }

    /** The mirror image on {@code :0}, where borrowing the user's cursor silently is the whole point. */
    @Test
    void aHostTapPutsTheUsersCursorBack() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);

        assertTrue(new PilotInputService(new PilotSession())
                .apply(PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 100,120", "button 1 true", "button 1 false", "move 7,9"),
                hostNc.calls);
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
