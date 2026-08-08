package com.botmaker.studio.services.pilot;

import com.botmaker.session.Capability;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.studio.services.pilot.PilotFakes.FakeSession;
import com.botmaker.studio.services.pilot.PilotFakes.RecordingController;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PilotVideo}'s two answers to "what is being encoded, and is it still there".
 *
 * <p>Both matter because the H.264 path has no picture to inspect. A JPEG frame that is wrong is visibly
 * wrong; a stream aimed at the wrong drawable is a healthy encoder producing black, and a stream whose rect is
 * wrong is a correct picture whose taps land somewhere else entirely.
 */
class PilotVideoTest {

    private static FakeSession session(Rectangle screen) {
        FakeSession s = new FakeSession(new RecordingController(),
                new GenericWindow(1L, "Game", screen), null, Set.of(Capability.BACKGROUND_CLICK));
        s.screenRect = screen;
        return s;
    }

    /**
     * The gamescope shape: the session is 1920×1080 but the drawable with pixels on it is a window inset
     * inside that. The client fits its canvas to this rect and maps every tap through it.
     */
    @Test
    void theRectIsTheStreamsSurfaceNotTheSessionScreen() {
        FakeSession s = session(new Rectangle(0, 0, 1920, 1080));
        s.videoSurface = new Rectangle(307, 239, 1280, 661);

        try (PilotVideo video = new PilotVideo(1280, 24)) {
            video.follow(s, packet -> { });

            assertEquals(new Rectangle(307, 239, 1280, 661), video.rect());
            assertNotEquals(s.screen(), video.rect(), "assuming the screen would misplace taps by the offset");
        }
    }

    /** A session with nothing painted has no drawable to encode, and must not be handed one that is black. */
    @Test
    void aSessionWithNothingPaintedIsNotStreamed() {
        FakeSession s = session(new Rectangle(0, 0, 1920, 1080));
        s.videoSurface = null;

        try (PilotVideo video = new PilotVideo(1280, 24)) {
            assertFalse(video.follow(s, packet -> { }));
            assertFalse(video.live());
        }
    }

    /**
     * A decline is temporary — a game that has not mapped its window yet maps it a moment later — but it must
     * not become a per-tick retry in the meantime. Ten ticks inside the retry window cost one attempt.
     */
    @Test
    void aDeclineIsRetriedButNotEveryTick() {
        FakeSession s = session(new Rectangle(0, 0, 1920, 1080));
        s.videoSurface = null;

        try (PilotVideo video = new PilotVideo(1280, 24)) {
            for (int tick = 0; tick < 10; tick++) {
                video.follow(s, packet -> { });
            }

            assertEquals(1, s.videoOpens, "the frame loop runs at 24 fps — this cannot be one ffmpeg per tick");
        }
    }

    /**
     * The case the root grab never had: a launcher chain swaps its own window for the game's, so the surface
     * moves under a stream that was aimed at one drawable and cannot be re-aimed. The stream ends and reopens
     * on the new one, carrying the new rect — otherwise the client keeps fitting to a window that is gone.
     */
    @Test
    void aSurfaceThatMovesEndsTheStreamAndReopensOnTheNewOne() {
        FakeSession s = session(new Rectangle(0, 0, 1920, 1080));
        s.videoSurface = new Rectangle(0, 0, 640, 480);

        try (PilotVideo video = new PilotVideo(1280, 24)) {
            video.follow(s, packet -> { });
            assertEquals(1, s.videoOpens);

            s.videoSurface = new Rectangle(0, 0, 1920, 1080);   // the game replaced the launcher's window
            video.follow(s, packet -> { });

            assertEquals(2, s.videoOpens, "an encoder holding a destroyed drawable cannot be told to move");
            assertEquals(new Rectangle(0, 0, 1920, 1080), video.rect());
        }
    }

    /** A surface that has not moved must not restart the stream — the check runs on every one of 24 ticks. */
    @Test
    void anUnchangedSurfaceKeepsTheStreamRunning() {
        FakeSession s = session(new Rectangle(0, 0, 1920, 1080));
        s.videoSurface = new Rectangle(307, 239, 1280, 661);

        try (PilotVideo video = new PilotVideo(1280, 24)) {
            for (int tick = 0; tick < 10; tick++) {
                assertTrue(video.follow(s, packet -> { }) || tick == 0, "tick " + tick);
            }

            assertEquals(1, s.videoOpens);
        }
    }
}
