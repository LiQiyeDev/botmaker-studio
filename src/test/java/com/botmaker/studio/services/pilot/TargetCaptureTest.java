package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.session.Capability;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Route capture: the {@link PilotRoute} the frame loop resolved is the surface {@link TargetCapture} grabs —
 * a nested session's {@code :N} screen or an emulator's framebuffer — each tagged with the rect that makes
 * capture and Interact share one coordinate space, and each reported back beside its own frame so the two can
 * never disagree.
 */
class TargetCaptureTest {

    @Test
    void anActiveSessionsScreenFrameAndRectAreWhatGetsCaptured() {
        BufferedImage windowFrame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        BufferedImage rootFrame = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        GenericWindow win = new GenericWindow(1, "Nested Game", new Rectangle(0, 0, 640, 480));
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.windowFrame = windowFrame;

        PilotFakes.FakeSession session =
                new PilotFakes.FakeSession(nc, win, windowFrame, EnumSet.of(Capability.BACKGROUND_CLICK));
        session.screenFrame = rootFrame;
        session.screenRect = new Rectangle(0, 0, 1280, 800);
        PilotRoute route = new PilotRoute.Session(session);

        // lastTarget is null (idle on :0) — the session must still win.
        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(route, null);

        assertNotNull(resolved, "an active session always yields a frame");
        assertSame(route, resolved.route(), "the frame is reported on the route it was taken from");
        TargetCapture.Capture cap = resolved.cap();
        assertSame(rootFrame, cap.img(), "the :N root is streamed, not the attached window");
        assertEquals(0, cap.sx());
        assertEquals(0, cap.sy());
        assertEquals(1280, cap.sw());
        assertEquals(800, cap.sh());
    }

    /**
     * The cursor-teleport fix, at the resolution level: a session that cannot produce a frame resolves to
     * nothing. Falling through to the {@code :0} desktop published host multi-monitor bounds under a route that
     * still claimed to be the session, so a tap was replayed through the {@code :N} controller at host
     * coordinates — and it streamed the user's desktop over a link they opened to watch a bot.
     */
    @Test
    void aSessionThatCannotBeGrabbedResolvesToNothingRatherThanTheDesktop() {
        PilotRoute route = new PilotRoute.Session(new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class)));

        assertNull(new TargetCapture(null).resolve(route, null),
                "a failed session grab must never fall back to the user's desktop");
    }

    /** The same refusal for the emulator route — an unreachable emulator is not consent to stream {@code :0}. */
    @Test
    void anEmulatorThatCannotBeGrabbedResolvesToNothingRatherThanTheDesktop() {
        PilotFakes.RecordingSurface surface = new PilotFakes.RecordingSurface(null);

        assertNull(new TargetCapture(null).resolve(new PilotRoute.Emulator(surface), null));
    }

    /**
     * gamescope renames its output window after the app it hosts, so on {@code :0} the best title match for the
     * game <em>is</em> the session's own container. Streaming it looks right and is a trap: Interact would then
     * fire real device input into the container and the host desktop stops responding. It is refused by id.
     */
    @Test
    void aSessionsOwnHostWindowIsNeverStreamedAsADesktopWindowTarget() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.windowFrame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        nc.windows.add(new GenericWindow(4242L, "Firestone", new Rectangle(0, 0, 640, 480)));
        NativeControllerFactory.setForTesting(nc);
        try {
            TargetCapture capture = new TargetCapture(null, () -> 4242L);

            capture.resolve(PilotRoute.DESKTOP, new TelemetryEvent.Target("Firestone", 0, 0, 640, 480));

            assertFalse(nc.calls.contains("capture"),
                    "the session's own container must never be grabbed as a :0 window target");
        } finally {
            NativeControllerFactory.setForTesting(null);
        }
    }

    /** The same window with no session live is an ordinary target — the guard keys on the id, not the title. */
    @Test
    void anOrdinaryWindowTargetIsStillCaptured() {
        PilotFakes.RecordingController nc = new PilotFakes.RecordingController();
        nc.windowFrame = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        nc.windows.add(new GenericWindow(4242L, "Firestone", new Rectangle(0, 0, 640, 480)));
        NativeControllerFactory.setForTesting(nc);
        try {
            TargetCapture.Resolved resolved = new TargetCapture(null)
                    .resolve(PilotRoute.DESKTOP, new TelemetryEvent.Target("Firestone", 0, 0, 640, 480));

            assertNotNull(resolved);
            assertSame(nc.windowFrame, resolved.cap().img());
        } finally {
            NativeControllerFactory.setForTesting(null);
        }
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

        PilotRoute route = new PilotRoute.Emulator(surface);
        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(route, null);

        assertNotNull(resolved);
        assertSame(route, resolved.route());
        TargetCapture.Capture cap = resolved.cap();
        assertSame(frame, cap.img());
        assertEquals(0, cap.sx());
        assertEquals(0, cap.sy());
        assertEquals(1280, cap.sw());
        assertEquals(720, cap.sh());
        assertEquals(java.util.List.of("grab"), surface.calls, "exactly one grab per frame");
    }
}
