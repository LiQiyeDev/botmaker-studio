package com.botmaker.studio.services.launch;

import com.botmaker.shared.session.NestedSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure coverage of the shared background launcher's backend shaping and default sizing — the logic that maps
 * Xephyr (2D) vs gamescope (3D) onto {@code NestedSession.Options} and applies the fallback display size. The
 * live bring-up ({@code NestedSession.start} → launch → started listeners) needs a real X server and is verified
 * manually / by the shared live suite.
 */
class BackgroundLauncherTest {

    @Test
    void xephyrIsThe2DBackendAtTheRequestedSize() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(NestedSession.Backend.XEPHYR, 1600, 900);
        assertEquals(NestedSession.Backend.XEPHYR, o.backend());
        assertEquals(1600, o.width());
        assertEquals(900, o.height());
    }

    @Test
    void gamescopeIsTheOptInHardware3DBackend() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(NestedSession.Backend.GAMESCOPE, 1920, 1080);
        assertEquals(NestedSession.Backend.GAMESCOPE, o.backend());
        assertEquals(1920, o.width());
        assertEquals(1080, o.height());
    }

    @Test
    void nonPositiveSizeFallsBackToTheDefault() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(NestedSession.Backend.XEPHYR, 0, -5);
        assertEquals(BackgroundLauncher.DEFAULT_WIDTH, o.width());
        assertEquals(BackgroundLauncher.DEFAULT_HEIGHT, o.height());
    }
}
