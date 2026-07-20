package com.botmaker.studio.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovery of <b>damaged locked methods</b> — the half {@code recover(...)} can't do.
 *
 * <p>The old rule was "never overwrite a file that exists", which is right for whole files and useless here: a
 * {@code GoHome.java} whose {@code run()} had been renamed was present, therefore not missing, therefore fine —
 * while the bot no longer compiled and Recover cheerfully reported nothing to do. What this adds is method-level:
 * restore what BotMaker owns, and only that.
 *
 * <p>The tests to read first are {@link #aRenamedSuperviseHookIsRestoredButItsBodyIsKept} (the reason the
 * SIGNATURE/FULL distinction exists at all) and {@link #aUsersOwnMethodInAScaffoldFileIsNeverTouched} (the
 * promise the dialog makes).
 */
class ProjectRepairAstTest {

    @TempDir
    Path projectsRoot;

    private ProjectConfig config;
    private Path mainDir;
    private Path goHome;

    @BeforeEach
    void setUp() throws IOException {
        config = ProjectConfig.forProject("MyBot", projectsRoot);
        mainDir = config.mainSourceFile().getParent();
        Files.createDirectories(mainDir);
        for (Map.Entry<String, String> e :
                ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, config.projectName(), config.packageName()).entrySet()) {
            Files.writeString(mainDir.resolve(e.getKey()), e.getValue());
        }
        goHome = mainDir.resolve("GoHome.java");
    }

    /** The generator's view of the scaffold — what the caller passes to findDamaged/repairDamaged. */
    private Map<Path, String> canonical() {
        Map<Path, String> byPath = new LinkedHashMap<>();
        for (Map.Entry<String, String> e :
                ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, config.projectName(), config.packageName()).entrySet()) {
            byPath.put(mainDir.resolve(e.getKey()), e.getValue());
        }
        return byPath;
    }

    private List<ProjectRepair.Damage> findDamaged() {
        return ProjectRepair.findDamaged(config, ProjectTemplate.GAME_BOT, canonical());
    }

    private List<Path> repair(List<ProjectRepair.Damage> damaged) throws IOException {
        return ProjectRepair.repairDamaged(config, ProjectTemplate.GAME_BOT, canonical(), damaged);
    }

    @Test
    void anIntactProjectReportsNoDamage() {
        assertEquals(List.of(), findDamaged());
    }

    @Test
    void aRenamedSuperviseHookIsRestoredButItsBodyIsKept() throws IOException {
        // GoHome.run is an @Override of Activity.run bound via GoHome.INSTANCE::execute, so the rename breaks the
        // bot — but the body is the user's work and restoring the signature must not cost them it.
        Files.writeString(goHome, Files.readString(goHome)
                .replace("public Outcome run() {", "public Outcome goHomeNow() {\n        BotMaker.print(\"mine\");"));

        List<ProjectRepair.Damage> damaged = findDamaged();
        assertEquals(1, damaged.size(), "expected exactly the renamed run(): " + damaged);
        assertEquals("run", damaged.getFirst().methodName());
        assertEquals(ProjectRepair.Damage.Kind.MISSING, damaged.getFirst().kind());

        assertEquals(List.of(goHome), repair(damaged));
        String repaired = Files.readString(goHome);
        assertTrue(repaired.contains("Outcome run()"), "run() must be back:\n" + repaired);
        assertEquals(List.of(), findDamaged(), "and the project must now be clean");
    }

    @Test
    void aReParameterisedHookIsRestoredWithTheUsersBody() throws IOException {
        Files.writeString(goHome, Files.readString(goHome)
                .replace("public Outcome run() {", "public Outcome run(int attempts) {\n        BotMaker.print(\"my recovery\");"));

        List<ProjectRepair.Damage> damaged = findDamaged();
        assertEquals(1, damaged.size(), "" + damaged);
        assertEquals(ProjectRepair.Damage.Kind.SIGNATURE_CHANGED, damaged.getFirst().kind());
        assertTrue(damaged.getFirst().describe().contains("signature changed"));

        repair(damaged);
        String repaired = Files.readString(goHome);
        assertTrue(repaired.contains("Outcome run()"), "the signature BotMaker calls is restored:\n" + repaired);
        assertFalse(repaired.contains("int attempts"));
        assertTrue(repaired.contains("my recovery"),
                "a SIGNATURE lock means the body is the user's — it must survive the repair:\n" + repaired);
    }

    @Test
    void aUsersOwnMethodInAScaffoldFileIsNeverTouched() throws IOException {
        String withHelper = Files.readString(goHome).replaceFirst("\\}\\s*$",
                "    public static void myHelper() { BotMaker.print(\"mine\"); }\n}\n");
        Files.writeString(goHome, withHelper);

        assertEquals(List.of(), findDamaged(), "a method BotMaker doesn't own is not damage");

        // And even while repairing something else in the same file, it survives untouched.
        Files.writeString(goHome, Files.readString(goHome).replace("Outcome run()", "Outcome run(int n)"));
        repair(findDamaged());
        assertTrue(Files.readString(goHome).contains("myHelper"),
                "repairing a locked method must not disturb the user's own");
    }

    @Test
    void aUsersEditsToAnUnlockedBodyAreNotDamage() throws IOException {
        // GoHome.run's body is exactly what the user is meant to write. Reporting it as damage — or worse,
        // "restoring" it — would delete their work every time they ran Recover.
        Files.writeString(goHome, Files.readString(goHome)
                .replace("public Outcome run() {", "public Outcome run() {\n        BotMaker.print(\"walking home\");"));
        assertEquals(List.of(), findDamaged());
    }

    @Test
    void anEditedGameLoopBodyIsDamageAndIsRestored() throws IOException {
        // GameLoop.run is MethodLock.FULL: the hook into the generated driver is BotMaker's, so an edited one
        // (the reported "I changed it and Recover didn't notice") must be found and put back.
        Path gameLoop = mainDir.resolve("GameLoop.java");
        Files.writeString(gameLoop, Files.readString(gameLoop)
                .replace("FlowDriver.run();", "somethingElse();"));

        List<ProjectRepair.Damage> damaged = findDamaged();
        assertEquals(1, damaged.size(), "" + damaged);
        assertEquals("run", damaged.getFirst().methodName());
        assertEquals(ProjectRepair.Damage.Kind.BODY_CHANGED, damaged.getFirst().kind());

        repair(damaged);
        String repaired = Files.readString(gameLoop);
        // The rewrite re-prints the replaced method, so compare tokens rather than exact whitespace.
        assertTrue(repaired.contains("FlowDriver.run();"), "the canonical hook is back:\n" + repaired);
        assertFalse(repaired.contains("somethingElse"));
        assertEquals(List.of(), findDamaged(), "sameBody compares printed forms, so the repair must read as clean");
    }

    @Test
    void aLegacyDispatchLoopGameLoopIsUpgradedToTheFlowDriverHook() throws IOException {
        // The migration that matters for every existing bot. A project scaffolded before conditional flow has
        // the whole old dispatch loop inline in GameLoop.run — iterate ActivityRegistry.ALL, run each enabled
        // activity, disable it, stop when none ran. That loop can't branch and can't cycle, so it has to be
        // replaced wholesale; because run() is FULL-locked, Recover is what does it.
        Path gameLoop = mainDir.resolve("GameLoop.java");
        Files.writeString(gameLoop, """
                package com.mybot;

                import com.botmaker.sdk.api.bot.Activity;
                import com.botmaker.sdk.api.bot.Bot;
                import com.botmaker.sdk.api.bot.Watchdog;

                public class GameLoop {
                    public static void run() {
                        boolean anyActive = false;
                        for (Activity activity : ActivityRegistry.ALL) {
                            if (activity.active()) {
                                anyActive = true;
                                activity.execute();
                                activity.disable();
                                Watchdog.checkpoint();
                            }
                        }
                        if (!ActivityRegistry.ALL.isEmpty() && !anyActive) {
                            Bot.stop();
                        }
                    }
                }
                """);

        List<ProjectRepair.Damage> damaged = findDamaged();
        assertEquals(1, damaged.size(), "" + damaged);
        assertEquals(ProjectRepair.Damage.Kind.BODY_CHANGED, damaged.getFirst().kind());

        repair(damaged);
        String repaired = Files.readString(gameLoop);
        assertTrue(repaired.contains("FlowDriver.run();"), "the loop must be upgraded:\n" + repaired);
        assertFalse(repaired.contains("activity.disable()"),
                "the one-shot rule is gone — it is what made a cycle impossible:\n" + repaired);
        assertEquals(List.of(), findDamaged());
    }

    @Test
    void aMissingLockedMethodIsReinserted() throws IOException {
        String source = Files.readString(goHome);
        int start = source.indexOf("public Outcome run()");
        int end = source.lastIndexOf("}");
        Files.writeString(goHome, source.substring(0, start) + source.substring(end));

        List<ProjectRepair.Damage> damaged = findDamaged();
        assertEquals(1, damaged.size(), "" + damaged);
        assertEquals(ProjectRepair.Damage.Kind.MISSING, damaged.getFirst().kind());

        repair(damaged);
        assertTrue(Files.readString(goHome).contains("Outcome run()"));
        assertEquals(List.of(), findDamaged());
    }

    @Test
    void reformattingAScaffoldFileIsNotDamage() throws IOException {
        Files.writeString(goHome, Files.readString(goHome).replace("    ", "\t").replace("\n", "\r\n"));
        assertEquals(List.of(), findDamaged(), "whitespace is not a contract");
    }

    @Test
    void aDeletedFileIsLeftToTheMissingFilePath() throws IOException {
        // findDamaged is about files that are present but wrong; a deleted one is findMissing's job, and
        // reporting it twice would offer the user two different fixes for one problem.
        Files.delete(goHome);
        assertEquals(List.of(), findDamaged());
    }
}
