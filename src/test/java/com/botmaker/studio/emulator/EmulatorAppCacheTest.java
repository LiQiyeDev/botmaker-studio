package com.botmaker.studio.emulator;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emulator app list on disk. It exists because the picker's in-process map promised a stopped instance
 * would still show its last-known apps and only delivered that within one run of Studio.
 */
class EmulatorAppCacheTest {

    private static final EmulatorInstance WAYDROID =
            new EmulatorInstance(PlatformId.WAYDROID, "Waydroid", "192.168.240.112", 5555);

    @Test
    void aPackageListSurvivesARoundTrip(@TempDir Path dir) {
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        cache.putPackages(WAYDROID, List.of("com.supercell.clashofclans", "com.example.app"));

        assertEquals(List.of("com.supercell.clashofclans", "com.example.app"),
                new EmulatorAppCache(dir).packages(WAYDROID));
    }

    @Test
    void anInstanceWithNothingCachedReadsEmptyRatherThanFailing(@TempDir Path dir) {
        assertEquals(List.of(), new EmulatorAppCache(dir).packages(WAYDROID));
    }

    @Test
    void anIdentityWithColonsAndDotsBecomesAUsableFileName(@TempDir Path dir) {
        // The key is `waydroid@192.168.240.112:5555` — a colon is not a legal file name character on Windows,
        // and the cache must not be the thing that breaks there.
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        cache.putPackages(WAYDROID, List.of("com.example.app"));

        assertTrue(dir.toFile().listFiles().length > 0, "expected a cache file to be written");
        assertEquals(List.of("com.example.app"), cache.packages(WAYDROID));
    }

    @Test
    void iconsRoundTripAndAnAbsentOneIsNull(@TempDir Path dir) {
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        assertNull(cache.icon(WAYDROID, "com.example.app"));

        cache.putIcon(WAYDROID, "com.example.app", new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB));
        BufferedImage read = new EmulatorAppCache(dir).icon(WAYDROID, "com.example.app");

        assertNotNull(read);
        assertEquals(8, read.getWidth());
    }

    @Test
    void writingNothingIsANoOpRatherThanAClearedCache(@TempDir Path dir) {
        // A live query that failed comes back empty, and must not erase what we knew before it.
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        cache.putPackages(WAYDROID, List.of("com.example.app"));
        cache.putPackages(WAYDROID, List.of());
        cache.putIcon(WAYDROID, "com.example.app", null);

        assertEquals(List.of("com.example.app"), cache.packages(WAYDROID));
    }
}
