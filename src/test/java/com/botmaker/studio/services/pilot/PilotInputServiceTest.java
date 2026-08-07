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
 * Route replay: a gesture is applied to the surface of the {@link PilotRoute} it arrived with — a nested
 * session's {@code :N} controller, the host {@code :0} one, or an emulator's ADB verbs — using the
 * {@link PilotFakes} doubles so no real display and no emulator is touched.
 */
class PilotInputServiceTest {

    private static final PilotInputService.Bounds BOUNDS = new PilotInputService.Bounds(0, 0, 800, 600);

    @AfterEach
    void resetFactory() {
        NativeControllerFactory.setForTesting(null); // don't leak an injected host controller into other tests
    }

    /** A session route, ready to hand to {@code apply}. */
    private static PilotRoute sessionRoute(PilotFakes.RecordingController nc) {
        return new PilotRoute.Session(
                new PilotFakes.FakeSession(nc, null, null, EnumSet.of(Capability.BACKGROUND_CLICK)));
    }

    @Test
    void gesturesRouteToTheActiveSessionController() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(sessionRoute(nc), PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        // TAP on a session goes through click() → move, press, release, with no warp back.
        assertEquals(3, nc.calls.size());
        assertEquals("move 100,120", nc.calls.get(0));
        assertEquals("button 1 true", nc.calls.get(1));
        assertEquals("button 1 false", nc.calls.get(2));
    }

    /**
     * The session tap must leave the pointer on the target. Warping it back is what leaves a game rendering a
     * hover highlight instead of registering the click — and on {@code :N} there is no user cursor to return.
     */
    @Test
    void aSessionTapDoesNotWarpThePointerBack() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.cursor = new java.awt.Point(7, 9); // a readable origin: the restoring path would warp here

        assertTrue(new PilotInputService()
                .apply(sessionRoute(nc), PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

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
        PilotRoute route = sessionRoute(nc);
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(route, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(route, PilotInputService.Kind.MOVE, 40, 40, 1, 0, BOUNDS));
        assertTrue(input.apply(route, PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 10,10", "button 1 true", "move 40,40", "move 50,50",
                "button 1 false"), nc.calls);
    }

    /** And on {@code :0} the drag hands the cursor back, since there it is the user's. */
    @Test
    void aHostDragPutsTheUsersCursorBack() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals("move 7,9", hostNc.calls.get(hostNc.calls.size() - 1), hostNc.calls.toString());
    }

    /** The mirror image on {@code :0}, where borrowing the user's cursor silently is the whole point. */
    @Test
    void aHostTapPutsTheUsersCursorBack() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);

        assertTrue(new PilotInputService()
                .apply(PilotRoute.DESKTOP, PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 100,120", "button 1 true", "button 1 false", "move 7,9"),
                hostNc.calls);
    }

    @Test
    void backgroundInputIsTrueForANestedSessionRegardlessOfControllerFlag() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.background = false; // even a controller that reports false...

        // ...is background-safe because the session advertises BACKGROUND_CLICK (its :N pointer is the bot's alone).
        assertTrue(new PilotInputService().supportsBackgroundInput(sessionRoute(nc)));
    }

    @Test
    void theRouteTheFrameCameWithDecidesWhoGetsTheGesture() {
        PilotFakes.RecordingController sessionNc = new PilotFakes.RecordingController();
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc); // the :0 controller the desktop route resolves
        PilotInputService input = new PilotInputService();

        // On the session route the session controller takes the gesture — the host is untouched.
        assertTrue(input.apply(sessionRoute(sessionNc), PilotInputService.Kind.MOVE, 10, 20, 1, 0, BOUNDS));
        assertEquals("move 10,20", sessionNc.calls.get(0));
        assertTrue(hostNc.calls.isEmpty());

        // On the desktop route it goes to the host :0 controller, not the (no longer live) session one.
        int sessionSeen = sessionNc.calls.size();
        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.MOVE, 30, 40, 1, 0, BOUNDS));
        assertEquals(sessionSeen, sessionNc.calls.size(), "a route not in play must not receive gestures");
        assertEquals("move 30,40", hostNc.calls.get(0), "the host :0 controller now takes the gesture");
        assertTrue(hostNc.reliableCalled, "the host path escalates via useReliableInput() on first use");
    }

    @Test
    void outOfBoundsGesturesAreStillRejectedWithASession() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        PilotInputService input = new PilotInputService();

        assertFalse(input.apply(sessionRoute(nc), PilotInputService.Kind.TAP, 5000, 5000, 1, 0, BOUNDS));
        assertTrue(nc.calls.isEmpty(), "a gesture outside the shown surface must never reach the controller");
    }

    // --- A held button always comes back up: the "I can't click anything until BotMaker is shut down" bug ---

    /**
     * The drag that wedged the desktop. Releasing past the edge of the streamed frame used to be dropped like
     * any other out-of-bounds gesture, leaving the button down — an implicit X pointer grab on whatever window
     * got the press, so every later click anywhere went there. The UP is clamped into the frame instead.
     */
    @Test
    void aDragReleasedOutsideTheFrameStillReleasesTheButton() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc);
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.UP, 9000, 9000, 1, 0, BOUNDS),
                "an UP that ends a drag is clamped, never dropped");

        assertEquals(java.util.List.of("move 10,10", "button 1 true", "move 799,599", "button 1 false"),
                hostNc.calls, "the release lands on the last pixel of the frame, not off it");
    }

    /** A press outside the frame is still refused outright — the clamp is for finishing a drag, not starting one. */
    @Test
    void aPressOutsideTheFrameIsStillRejected() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc);

        assertFalse(new PilotInputService()
                .apply(PilotRoute.DESKTOP, PilotInputService.Kind.DOWN, 9000, 9000, 1, 0, BOUNDS));
        assertTrue(hostNc.calls.isEmpty());
    }

    /** The phone that vanishes mid-drag: whoever notices calls this, and the host gets its pointer back. */
    @Test
    void releaseHeldLetsGoOfAButtonNoUpEverReleased() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        input.releaseHeld();

        assertEquals(java.util.List.of("move 10,10", "button 1 true", "button 1 false", "move 7,9"),
                hostNc.calls);

        // Idempotent: a second call (close() after onClose, say) must not press or release anything again.
        input.releaseHeld();
        input.close();
        assertEquals(4, hostNc.calls.size());
    }

    /** Nothing held, nothing to do — the common case for every exit path that calls it blind. */
    @Test
    void releaseHeldWithNoDragTouchesNothing() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc);

        new PilotInputService().releaseHeld();

        assertTrue(hostNc.calls.isEmpty());
        assertFalse(hostNc.reliableCalled, "a no-op release must not escalate :0 to device input");
    }

    /**
     * A session coming up (or going away) under a drag: the button is released on the surface that actually has
     * it, before anything reaches the new one. Releasing on "the current route" would strand it on the old.
     */
    @Test
    void aRouteChangeMidDragReleasesOnTheRouteThatHoldsTheButton() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        hostNc.cursor = new java.awt.Point(7, 9);
        NativeControllerFactory.setForTesting(hostNc);
        PilotFakes.RecordingController sessionNc = new PilotFakes.RecordingController();
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(PilotRoute.DESKTOP, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(sessionRoute(sessionNc), PilotInputService.Kind.MOVE, 40, 40, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("move 10,10", "button 1 true", "button 1 false", "move 7,9"),
                hostNc.calls, "the :0 button is released before the session takes over");
        assertEquals(java.util.List.of("move 40,40"), sessionNc.calls);
    }

    // --- The emulator route: Android has no pointer, so the gesture shapes differ ---

    @Test
    void anEmulatorTapIsOneAdbTapAndNoControllerIsEverResolved() {
        PilotFakes.RecordingController hostNc = new PilotFakes.RecordingController();
        NativeControllerFactory.setForTesting(hostNc);
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);

        assertTrue(new PilotInputService().apply(new PilotRoute.Emulator(surface),
                PilotInputService.Kind.TAP, 100, 120, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("tap 100,120"), surface.calls);
        // The host controller must not even be constructed: resolving it escalates :0 to real device input,
        // process-wide and stickily, which an emulator gesture has no business causing.
        assertTrue(hostNc.calls.isEmpty());
        assertFalse(hostNc.reliableCalled);
    }

    /**
     * A drag is <b>one</b> {@code input swipe} carrying both ends, so the intermediate moves dispatch nothing.
     * Sending a swipe per MOVE would be a stutter of unrelated flicks rather than a drag.
     */
    @Test
    void anEmulatorDragIsASingleSwipeFromTheDownPoint() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);
        PilotRoute route = new PilotRoute.Emulator(surface);
        PilotInputService input = new PilotInputService();

        assertTrue(input.apply(route, PilotInputService.Kind.DOWN, 10, 10, 1, 0, BOUNDS));
        assertTrue(input.apply(route, PilotInputService.Kind.MOVE, 40, 40, 1, 0, BOUNDS));
        assertTrue(input.apply(route, PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("drag 10,10->50,50"), surface.calls);
    }

    /** An UP with no remembered DOWN (a client reconnecting mid-gesture) taps rather than swiping from nowhere. */
    @Test
    void anEmulatorUpWithNoDownDegradesToATap() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);

        assertTrue(new PilotInputService().apply(new PilotRoute.Emulator(surface),
                PilotInputService.Kind.UP, 50, 50, 1, 0, BOUNDS));

        assertEquals(java.util.List.of("tap 50,50"), surface.calls);
    }

    @Test
    void anEmulatorScrollIsForwardedWithItsSignedAmount() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);

        assertTrue(new PilotInputService().apply(new PilotRoute.Emulator(surface),
                PilotInputService.Kind.SCROLL, 100, 120, 1, -3, BOUNDS));

        assertEquals(java.util.List.of("scroll 100,120 -3"), surface.calls);
    }

    /** ADB has no host cursor to move, so this route is background-safe without any capability to advertise. */
    @Test
    void anEmulatorRouteIsAlwaysBackgroundSafe() {
        assertTrue(new PilotInputService()
                .supportsBackgroundInput(new PilotRoute.Emulator(new PilotFakes.RecordingSurface(null))));
    }

    @Test
    void outOfBoundsGesturesAreRejectedOnTheEmulatorRouteToo() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);

        assertFalse(new PilotInputService().apply(new PilotRoute.Emulator(surface),
                PilotInputService.Kind.TAP, 5000, 5000, 1, 0, BOUNDS));

        assertTrue(surface.calls.isEmpty(), "the clamp guards every route, not just the display-backed ones");
    }
}
