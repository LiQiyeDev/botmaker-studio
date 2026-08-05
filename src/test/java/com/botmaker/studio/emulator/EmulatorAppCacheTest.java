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

    private static EmulatorProbe.InstalledApp app(String pkg, String label) {
        return new EmulatorProbe.InstalledApp(pkg, label);
    }

    @Test
    void aPackageListSurvivesARoundTripWithItsLabels(@TempDir Path dir) {
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        cache.putPackages(WAYDROID, List.of(
                app("com.HolydayStudios.Firestone", "Firestone"),
                app("com.example.app", null)));

        List<EmulatorProbe.InstalledApp> read = new EmulatorAppCache(dir).packages(WAYDROID);
        assertEquals(List.of(app("com.HolydayStudios.Firestone", "Firestone"), app("com.example.app", null)),
                read);
        // The one without a label still displays as something: the package is the fallback.
        assertEquals("com.example.app", read.get(1).display());
    }

    /** A cache written before labels existed is one package per line — it must still read. */
    @Test
    void theOlderLabelLessFormatStillReads(@TempDir Path dir) throws Exception {
        EmulatorAppCache cache = new EmulatorAppCache(dir);
        cache.putPackages(WAYDROID, List.of(app("com.example.app", "Example")));
        java.nio.file.Path file = java.util.Arrays.stream(dir.toFile().listFiles())
                .filter(java.io.File::isFile).findFirst().orElseThrow().toPath();
        java.nio.file.Files.writeString(file, "com.example.app\ncom.other.app\n");

        assertEquals(List.of(app("com.example.app", null), app("com.other.app", null)),
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
        cache.putPackages(WAYDROID, List.of(app("com.example.app", null)));

        assertTrue(dir.toFile().listFiles().length > 0, "expected a cache file to be written");
        assertEquals(List.of(app("com.example.app", null)), cache.packages(WAYDROID));
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
        cache.putPackages(WAYDROID, List.of(app("com.example.app", "Example")));
        cache.putPackages(WAYDROID, List.of());
        cache.putIcon(WAYDROID, "com.example.app", null);

        assertEquals(List.of(app("com.example.app", "Example")), cache.packages(WAYDROID));
    }
}
