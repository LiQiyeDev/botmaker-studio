package com.botmaker.studio.project;

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
 * and — the part that would bite an existing project — that a project still carrying a generated
 * {@code BotSettings.java} is migrated without losing what it was tuned to.
 */
class BotSettingsTest {

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainSourceFile().getParent());
        Files.createDirectories(config.resourcesRoot());
        return config;
    }

    private static Path legacyFile(ProjectConfig config) {
        return config.mainSourceFile().getParent().resolve("BotSettings.java");
    }

    @Test
    void everySettingRoundTripsThroughTheProjectFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings written = new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                BotSettings.LinuxInput.UINPUT, false, BotSettings.SessionBackend.GAMESCOPE);

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
        assertEquals(BotSettings.LinuxInput.AUTO, read.linuxInput());
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

    // --- migration off the generated file ---

    /** A generated {@code BotSettings.java} in the shape the previous Studio wrote. */
    private static String generatedFile(String body) {
        return """
            package com.mybot;

            import com.botmaker.sdk.api.vision.ClickConfig;

            public final class BotSettings {

                public static void apply() {
            %s    }

                private BotSettings() {}
            }
            """.formatted(body);
    }

    @Test
    void migrationCarriesEveryTunedValueIntoTheProjectFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(legacyFile(config), generatedFile("""
                    System.setProperty("botmaker.linux.input", "uinput");
                    Session.disable();
                    Session.useBackend("xephyr");
                    ClickConfig.useRealInput(true);
                    ClickConfig.setFoundDelay(750);
                    ClickConfig.setNotFoundDelay(125);
                    ClickConfig.setDefaultConfidence(0.62);
                    ClickConfig.enableRandomClicks(false);
                    ClickConfig.DEFAULT_COMPARE_MARGIN = 0.11;
                    ClickConfig.setMaxRetryAttempts(7);
            """));
        Files.writeString(config.mainSourceFile(), """
            package com.mybot;

            import com.botmaker.sdk.api.bot.Bot;

            public class MyBot {
                public static void main(String[] args) {
                    BotSettings.apply();
                    Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run);
                }
            }
            """);

        String updated = BotSettings.migrate(config);

        assertEquals(new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                        BotSettings.LinuxInput.UINPUT, false, BotSettings.SessionBackend.XEPHYR),
                BotSettings.read(config.resourcesRoot()),
                "everything the project was tuned to has to survive the move");
        assertFalse(Files.exists(legacyFile(config)),
                "the generated file calls a facade that no longer exists — leaving it breaks the build");
        assertNotNull(updated, "the entry point is rewritten, so the caller can refresh its cached copy");
        assertFalse(updated.contains("BotSettings.apply()"), "nothing left to call:\n" + updated);
        assertTrue(updated.contains("Bot.start("), "the rest of main is untouched:\n" + updated);
    }

    @Test
    void migrationLeavesSettingsTheGeneratedFileNeverMentionedAlone(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        // Isolation was always written to the properties file too, and that copy is the one Studio read. A
        // generated file that says nothing about Session must not now reset it to the default.
        ProjectCreator.writeSessionIsolated(config.resourcesRoot(), false);
        Files.writeString(legacyFile(config), generatedFile("        ClickConfig.setFoundDelay(750);\n"));
        Files.writeString(config.mainSourceFile(), "package com.mybot;\npublic class MyBot { }\n");

        BotSettings.migrate(config);

        BotSettings after = BotSettings.read(config.resourcesRoot());
        assertEquals(750, after.foundDelay());
        assertFalse(after.isolatedSession(), "the key the generated file never mentioned has to survive");
    }

    @Test
    void migrationHandlesTheOlderInlineCallInMain(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        // Older still: before the generated file existed, real input was one call in main.
        Files.writeString(config.mainSourceFile(), """
            package com.mybot;

            import com.botmaker.sdk.api.bot.Bot;
            import com.botmaker.sdk.api.vision.ClickConfig;

            public class MyBot {
                public static void main(String[] args) {
                    ClickConfig.useRealInput(true);
                    Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run);
                }
            }
            """);

        String updated = BotSettings.migrate(config);

        assertTrue(BotSettings.read(config.resourcesRoot()).realInput(),
                "the setting the project had must survive the move");
        assertNotNull(updated, updated);
        assertFalse(updated.contains("useRealInput"), "the inline call is removed, not duplicated:\n" + updated);
        assertFalse(updated.contains("import com.botmaker.sdk.api.vision.ClickConfig;"),
                "the now-unused import goes with the call it served:\n" + updated);
    }

    @Test
    void migrationIsANoOpOnAProjectThatHasNeitherForm(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.resourcesRoot(), BotSettings.DEFAULTS.withRealInput(true));
        Files.writeString(config.mainSourceFile(), "package com.mybot;\npublic class MyBot { }\n");

        assertNull(BotSettings.migrate(config), "nothing to migrate — and nothing rewritten");
        assertTrue(BotSettings.read(config.resourcesRoot()).realInput(),
                "an already-migrated project's values are never re-seeded");
    }
}
