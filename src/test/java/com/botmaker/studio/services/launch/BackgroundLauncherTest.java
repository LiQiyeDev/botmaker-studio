package com.botmaker.studio.services.launch;

import com.botmaker.session.impl.NestedSession;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of the shared background launcher's backend shaping and default sizing — the logic that maps
 * Xephyr (2D) vs gamescope (3D) onto {@code NestedSession.Options} and applies the fallback display size. The
 * live bring-up ({@code NestedSession.start} → launch → started listeners) needs a real X server and is verified
 * manually / by the shared live suite.
 */
class BackgroundLauncherTest {

    private static LaunchSpec game() {
        return LaunchSpec.parse("exe:/usr/bin/game");
    }

    @Test
    void xephyrIsThe2DBackendAtTheRequestedSize() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(game(), NestedSession.Backend.XEPHYR, 1600, 900);
        assertEquals(NestedSession.Backend.XEPHYR, o.backend());
        assertEquals(1600, o.width());
        assertEquals(900, o.height());
    }

    @Test
    void gamescopeIsTheOptInHardware3DBackend() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(game(), NestedSession.Backend.GAMESCOPE, 1920, 1080);
        assertEquals(NestedSession.Backend.GAMESCOPE, o.backend());
        assertEquals(1920, o.width());
        assertEquals(1080, o.height());
    }

    @Test
    void nonPositiveSizeFallsBackToTheDefault() {
        NestedSession.Options o = BackgroundLauncher.optionsFor(game(), NestedSession.Backend.XEPHYR, 0, -5);
        assertEquals(BackgroundLauncher.DEFAULT_WIDTH, o.width());
        assertEquals(BackgroundLauncher.DEFAULT_HEIGHT, o.height());
    }

    /**
     * An emulator app brings its own compositor (gamescope is its child, not its display), so the display it
     * runs on must not manage windows: a window manager would frame and resize that compositor's window, which
     * is precisely the scaling the private display exists to avoid.
     */
    @Test
    void anEmulatorAppRunsOnAnUnmanagedDisplay() {
        LaunchSpec waydroid = LaunchSpec.parse("emu-app:com.example.game@Waydroid");
        NestedSession.Options o =
                BackgroundLauncher.optionsFor(waydroid, NestedSession.Backend.XEPHYR, 1080, 1920);
        assertTrue(o.hasExplicitWindowManager() && o.windowManagerCommand().isEmpty(),
                "must be an explicit none — a default openbox would resize gamescope's window");
        assertEquals(1080, o.width());
        assertEquals(1920, o.height());
    }
}
