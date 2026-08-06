package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The persisted key of a bottom tab, and the total parse the saved layout is restored through. */
class BottomTabTest {

    @Test
    void everyTabRoundTripsThroughItsKey() {
        for (BottomTab tab : BottomTab.values()) {
            assertEquals(tab, BottomTab.named(tab.key()));
        }
    }

    /** A layout written by a newer Studio, or hand-edited: the window opens on its default tab. */
    @Test
    void anUnknownOrAbsentKeyNamesNoTab() {
        assertNull(BottomTab.named("PROFILER"));
        assertNull(BottomTab.named(""));
        assertNull(BottomTab.named(null));
    }
}
