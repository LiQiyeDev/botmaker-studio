package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the two things a new project must carry for the rest of the Studio to know what it is: its template,
 * recorded in {@code settings.json}, and its starting sources.
 */
class ProjectCreatorTest {

    @Test
    void theChosenTemplateIsPersisted(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.seedSettings(config, new StudioProjectSettings.Resolution(1920, 1080),
                ProjectTemplate.GAME_BOT);

        assertEquals(ProjectTemplate.GAME_BOT,
                StudioProjectSettings.read(config.resourcesRoot()).template());
    }

    @Test
    void theTemplateIsRecordedEvenWithoutAResolution(@TempDir Path root) throws IOException {
        // seedResolution used to bail out early on a null resolution. The resolution is optional (it
        // auto-seeds from the window on first capture); the template is not — losing it would make the
        // project indistinguishable from a legacy one and unlock its generated scaffolding.
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.seedSettings(config, null, ProjectTemplate.GAME_BOT);

        StudioProjectSettings settings = StudioProjectSettings.read(config.resourcesRoot());
        assertEquals(ProjectTemplate.GAME_BOT, settings.template());
        assertNull(settings.referenceResolution());
    }

    @Test
    void aLegacySettingsFileWithoutATemplateStillReads(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        java.nio.file.Files.createDirectories(config.resourcesRoot());
        java.nio.file.Files.writeString(config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME),
                "{\"captureTargets\":[],\"defaultTargetIndex\":null}");

        assertNull(StudioProjectSettings.read(config.resourcesRoot()).template(),
                "an older project has no template recorded; callers fall back to the heuristic");
    }

    @Test
    void theGameBotTemplateScaffoldsTheSuperviseContract() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        assertEquals(java.util.List.of("MyBot.java", "GameLoop.java", "FlowDriver.java", "GoHome.java",
                "Startup.java", "ActivityRegistry.java"), java.util.List.copyOf(sources.keySet()));
        assertTrue(sources.get("MyBot.java").contains("Bot.start(GameLoop::run, GoHome::run, Startup::run)"));
        // GameLoop::run / GoHome::run bind as Runnables (static, no-arg, void); Startup::run binds as a
        // Consumer<StartMode>, so it stays static+void but takes the StartMode the supervisor hands it.
        assertTrue(sources.get("GoHome.java").contains("public static void run()"));
        assertTrue(sources.get("Startup.java").contains("public static void run(StartMode mode)"));
        // Startup is generated wiring: it launches the project's configured target, choosing skip-if-running on
        // a cold start vs. force-stop-then-relaunch on a recovery restart — not a TODO stub.
        String startup = sources.get("Startup.java");
        assertTrue(startup.contains("Target.startIfNotRunning()"), startup);
        assertTrue(startup.contains("Target.restart()"), startup);
        assertTrue(startup.contains("import com.botmaker.sdk.api.launch.Target;"), startup);
        assertTrue(startup.contains("import com.botmaker.sdk.api.bot.StartMode;"), startup);
    }

    @Test
    void theGameLoopIsOnlyAHookIntoTheGeneratedFlowDriver() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        String loop = sources.get("GameLoop.java");
        // GameLoop stays a file of its own only because the entry point binds GameLoop::run and the entry point
        // is the user's to edit. All the dispatch moved to FlowDriver, which ActivityService regenerates on
        // every save — so nothing here may know anything about activities.
        assertTrue(loop.contains("FlowDriver.run();"), loop);
        assertFalse(loop.contains("ActivityRegistry"), "the loop no longer iterates a list:\n" + loop);
    }

    @Test
    void theSeededFlowDriverStopsImmediatelyAndCompilesWithNoActivities() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        String driver = sources.get("FlowDriver.java");
        // A brand-new project has no flow yet, so there is no start node to go to. It must still be valid Java
        // that ends cleanly — this is what GameLoop compiles against before the first activity is added.
        assertTrue(driver.contains("String node = null;"), driver);
        assertTrue(driver.contains("Bot.stop();"), driver);
        assertTrue(driver.contains("import com.botmaker.sdk.api.bot.Bot;"), driver);
    }

    @Test
    void nothingAutoDisablesAnActivityAnyMore() {
        // Deliberate behaviour change. The old loop ran every enabled activity once and disable()d it right
        // after, so the bot stopped when all of them had run. That makes a cycle impossible — an activity wired
        // back to itself would be dead on its second visit — so the one-shot rule had to go. A run now ends by
        // reaching Stop or an outcome with no wire, and MAX_STEPS is what bounds a loop with no exit.
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        assertFalse(sources.get("GameLoop.java").contains("disable()"), sources.get("GameLoop.java"));
        assertFalse(sources.get("FlowDriver.java").contains("disable()"), sources.get("FlowDriver.java"));
        assertTrue(sources.get("FlowDriver.java").contains("MAX_STEPS"), sources.get("FlowDriver.java"));
    }

    @Test
    void theEmptyTemplateEntryPointCompilesAsWritten() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.EMPTY, "MyBot", "mybot");

        assertEquals(java.util.List.of("MyBot.java"), java.util.List.copyOf(sources.keySet()));
        String main = sources.get("MyBot.java");
        assertTrue(main.contains("BotMaker.print"), main);
        // ProjectRepair used to hold its own copy of this source that had lost the import, so a recovered
        // empty project didn't compile. There is one copy now; it must carry what it uses.
        assertTrue(main.contains("import com.botmaker.sdk.api.BotMaker;"), main);
    }
}
