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

        assertEquals(java.util.List.of("MyBot.java", "GameLoop.java", "GoHome.java", "Startup.java",
                "ActivityRegistry.java"), java.util.List.copyOf(sources.keySet()));
        assertTrue(sources.get("MyBot.java").contains("Bot.supervise(GameLoop::run, GoHome::run, Startup::run)"));
        // The method references above only bind if these stay static, no-arg and void.
        assertTrue(sources.get("GoHome.java").contains("public static void run()"));
        assertTrue(sources.get("Startup.java").contains("public static void run()"));
        // Startup is generated wiring: its body launches the project's configured target, not a TODO stub.
        String startup = sources.get("Startup.java");
        assertTrue(startup.contains("Target.start()"), startup);
        assertTrue(startup.contains("import com.botmaker.sdk.api.launch.Target;"), startup);
    }

    @Test
    void theGameLoopChecksTheEffectiveActiveStateNotTheConfiguredDefault() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        String loop = sources.get("GameLoop.java");
        // active() layers a runtime setEnabled/disable override on top of the configured isEnabled() default, so
        // a mid-run disable() actually stops the activity next pass (otherwise the loop runs it forever).
        assertTrue(loop.contains("activity.active()"), loop);
        assertFalse(loop.contains("activity.isEnabled()"), loop);
    }

    @Test
    void theGameLoopEndsTheBotWhenEveryActivityIsDisabled() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        String loop = sources.get("GameLoop.java");
        // Without a stop path, supervise's while(true) spins forever once all activities are disabled. The loop
        // calls Bot.stop() when nothing ran this pass (guarded on a non-empty registry) so the bot actually ends.
        assertTrue(loop.contains("Bot.stop()"), loop);
        assertTrue(loop.contains("!anyActive"), loop);
        assertTrue(loop.contains("import com.botmaker.sdk.api.bot.Bot;"), loop);
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
