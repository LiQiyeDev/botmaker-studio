package com.botmaker.studio.project.capture;

import com.botmaker.sdk.authoring.CaptureModel;
import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.StudioProjectSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture targets now live in the SDK's {@code capture.json}, and this holds the three things that had to
 * stay true across that move: a project written by an older Studio still opens with its pickers configured,
 * the targets survive a round trip through the new file, and the one spec a running bot reads follows the
 * default target instead of drifting from it.
 */
class CaptureStoreTest {

    private static final String LEGACY_SETTINGS = """
            {
              "schemaVersion": 1,
              "captureTargets": [
                { "type": "window", "titleSubstring": "Diablo IV" },
                { "type": "emulator", "instanceName": "MuMu Player 12" }
              ],
              "defaultTargetIndex": 1,
              "knownWindowTitles": ["Diablo IV"]
            }
            """;

    @Test
    void anOlderProjectsTargetsAreReadOutOfSettingsUntilTheCaptureFileExists(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), LEGACY_SETTINGS);

        StudioProjectSettings read = StudioProjectSettings.read(dir);

        assertEquals(2, read.captureTargets().size());
        assertEquals(CaptureTargetModel.window("Diablo IV"), read.captureTargets().get(0));
        assertEquals(CaptureTargetModel.emulator("MuMu Player 12"), read.defaultTarget());
        assertEquals(List.of("Diablo IV"), read.knownWindowTitles());
    }

    @Test
    void writingMovesThemAcrossAndStopsKeepingACopyInSettings(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), LEGACY_SETTINGS);

        StudioProjectSettings.read(dir).write(dir);

        assertTrue(Files.exists(dir.resolve(CaptureModel.FILE_NAME)));
        assertFalse(Files.readString(dir.resolve(StudioProjectSettings.FILE_NAME)).contains("captureTargets"));

        StudioProjectSettings reread = StudioProjectSettings.read(dir);
        assertEquals(2, reread.captureTargets().size());
        assertEquals(CaptureTargetModel.emulator("MuMu Player 12"), reread.defaultTarget());
    }

    @Test
    void anExistingCaptureFileWinsOverWhatSettingsUsedToHold(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), LEGACY_SETTINGS);
        Files.writeString(dir.resolve(CaptureModel.FILE_NAME), "{ \"targets\": [], \"defaultIndex\": null }");

        StudioProjectSettings read = StudioProjectSettings.read(dir);

        assertTrue(read.captureTargets().isEmpty());
        assertNull(read.defaultTarget());
    }

    /**
     * The capture resolution moved into {@code capture.json} on 2026-08-31, a day after the targets did, so
     * it needs its own migration and its own test: a project written in that one-day window has
     * {@code capture.json} with the targets and {@code settings.json} with the size. The targets' rule — "once
     * the file exists it is the answer" — would have thrown the size away.
     */
    @Test
    void theCaptureSizeMigratesEvenWhenTheCaptureFileAlreadyExists(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), """
                {
                  "schemaVersion": 1,
                  "referenceResolution": { "width": 1600, "height": 900 }
                }
                """);
        Files.writeString(dir.resolve(CaptureModel.FILE_NAME),
                "{ \"targets\": [{ \"spec\": \"desktop\" }], \"defaultIndex\": 0 }");

        StudioProjectSettings read = StudioProjectSettings.read(dir);
        assertEquals(new CaptureModel.Resolution(1600, 900), read.referenceResolution());

        // And the next write moves it across, after which settings.json no longer carries it at all.
        read.write(dir);
        assertFalse(Files.readString(dir.resolve(StudioProjectSettings.FILE_NAME))
                .contains("referenceResolution"));
        assertEquals(new CaptureModel.Resolution(1600, 900),
                StudioProjectSettings.read(dir).referenceResolution());
    }

    /** Once {@code capture.json} names a size it wins, or changing it would be undone by the old file. */
    @Test
    void aCaptureSizeInTheCaptureFileWinsOverTheLegacyOne(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), """
                {
                  "schemaVersion": 1,
                  "referenceResolution": { "width": 1600, "height": 900 }
                }
                """);
        Files.writeString(dir.resolve(CaptureModel.FILE_NAME),
                "{ \"targets\": [], \"defaultIndex\": null, \"reference\": { \"width\": 1280, \"height\": 720 } }");

        assertEquals(new CaptureModel.Resolution(1280, 720),
                StudioProjectSettings.read(dir).referenceResolution());
    }

    @Test
    void theDefaultTargetIsProjectedOntoTheSpecARunningBotReads(@TempDir Path dir) throws IOException {
        new StudioProjectSettings(List.of(CaptureTargetModel.desktop(),
                CaptureTargetModel.window("Diablo IV")), 1).write(dir);

        assertEquals("window:Diablo IV", captureSource(dir));
    }

    @Test
    void aProjectWithNoDefaultLeavesThatSpecAlone(@TempDir Path dir) throws IOException {
        Properties existing = new Properties();
        existing.setProperty(ProjectProperties.KEY_CAPTURE_SOURCE, "emulator:Waydroid");
        try (var out = Files.newOutputStream(dir.resolve(ProjectProperties.FILE_NAME))) {
            existing.store(out, null);
        }

        new StudioProjectSettings(List.of(), null).write(dir);

        assertEquals("emulator:Waydroid", captureSource(dir));
    }

    private static String captureSource(Path dir) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(dir.resolve(ProjectProperties.FILE_NAME))) {
            properties.load(in);
        }
        return properties.getProperty(ProjectProperties.KEY_CAPTURE_SOURCE);
    }
}
