package com.botmaker.studio.project;

import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The private-display setting is a pair of project keys with two editing surfaces (this one and the Launch
 * Target dialog's "Run in background" toggle), so what has to hold is that a write moves both keys together and
 * leaves the project's other settings alone.
 *
 * <p>It used to exist in a second form as well — a {@code Session} statement in the generated
 * {@code BotSettings.java}, which the SDK ranked <em>above</em> these keys, so a properties-only write could be
 * silently beaten by a stale statement. That form is gone; {@link BotSettingsTest} covers migrating a project
 * that still has one.
 */
class SessionSettingTest {

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainSourceFile().getParent());
        return config;
    }

    @Test
    void defaultsWhenTheProjectHasNeverSaidAnything(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        assertEquals(SessionSetting.DEFAULT, SessionSetting.read(config.resourcesRoot()));
    }

    @Test
    void aWriteRoundTripsThroughBothKeys(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.XEPHYR));

        assertFalse(ProjectCreator.readSessionIsolated(config.resourcesRoot()));
        assertEquals("xephyr", ProjectCreator.readSessionBackend(config.resourcesRoot()));
        assertEquals(new SessionSetting(false, BotSettings.SessionBackend.XEPHYR),
                SessionSetting.read(config.resourcesRoot()));
    }

    @Test
    void goingBackToTheAutomaticBackendRemovesTheKey(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.GAMESCOPE));
        SessionSetting.write(config, SessionSetting.DEFAULT);

        // The backend key is *removed* rather than set to "auto": absent is what the SDK reads as "choose by
        // kind", and it is the state a project that never pinned a backend is in — writing a value for it would
        // make "never chose" and "chose automatic" different bytes.
        assertEquals(null, ProjectCreator.readSessionBackend(config.resourcesRoot()));
        assertTrue(ProjectCreator.readSessionIsolated(config.resourcesRoot()));
    }

    @Test
    void writingTheSessionPreservesEveryOtherSetting(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings tuned = new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                LinuxInputBackendId.UINPUT, true, BotSettings.SessionBackend.AUTO);
        BotSettings.write(config.resourcesRoot(), tuned);

        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.AUTO));

        // Both settings live in one file, so the session write must touch its two keys and no others.
        assertEquals(tuned.withSession(false, BotSettings.SessionBackend.AUTO),
                BotSettings.read(config.resourcesRoot()));
    }
}
