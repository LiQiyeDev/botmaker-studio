package com.botmaker.studio.project;

import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
import com.botmaker.shared.config.ProjectProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code botmaker-project.properties} is the storage format for the bot's runtime tuning, so what matters is
 * that every value written can be read back, that an absent key degrades to its default rather than failing,
 * and that an absent key degrades to its default rather than failing.
 */
class BotSettingsTest {

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainSourceFile().getParent());
        Files.createDirectories(config.resourcesRoot());
        return config;
    }

    @Test
    void everySettingRoundTripsThroughTheProjectFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings written = new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                LinuxInputBackendId.UINPUT, false, BotSettings.SessionBackend.GAMESCOPE);

        BotSettings.write(config.resourcesRoot(), written);

        assertEquals(written, BotSettings.read(config.resourcesRoot()));
    }

    @Test
    void theAutomaticChoicesWriteNoKeyAtAll(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.resourcesRoot(), BotSettings.DEFAULTS);

        // "auto" *is* what the SDK does with no key set, so writing it would store a value that says nothing —
        // and both must still read back as AUTO.
        java.util.Properties props = ProjectCreator.readProjectProperties(config.resourcesRoot());
        assertNull(props.getProperty(ProjectProperties.KEY_INPUT_LINUX_BACKEND));
        assertNull(props.getProperty(ProjectProperties.KEY_SESSION_BACKEND));
        BotSettings read = BotSettings.read(config.resourcesRoot());
        assertEquals(LinuxInputBackendId.AUTO, read.linuxInput());
        assertEquals(BotSettings.SessionBackend.AUTO, read.sessionBackend());
    }

    @Test
    void writingSettingsPreservesTheProjectsOtherKeys(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        ProjectCreator.writeLaunchTarget(config.resourcesRoot(), "steam:12345");

        BotSettings.write(config.resourcesRoot(), BotSettings.GAME_DEFAULTS);

        assertEquals("steam:12345", ProjectCreator.readLaunchTarget(config.resourcesRoot()));
    }

    @Test
    void anAbsentKeyReadsBackAsItsDefault(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        // One key only — an older Studio's file, or a hand edit that removed the rest.
        ProjectCreator.writeProjectKeys(config.resourcesRoot(),
                java.util.Map.of(ProjectProperties.KEY_INPUT_REAL, "true"));

        BotSettings read = BotSettings.read(config.resourcesRoot());
        assertTrue(read.realInput());
        assertEquals(BotSettings.DEFAULTS.foundDelay(), read.foundDelay());
        assertEquals(BotSettings.DEFAULTS.confidence(), read.confidence());
        assertEquals(BotSettings.DEFAULTS.maxRetryAttempts(), read.maxRetryAttempts());
    }

    @Test
    void aMissingFileIsAllDefaults(@TempDir Path root) {
        assertEquals(BotSettings.DEFAULTS, BotSettings.read(root.resolve("nope")));
    }

    // The four migration tests stood here until 2026-09-02, with generatedFile() and legacyFile() to
    // stage the fixture: a generated BotSettings.java full of ClickConfig.* and Session.* calls that
    // BotSettings.migrate read back with regexes. They go with that method — matching one plugin's facade
    // calls in the user's own source is not something the editor can own, because the editor does not know
    // what a ClickConfig is.
}
