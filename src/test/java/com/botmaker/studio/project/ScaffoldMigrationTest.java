package com.botmaker.studio.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migrations that bring an older project onto the current scaffold — off {@code GameLoop.java} /
 * {@code Startup.java}, and onto the popup guard. Both failure directions are silent, which is why they are
 * pinned here: not migrating leaves a project calling a 3-arg {@code Bot.start} the SDK no longer has (it stops
 * compiling), and over-migrating deletes a file, or rewrites an entry point, in a project that was never ours.
 */
class ScaffoldMigrationTest {

    @TempDir
    Path projectsRoot;

    private ProjectConfig config;
    private Path mainDir;

    @BeforeEach
    void setUp() throws IOException {
        config = ProjectConfig.forProject("MyBot", projectsRoot);
        mainDir = config.mainSourceFile().getParent();
        Files.createDirectories(mainDir);
    }

    /** A game-bot project exactly as the previous generation of the scaffold wrote it. */
    private void writeLegacyProject() throws IOException {
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;

                import com.botmaker.sdk.api.bot.Bot;

                public class MyBot {
                    public static void main(String[] args) {
                        // Runs GameLoop forever; on a crash or a stuck screen it runs GoHome then Startup and restarts.
                        Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run);
                    }
                }
                """);
        Files.writeString(mainDir.resolve("GameLoop.java"), """
                package com.mybot;
                public class GameLoop {
                    public static void run() {
                        FlowDriver.run();
                    }
                }
                """);
        Files.writeString(mainDir.resolve("Startup.java"), """
                package com.mybot;
                import com.botmaker.sdk.api.bot.StartMode;
                import com.botmaker.sdk.api.launch.Target;
                public class Startup {
                    public static void run(StartMode mode) {
                        switch (mode) {
                            case COLD -> Target.startIfNotRunning();
                            case RESTART -> Target.restart();
                        }
                    }
                }
                """);
    }

    @Test
    void aLegacyProjectIsRewiredOntoTheTwoArgStartAndLosesBothFiles() throws IOException {
        writeLegacyProject();

        String migrated = ScaffoldMigration.migrate(config);

        assertTrue(migrated.contains("Bot.start(FlowDriver::run, GoHome.INSTANCE::execute)"), migrated);
        assertFalse(Files.exists(mainDir.resolve("GameLoop.java")));
        assertFalse(Files.exists(mainDir.resolve("Startup.java")));
        // The comment above the call named both files. Left behind it would document a shape the project no
        // longer has — and, because a delete refuses to remove a type the entry point still names, a stale
        // comment alone would have been enough to keep Startup.java on disk forever.
        assertFalse(migrated.contains("GameLoop"), migrated);
        assertFalse(migrated.contains("Startup"), migrated);
        assertEquals(migrated, Files.readString(config.mainSourceFile()), "the rewrite must be on disk too");
    }

    @Test
    void theUsersOwnRecoveryHookIsCarriedOverRatherThanReWritten() throws IOException {
        // The middle argument is theirs — an older project binds GoHome::run, not GoHome.INSTANCE::execute —
        // so it is captured and put back, not assumed.
        String rewritten = ScaffoldMigration.rewriteEntryPoint(
                "        Bot.supervise(GameLoop::run, GoHome::run, Startup::run);");
        assertEquals("        Bot.start(FlowDriver::run, GoHome::run);", rewritten);
    }

    @Test
    void aBodyTheUserBoundThemselvesStaysBound() throws IOException {
        // Only the GameLoop::run hop is retargeted; anything else in that slot is the user's choice.
        String rewritten = ScaffoldMigration.rewriteEntryPoint(
                "Bot.start(MyOwnLoop::tick, GoHome.INSTANCE::execute, Startup::run);");
        assertEquals("Bot.start(MyOwnLoop::tick, GoHome.INSTANCE::execute);", rewritten);
    }

    @Test
    void anAlreadyMigratedProjectIsLeftAlone() throws IOException {
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                import com.botmaker.sdk.api.bot.Bot;
                import com.botmaker.sdk.api.bot.PopupGuard;
                public class MyBot {
                    public static void main(String[] args) {
                        PopupGuard.install(Popups.INSTANCE::execute);

                        Bot.start(FlowDriver::run, GoHome.INSTANCE::execute);
                    }
                }
                """);
        String before = Files.readString(config.mainSourceFile());

        assertNull(ScaffoldMigration.migrate(config), "nothing to do reports nothing to do");
        assertEquals(before, Files.readString(config.mainSourceFile()));
    }

    /**
     * A project generated before the popup guard existed gets both halves of it — the install line and the
     * {@code Popups.java} it names. Half a migration is a project that doesn't compile, so the file is written
     * first and the line that references it second.
     */
    @Test
    void aProjectFromBeforeThePopupGuardGainsTheCallAndTheFile() throws IOException {
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                import com.botmaker.sdk.api.bot.Bot;
                public class MyBot {
                    public static void main(String[] args) {
                        Bot.start(FlowDriver::run, GoHome.INSTANCE::execute);
                    }
                }
                """);

        String migrated = ScaffoldMigration.migrate(config);

        assertTrue(migrated.contains("import com.botmaker.sdk.api.bot.PopupGuard;"), migrated);
        assertTrue(migrated.contains("PopupGuard.install(Popups.INSTANCE::execute);"), migrated);
        assertTrue(migrated.indexOf("PopupGuard.install") < migrated.indexOf("Bot.start("),
                "the guard must be installed before the loop it guards: " + migrated);
        assertEquals(migrated, Files.readString(config.mainSourceFile()));
        assertTrue(Files.exists(mainDir.resolve("Popups.java")),
                "the line names a file that has to exist, or the project stops compiling");
        assertTrue(Files.readString(mainDir.resolve("Popups.java")).contains("class Popups extends Activity"));
    }

    /** An entry point the user wrote themselves has no BotMaker import to anchor to, and is left untouched. */
    @Test
    void anEntryPointThatIsNotOursIsNotGivenAGuard() {
        String theirs = "public class MyBot { public static void main(String[] a) { Bot.start(x, y); } }";
        assertEquals(theirs, ScaffoldMigration.installPopupGuard(theirs));
    }

    @Test
    void aProjectThatWasNeverOursKeepsItsFiles() throws IOException {
        // An empty-template project where the user happens to have written a GameLoop.java. Nothing here calls
        // the 3-arg start, so the migration must not fire — deleting their file would be the worst outcome of
        // the lot, and the gate on our own generated call is what prevents it.
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                public class MyBot {
                    public static void main(String[] args) {
                        GameLoop.run();
                    }
                }
                """);
        Path theirs = mainDir.resolve("GameLoop.java");
        String source = """
                package com.mybot;
                public class GameLoop {
                    public static void run() { System.out.println("mine"); }
                }
                """;
        Files.writeString(theirs, source);

        assertNull(ScaffoldMigration.migrate(config));
        assertEquals(source, Files.readString(theirs), "a file we never generated is never deleted");
    }

    @Test
    void aFileTheRewrittenEntryPointStillCallsIsKept() throws IOException {
        // The user hand-edited main to keep calling GameLoop while leaving the 3-arg shape. The rewrite still
        // moves them off the retired overload, but the file is load-bearing, so it stays.
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                import com.botmaker.sdk.api.bot.Bot;
                public class MyBot {
                    public static void main(String[] args) {
                        GameLoop.warmUp();
                        Bot.start(GameLoop::run, GoHome.INSTANCE::execute, Startup::run);
                    }
                }
                """);
        Files.writeString(mainDir.resolve("GameLoop.java"), "package com.mybot;\npublic class GameLoop {}\n");
        Files.writeString(mainDir.resolve("Startup.java"), "package com.mybot;\npublic class Startup {}\n");

        String migrated = ScaffoldMigration.migrate(config);

        assertTrue(migrated.contains("Bot.start(FlowDriver::run, GoHome.INSTANCE::execute)"), migrated);
        assertTrue(Files.exists(mainDir.resolve("GameLoop.java")), "still called, so still needed");
        assertFalse(Files.exists(mainDir.resolve("Startup.java")), "no longer called, so retired");
    }

    @Test
    void aCommentIsNotACall() throws IOException {
        assertFalse(ScaffoldMigration.stripComments("""
                // Startup::run does the launching
                /* and Startup again */
                Bot.start(a, b);
                """).contains("Startup"));
    }
}
