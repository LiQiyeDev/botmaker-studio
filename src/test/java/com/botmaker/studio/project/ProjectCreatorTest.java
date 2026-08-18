package com.botmaker.studio.project;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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

    /**
     * A new game bot keeps its values in Java. The model is recorded at creation and never inferred later, so
     * this is the one moment it can be got wrong — and getting it wrong is silent: the project would simply
     * behave like a legacy one for the rest of its life.
     */
    @Test
    void aNewGameBotGetsAnActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.seedActivitiesFile(config, ProjectTemplate.GAME_BOT);

        assertTrue(Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)));
        assertTrue(ActivitiesConfig.read(config.resourcesRoot()).isEmpty());
    }

    @Test
    void anEmptyProjectGetsNoActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("Plain", root);
        ProjectCreator.seedActivitiesFile(config, ProjectTemplate.EMPTY);

        assertFalse(Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)));
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
    void sessionIsolatedDefaultsToTrueAndRoundTrips(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Path resources = config.resourcesRoot();

        // No file yet → the default-on state (matching the SDK's SessionBootstrap default).
        assertTrue(ProjectCreator.readSessionIsolated(resources), "isolation defaults on when unset");

        ProjectCreator.writeSessionIsolated(resources, false);
        assertFalse(ProjectCreator.readSessionIsolated(resources), "an explicit opt-out reads back as off");

        ProjectCreator.writeSessionIsolated(resources, true);
        assertTrue(ProjectCreator.readSessionIsolated(resources), "toggling back on reads as on");
    }

    @Test
    void theGameBotTemplateScaffoldsTheSuperviseContract() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        assertEquals(java.util.List.of("MyBot.java", "FlowDriver.java", "GoHome.java", "Popups.java",
                        "ActivityRegistry.java"),
                java.util.List.copyOf(sources.keySet()));
        // The click/vision tuning is a project setting the SDK reads before the first click, so there is no
        // generated BotSettings.java and nothing for main to call. See BotSettingsTest.
        assertFalse(sources.containsKey("BotSettings.java"), sources.keySet().toString());
        assertFalse(sources.get("MyBot.java").contains("BotSettings.apply()"), sources.get("MyBot.java"));
        assertTrue(sources.get("MyBot.java")
                .contains("Bot.start(FlowDriver::run, GoHome.INSTANCE::execute)"));
        // FlowDriver::run binds as a Runnable (static, no-arg, void); GoHome is an Activity so its instance
        // execute() binds as a Runnable too (a value-returning method ref is void-compatible).
        assertTrue(sources.get("GoHome.java").contains("extends Activity<GoHome.Outcome>"));
        assertTrue(sources.get("GoHome.java").contains("public Outcome run()"));
    }

    /**
     * The popup check is the same shape as GoHome — an Activity the entry point binds by method reference, not
     * a flow node — and it ships <em>empty</em>: a scaffold cannot know this game's popups, and a guard that
     * dismissed something the user never configured would be worse than none.
     */
    @Test
    void theGameBotTemplateScaffoldsAnEditablePopupCheck() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        String popups = sources.get("Popups.java");
        assertTrue(popups.contains("class Popups extends Activity<Popups.Outcome>"), popups);
        assertTrue(popups.contains("public Outcome run()"), popups);
        assertTrue(popups.contains("TODO"), "what to click is still the user's to write: " + popups);
        // The scaffold ships the loop, so the editor has a real "while any of […]" block to drop templates
        // into. This declares an empty group, which used to throw in Popups' class initialiser — the SDK now
        // allows one and treats it as "matches nothing" (ImageFinderEmptyGroupTest). A generated project
        // pins SDK_FALLBACK_VERSION, so this scaffold cannot ship ahead of an SDK release carrying that.
        assertTrue(popups.contains("ImageTemplateGroup POPUPS = ImageTemplateGroup.of();"), popups);
        assertTrue(popups.contains("ImageFinder.whileFindAny(POPUPS, found -> {"), popups);
        assertTrue(popups.contains("import com.botmaker.sdk.api.vision.ImageFinder;"), popups);
        assertTrue(popups.contains("import com.botmaker.sdk.api.vision.ImageTemplateGroup;"), popups);
        assertTrue(sources.get("MyBot.java").contains("PopupGuard.install(Popups.INSTANCE::execute);"),
                sources.get("MyBot.java"));
        assertTrue(sources.get("MyBot.java").contains("import com.botmaker.sdk.api.bot.PopupGuard;"),
                sources.get("MyBot.java"));
    }

    @Test
    void theTwoFilesThatHeldNoProjectDataAreNotGeneratedAtAll() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot");

        // GameLoop.java was `FlowDriver.run();` and Startup.java was a two-branch switch over Target — neither
        // held anything about *this* project (the launch target lives in botmaker-project.properties, not in
        // Startup), so both were per-project copies of SDK behaviour. The entry point binds FlowDriver directly
        // and the SDK's 2-arg Bot.start supplies the launch step.
        assertFalse(sources.containsKey("GameLoop.java"), sources.keySet().toString());
        assertFalse(sources.containsKey("Startup.java"), sources.keySet().toString());
        String main = sources.get("MyBot.java");
        assertFalse(main.contains("GameLoop"), main);
        assertFalse(main.contains("Startup"), main);
        // Nothing else may name them either — a scaffold file referring to a class that is never written is a
        // project that doesn't compile.
        sources.forEach((name, src) -> {
            assertFalse(src.contains("GameLoop"), name + " still names GameLoop:\n" + src);
            assertFalse(src.contains("Startup"), name + " still names Startup:\n" + src);
        });
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
