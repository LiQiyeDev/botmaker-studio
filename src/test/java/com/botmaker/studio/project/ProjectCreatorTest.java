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

    /**
     * The game-bot scaffold refuses, and says why (2026-08-25, temporarily).
     *
     * <p>Five tests stood here asserting what those files contain — the supervise contract, the editable popup
     * check, the two retired files staying retired, the seeded {@code FlowGraph.of(null)} driver, and the
     * absence of the old auto-disable loop. Every one of them was really a test of the <b>SDK's</b> scaffold
     * templates, read through Studio; the templates left the SDK on 2026-08-25 and the generator that replaces
     * them arrives in inversion phase 2. Re-asserting the content here would mean Studio holding a second copy
     * of the very thing the inversion exists to stop it holding, so the assertions travel with the generator
     * instead of being kept warm in the wrong repository.
     *
     * <p>What is worth testing in the meantime is that the refusal is a refusal: named, unmistakable, and not
     * a quietly empty project.
     */
    @Test
    void theGameBotTemplateRefusesByNameUntilTheSdkGeneratorLands() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, "MyBot", "mybot"));

        assertTrue(refused.getMessage().contains("generated by the SDK"), refused.getMessage());
        assertTrue(refused.getMessage().contains("empty project"),
                "the message must name the way forward, not only the obstacle: " + refused.getMessage());
    }

    @Test
    void theEmptyTemplateEntryPointCompilesAsWritten() {
        Map<String, String> sources = ProjectCreator.sourcesFor(ProjectTemplate.EMPTY, "MyBot", "mybot");

        assertEquals(java.util.List.of("MyBot.java"), java.util.List.copyOf(sources.keySet()));
        String main = sources.get("MyBot.java");
        assertTrue(main.contains("BotMaker.print"), main);
        // ProjectRepair used to hold its own copy of this source that had lost the import, so a recovered
        // empty project didn't compile. There is one copy now; it must carry what it uses.
        assertTrue(main.contains("import com.botmaker.sdk.api.util.BotMaker;"), main);
    }
}
