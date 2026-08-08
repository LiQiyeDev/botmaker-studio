package com.botmaker.studio.services.pilot;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.botmaker.session.Capability;
import com.botmaker.session.PreviewFrame;
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

    /**
     * A frame with pixels in it. Not {@code new BufferedImage(...)}: a fresh {@code TYPE_INT_RGB} is uniformly
     * black, and an all-black session root is exactly what {@link TargetCapture} now reads as "nothing was
     * captured" (see {@link #aSessionShowingAnEmptyX11RootResolvesToNothing}). A default-constructed image
     * silently became a *negative* fixture the day that check landed.
     */
    private static BufferedImage painted(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.DARK_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void anActiveSessionsScreenFrameAndRectAreWhatGetsCaptured() {
        BufferedImage windowFrame = painted(640, 480);
        BufferedImage rootFrame = painted(1280, 800);
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
     * The one-codec-pass path: a session that encoded a frame itself hands the bytes straight through, and
     * nothing on this side decodes them. The frame is therefore <em>not</em> pixels here — {@code cap().img()}
     * is null on purpose — while the rect, which is the half Interact needs, comes back with the bytes.
     */
    @Test
    void aSessionThatEncodesItsOwnFrameIsNotDecodedHere() {
        byte[] encoded = "not really a jpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.previewFrame = new PreviewFrame(encoded, new Rectangle(0, 0, 1280, 800));
        session.screenRect = new Rectangle(0, 0, 1280, 800);
        // No screenFrame at all: if this path decoded or re-grabbed, there would be nothing to resolve.

        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(new PilotRoute.Session(session), null);

        assertNotNull(resolved);
        assertSame(encoded, resolved.bytes(), "the agent's bytes go to the wire untouched");
        assertNull(resolved.cap().img(), "the frame is never decoded on this side");
        assertEquals(1280, resolved.cap().sw());
        assertEquals(800, resolved.cap().sh());
    }

    /**
     * The rect is the encoder's, not the screen's. Under a compositing backend the frame with pixels on it is a
     * <em>window</em>, so the two differ — and this side used to tag the bytes with {@code screen()} regardless,
     * which under gamescope's forced fullscreen happens to be right and on any windowed client silently
     * misplaces every Interact tap by the window's offset.
     */
    @Test
    void theEncodedFramesRectIsTheOneTheEncoderReportsNotTheSessionScreen() {
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.screenRect = new Rectangle(0, 0, 1920, 1080);
        session.previewFrame = new PreviewFrame("jpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new Rectangle(307, 239, 1280, 661));

        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(new PilotRoute.Session(session), null);

        assertNotNull(resolved);
        assertEquals(307, resolved.cap().sx(), "the window's origin, not the screen's");
        assertEquals(239, resolved.cap().sy());
        assertEquals(1280, resolved.cap().sw());
        assertEquals(661, resolved.cap().sh());
    }

    /** An encoder that reports an empty rect has told us nothing about what it sent; fall back and re-grab. */
    @Test
    void anEncodedFrameWithNoRectFallsBackToGrabbingHere() {
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.previewFrame = new PreviewFrame("jpeg".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new Rectangle());
        session.screenFrame = painted(1280, 800);
        session.screenRect = new Rectangle(0, 0, 1280, 800);

        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(new PilotRoute.Session(session), null);

        assertNotNull(resolved);
        assertSame(session.screenFrame, resolved.cap().img(), "the slow path produced this frame");
    }

    /**
     * A session with no encoder of its own still streams: the resolution falls back to grabbing the root here,
     * and the encode happens on demand. This is every non-nested session, and every nested one whose agent
     * refused the verb.
     */
    @Test
    void aSessionWithoutAPreviewEncoderStillYieldsPixelsAndBytes() {
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.screenFrame = painted(1280, 800);
        session.screenRect = new Rectangle(0, 0, 1280, 800);

        TargetCapture.Resolved resolved = new TargetCapture(null).resolve(new PilotRoute.Session(session), null);

        assertNotNull(resolved);
        assertSame(session.screenFrame, resolved.cap().img());
        assertNotNull(resolved.bytes(), "the fallback path must still be able to encode");
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

    /**
     * The Waydroid failure mode, one layer below the route choice: gamescope's embedded Xwayland has nothing
     * mapped on it, so the grab <em>succeeds</em> and hands back a full-size, entirely black root — no
     * exception, no null, nothing to distinguish it from a game on a dark screen. Treating it as no capture is
     * what lets the frame loop say why instead of streaming black forever.
     */
    @Test
    void aSessionShowingAnEmptyX11RootResolvesToNothing() {
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.screenFrame = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB); // untouched = black
        session.screenRect = new Rectangle(0, 0, 1280, 800);

        assertNull(new TargetCapture(null).resolve(new PilotRoute.Session(session), null));
    }

    /** One lit pixel is a frame. The blank check samples a coarse grid, so it must be a pixel the grid visits. */
    @Test
    void aRootWithAnythingOnItIsStillAFrame() {
        BufferedImage root = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        root.setRGB(640, 400, 0x00FF00);
        PilotFakes.FakeSession session = new PilotFakes.FakeSession(
                new PilotFakes.RecordingController(), null, null, EnumSet.noneOf(Capability.class));
        session.screenFrame = root;
        session.screenRect = new Rectangle(0, 0, 1280, 800);

        assertNotNull(new TargetCapture(null).resolve(new PilotRoute.Session(session), null));
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
