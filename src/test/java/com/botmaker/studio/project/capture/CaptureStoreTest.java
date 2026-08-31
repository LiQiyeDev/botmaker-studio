package com.botmaker.studio.project.capture;

import com.botmaker.sdk.authoring.CaptureModel;
import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.studio.project.StudioProjectSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the editor is still entitled to say about {@code capture.json} since the targets manager became the SDK
 * plugin's: it reads the model through {@link com.botmaker.sdk.authoring.Authoring}, and the only component it
 * writes is the reference resolution — merged into whatever the plugin has since put in that file, never
 * written over it.
 *
 * <p>The migration off the old {@code settings.json} shape is asserted on the SDK's side now, where the reader
 * lives; what is checked here is that the editor sees its result.
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
    }

    @Test
    void anExistingCaptureFileWinsOverWhatSettingsUsedToHold(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), LEGACY_SETTINGS);
        Files.writeString(dir.resolve(CaptureModel.FILE_NAME), "{ \"targets\": [], \"defaultIndex\": null }");

        StudioProjectSettings read = StudioProjectSettings.read(dir);

        assertNull(read.defaultTarget());
    }

    /**
     * The one that would have gone wrong quietly: the editor holds a target list it read at open, the plugin's
     * manager writes a new one while it is open, and then anything at all is changed in the editor's own
     * settings. Writing {@code captureModel()} whole would put the stale list back.
     */
    @Test
    void writingSettingsLeavesTheTargetsThePluginWroteAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(StudioProjectSettings.FILE_NAME), LEGACY_SETTINGS);
        StudioProjectSettings openedEarlier = StudioProjectSettings.read(dir);

        // The plugin's manager writes a different list while the editor holds the old one.
        Files.writeString(dir.resolve(CaptureModel.FILE_NAME),
                "{ \"targets\": [{ \"spec\": \"window:RuneLite\" }], \"defaultIndex\": 0 }");

        openedEarlier.withTemplate(null).write(dir);

        assertEquals(CaptureTargetModel.window("RuneLite"),
                StudioProjectSettings.read(dir).defaultTarget());
    }

    /**
     * The capture resolution moved into {@code capture.json} on 2026-08-31, a day after the targets did, so it
     * needs its own migration: a project written in that one-day window has {@code capture.json} with the
     * targets and {@code settings.json} with the size.
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
}
