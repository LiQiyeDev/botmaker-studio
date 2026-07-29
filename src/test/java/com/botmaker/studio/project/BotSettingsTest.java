package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code BotSettings.java} is the storage format for the bot's runtime tuning, so what matters is
 * that every value written can be read back, that a file missing statements degrades to defaults rather than
 * failing, and that a project created before the file existed is migrated without losing its one setting.
 */
class BotSettingsTest {

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainSourceFile().getParent());
        return config;
    }

    @Test
    void everySettingRoundTripsThroughTheGeneratedFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings written = new BotSettings(true, 750, 125, 0.62, false, 0.11, 7,
                BotSettings.LinuxInput.UINPUT, false, BotSettings.SessionBackend.GAMESCOPE);

        BotSettings.write(config.mainSourceFile(), config.packageName(), written);

        assertEquals(written, BotSettings.read(BotSettings.fileFor(config.mainSourceFile())));
    }

    @Test
    void theAutomaticBackendWritesNoPropertyAtAll(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.mainSourceFile(), config.packageName(), BotSettings.DEFAULTS);

        String source = Files.readString(BotSettings.fileFor(config.mainSourceFile()));
        // "auto" *is* what the SDK does with no property set, so writing it would add a statement that says
        // nothing — and the value must still read back as AUTO.
        assertFalse(source.contains("botmaker.linux.input"), source);
        assertEquals(BotSettings.LinuxInput.AUTO,
                BotSettings.read(BotSettings.fileFor(config.mainSourceFile())).linuxInput());
    }

    @Test
    void aDefaultProjectMentionsNoSessionAtAll(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.mainSourceFile(), config.packageName(), BotSettings.DEFAULTS);

        String source = Files.readString(BotSettings.fileFor(config.mainSourceFile()));
        // Isolation on with an automatic backend *is* the SDK's own default, so the generated file carries no
        // Session statement and — just as important — no unused Session import. This is what keeps regenerating
        // an existing default project byte-identical.
        assertFalse(source.contains("Session"), source);
        BotSettings read = BotSettings.read(BotSettings.fileFor(config.mainSourceFile()));
        assertTrue(read.isolatedSession());
        assertEquals(BotSettings.SessionBackend.AUTO, read.sessionBackend());
    }

    @Test
    void optingOutEmitsExactlyTheDisableCallAndItsImport(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.mainSourceFile(), config.packageName(),
                BotSettings.DEFAULTS.withSession(false, BotSettings.SessionBackend.XEPHYR));

        String source = Files.readString(BotSettings.fileFor(config.mainSourceFile()));
        assertTrue(source.contains("import com.botmaker.sdk.api.Session;"), source);
        assertTrue(source.contains("Session.disable();"), source);
        assertTrue(source.contains("Session.useBackend(\"xephyr\");"), source);
    }

    @Test
    void aHandWrittenSessionCallInAnySpellingReadsBack(@TempDir Path root) throws IOException {
        // All three spellings are real API, so a user hand-editing this file may reach for any of them.
        record Case(String statement, boolean isolated) {}
        for (Case c : new Case[]{new Case("Session.disable();", false), new Case("Session.enable();", true),
                new Case("Session.set(false);", false), new Case("Session.set(true);", true)}) {
            Path file = root.resolve(c.statement().hashCode() + BotSettings.FILE_NAME);
            Files.writeString(file, """
                public final class BotSettings {
                    public static void apply() {
                        %s
                    }
                }
                """.formatted(c.statement()));
            assertEquals(c.isolated(), BotSettings.read(file).isolatedSession(), c.statement());
        }
    }

    @Test
    void aFileMissingStatementsReadsBackAsDefaults(@TempDir Path root) throws IOException {
        Path file = root.resolve(BotSettings.FILE_NAME);
        // Only one call left — an older Studio's file, or a user who deleted the lines they didn't want.
        Files.writeString(file, """
            package com.mybot;
            import com.botmaker.sdk.api.vision.ClickConfig;
            public final class BotSettings {
                public static void apply() {
                    ClickConfig.useRealInput(true);
                }
            }
            """);

        BotSettings read = BotSettings.read(file);
        assertTrue(read.realInput());
        assertEquals(BotSettings.DEFAULTS.foundDelay(), read.foundDelay());
        assertEquals(BotSettings.DEFAULTS.confidence(), read.confidence());
        assertEquals(BotSettings.DEFAULTS.maxRetryAttempts(), read.maxRetryAttempts());
    }

    @Test
    void aMissingFileIsAllDefaults(@TempDir Path root) {
        assertEquals(BotSettings.DEFAULTS, BotSettings.read(root.resolve("nope/BotSettings.java")));
    }

    @Test
    void migrationCarriesTheInlineRealInputCallIntoTheNewFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
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

        assertNotNull(updated, "the entry point is rewritten, so the caller can refresh its cached copy");
        assertTrue(updated.contains("BotSettings.apply();"), updated);
        assertFalse(updated.contains("useRealInput"), "the inline call is replaced, not duplicated:\n" + updated);
        assertFalse(updated.contains("import com.botmaker.sdk.api.vision.ClickConfig;"),
                "the now-unused import goes with the call it served:\n" + updated);
        assertTrue(BotSettings.read(BotSettings.fileFor(config.mainSourceFile())).realInput(),
                "the setting the project had must survive the move");
    }

    @Test
    void migrationIsANoOpOnceTheFileExists(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        BotSettings.write(config.mainSourceFile(), config.packageName(), BotSettings.DEFAULTS.withRealInput(true));
        Files.writeString(config.mainSourceFile(), "package com.mybot;\npublic class MyBot { }\n");

        assertEquals(null, BotSettings.migrate(config), "nothing to migrate — and nothing rewritten");
        assertTrue(BotSettings.read(BotSettings.fileFor(config.mainSourceFile())).realInput(),
                "an existing file's values are never re-seeded from the entry point");
    }

    @Test
    void aBotWithNoInlineCallStillGetsApplyAtTheTopOfMain(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(config.mainSourceFile(), """
            package com.mybot;
            import com.botmaker.sdk.api.BotMaker;

            public class MyBot {
                public static void main(String[] args) {
                    BotMaker.print("Hello!");
                }
            }
            """);

        String updated = BotSettings.migrate(config);

        assertNotNull(updated, updated);
        assertTrue(updated.indexOf("BotSettings.apply();") < updated.indexOf("BotMaker.print"),
                "apply() must run before anything it configures:\n" + updated);
    }
}
