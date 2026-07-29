package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The private-display setting exists in two forms — a {@code Session} statement in the generated source and two
 * project-properties keys — and the SDK ranks the statement <em>above</em> the keys. So what has to hold is not
 * that either write works, but that a single write moves <b>both</b>: a stale statement left behind by a
 * properties-only write would silently beat the checkbox the user just ticked.
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
    void oneWriteMovesBothForms(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.XEPHYR));

        // Form 1: the properties keys Studio's own Launch buttons read.
        assertFalse(ProjectCreator.readSessionIsolated(config.resourcesRoot()));
        assertEquals("xephyr", ProjectCreator.readSessionBackend(config.resourcesRoot()));
        // Form 2: the generated statement that travels with the bot.
        String source = Files.readString(BotSettings.fileFor(config.mainSourceFile()));
        assertTrue(source.contains("Session.disable();"), source);
        assertTrue(source.contains("Session.useBackend(\"xephyr\");"), source);
        // And it round-trips.
        assertEquals(new SessionSetting(false, BotSettings.SessionBackend.XEPHYR),
                SessionSetting.read(config.resourcesRoot()));
    }

    @Test
    void goingBackToTheDefaultRemovesBothTheStatementAndTheKey(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.GAMESCOPE));
        SessionSetting.write(config, SessionSetting.DEFAULT);

        // The backend key is *removed* rather than set to "auto": absent is what the SDK reads as "choose by
        // kind", and it is the state a project that never pinned a backend is in — writing a value for it would
        // make "never chose" and "chose automatic" different bytes.
        assertEquals(null, ProjectCreator.readSessionBackend(config.resourcesRoot()));
        assertTrue(ProjectCreator.readSessionIsolated(config.resourcesRoot()));
        assertFalse(Files.readString(BotSettings.fileFor(config.mainSourceFile())).contains("Session"));
    }

    @Test
    void writingTheSessionPreservesEveryOtherSetting(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings tuned = new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                BotSettings.LinuxInput.UINPUT, true, BotSettings.SessionBackend.AUTO);
        BotSettings.write(config.mainSourceFile(), config.packageName(), tuned);

        SessionSetting.write(config, new SessionSetting(false, BotSettings.SessionBackend.AUTO));

        // The session toggle regenerates the whole file, so the delays/confidence/backend it didn't touch have
        // to survive — it reads the current values back before rewriting.
        BotSettings after = BotSettings.read(BotSettings.fileFor(config.mainSourceFile()));
        assertEquals(tuned.withSession(false, BotSettings.SessionBackend.AUTO), after);
    }
}
