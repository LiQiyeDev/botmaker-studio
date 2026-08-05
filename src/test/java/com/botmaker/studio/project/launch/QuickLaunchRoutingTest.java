package com.botmaker.studio.project.launch;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which launches go through a private nested display. The case that matters is the emulator app: background
 * mode is on by default, and routing it through the background path meant the Launch button's only ever
 * outcome for an {@code emu-app:} target was an isolation refusal — the app never started.
 */
class QuickLaunchRoutingTest {

    private static LaunchSpec spec(LaunchKind kind, String token) {
        return new LaunchSpec(kind, token);
    }

    @Test
    void anEmulatorAppSkipsTheBackgroundSessionEvenWithIsolationOn() {
        assertFalse(QuickLaunch.usesBackgroundSession(spec(LaunchKind.EMULATOR_APP, "com.app@Pie64"), true));
    }

    @Test
    void everyOnDesktopKindStillUsesItWhenIsolationIsOn() {
        for (LaunchKind kind : LaunchKind.values()) {
            if (kind.runsOffDesktop()) {
                continue;
            }
            assertTrue(QuickLaunch.usesBackgroundSession(spec(kind, "token"), true), kind.name());
        }
    }

    @Test
    void isolationOffMeansTheDesktopPathForEveryKind() {
        for (LaunchKind kind : LaunchKind.values()) {
            assertFalse(QuickLaunch.usesBackgroundSession(spec(kind, "token"), false), kind.name());
        }
    }

    /** No target is not a target that isolates — the button is disabled there, but the predicate is total. */
    @Test
    void aNullSpecNeverRoutesToABackgroundSession() {
        assertFalse(QuickLaunch.usesBackgroundSession(null, true));
    }
}
