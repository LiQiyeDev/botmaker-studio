package com.botmaker.studio.project;

import com.botmaker.sdk.authoring.Authoring;
import com.botmaker.sdk.authoring.SdkVersion;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.migration.SchemaFile;
import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
     *
     * <p>Since 2026-08-25 the file is written by the SDK, not seeded here; this asserts the <b>hand-over</b>
     * (Studio asks for a game bot and one arrives) rather than the writing, which is
     * {@code ProjectCreateTest}'s in the SDK's own build. It still reads the result through
     * {@link ActivitiesConfig}, which is the point worth keeping: the two parsers must agree about the file.
     */
    @Test
    void aNewGameBotGetsAnActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Authoring.createProject(SdkVersion.latest(),
                ProjectSpecs.of(config, ProjectTemplate.GAME_BOT, MavenService.SDK_FALLBACK_VERSION, null),
                config.projectPath(), SchemaFile.ACTIVITIES.current(),
                Map.of("pom.xml", MavenService.pomXml(config, MavenService.SDK_FALLBACK_VERSION)));

        assertTrue(Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)));
        assertTrue(ActivitiesConfig.read(config.resourcesRoot()).isEmpty());
    }

    @Test
    void anEmptyProjectGetsNoActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("Plain", root);
        Authoring.createProject(SdkVersion.latest(),
                ProjectSpecs.of(config, ProjectTemplate.EMPTY, MavenService.SDK_FALLBACK_VERSION, null),
                config.projectPath(), SchemaFile.ACTIVITIES.current(),
                Map.of("pom.xml", MavenService.pomXml(config, MavenService.SDK_FALLBACK_VERSION)));

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

    /**
     * The package Studio hands the SDK is the <b>whole</b> one.
     *
     * <p>{@link ProjectConfig#packageName()} is the last segment only ({@code mybot}), and the generated
     * sources must declare {@code package com.mybot;}. A half-name crossing this boundary produces a project
     * that compiles into the wrong package and is noticed at run time, which is why the prefixing lives in
     * {@link ProjectSpecs} rather than at each call site.
     */
    @Test
    void theSpecCarriesTheFullPackage(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        assertEquals("mybot", config.packageName());
        assertEquals("com.mybot",
                ProjectSpecs.of(config, ProjectTemplate.GAME_BOT, "1.2.0", null).packageName());
    }

    /**
     * The one file a new project starts with is Studio's, and it is the only {@code .java} either half of
     * creation writes.
     *
     * <p>It used to come from the SDK — and before that from a copy {@code ProjectRepair} kept, which lost
     * an import and so "recovered" a project that did not compile. Neither writes source now:
     * {@link StarterSources} composes it once and nothing reads it back, so there is nothing left to drift.
     */
    @Test
    void aNewProjectStartsWithOneFileAndItIsStudiosOwn(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);

        Map<String, String> empty = StarterSources.of(config, ProjectTemplate.EMPTY);
        assertEquals(List.of("src/main/java/com/mybot/MyBot.java"), List.copyOf(empty.keySet()));
        assertTrue(empty.values().iterator().next().contains("package com.mybot;"));

        String gameBot = StarterSources.of(config, ProjectTemplate.GAME_BOT)
                .get("src/main/java/com/mybot/MyBot.java");
        assertNotNull(gameBot);
        assertTrue(gameBot.contains("public class MyBot"), gameBot);
        // The three things a game bot's entry point is for: the guard, the flow, and the recovery hook it
        // hands to both. GoHome.java and Popups.java were separate files until 2026-08-29 — one file is what
        // "written once and never touched again" can honestly promise.
        assertTrue(gameBot.contains("PopupGuard.install(MyBot::dismissPopups)"), gameBot);
        assertTrue(gameBot.contains("FlowGraph.run(MyBot.class, MyBot::goHome)"), gameBot);
        assertTrue(gameBot.contains("static void goHome()"), gameBot);
        // The shape of a define call is shown rather than written: at creation there are no activities.
        assertTrue(gameBot.contains("Activities.define(\"Mining\""), gameBot);
    }
}
