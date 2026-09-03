package com.botmaker.studio.project;

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

    /**
     * The minimum a caller hands in. {@code writeProject} refuses a directory that already holds a
     * {@code pom.xml}, so every call needs one to be a realistic creation rather than a special case.
     */
    private static final java.util.function.Function<ProjectConfig, Map<String, String>> POM =
            config -> Map.of("pom.xml", MavenService.blankPomXml(config));

    @Test
    void theChosenTemplateIsPersisted(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.seedSettings(config, ProjectTemplate.GAME_BOT);

        StudioProjectSettings settings = StudioProjectSettings.read(config.resourcesRoot());
        assertEquals(ProjectTemplate.GAME_BOT, settings.template());
    }

    // theTemplateIsRecordedEvenWithoutAResolution went on 2026-09-01 with the resolution it was about. It
    // held that a null resolution must not stop the template being written; seedSettings takes no resolution
    // now, so there is no early-return left for it to guard.

    /**
     * A new game bot keeps its values in Java. The model is recorded at creation and never inferred later, so
     * this is the one moment it can be got wrong — and getting it wrong is silent: the project would simply
     * behave like a legacy one for the rest of its life.
     *
     * <p>Written here since 2026-09-01 rather than handed to {@code Authoring.createProject} — but read back
     * through {@link ActivitiesConfig}, which is the point worth keeping: the file is written once by
     * {@link ActivitiesConfig#json} and read by every later open, and a stamp lost between the two would
     * re-run every migration step against an already-current file.
     */
    @Test
    void aNewGameBotGetsAnActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.writeProject(config, ProjectTemplate.GAME_BOT, POM.apply(config));

        assertTrue(Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)));
        assertTrue(ActivitiesConfig.read(config.resourcesRoot()).isEmpty());
        assertEquals(SchemaFile.ACTIVITIES.current(),
                SchemaFile.ACTIVITIES.versionIn(config.resourcesRoot()).orElse(0),
                "an unstamped file reads as version 0 and re-runs every migration on the next open");
    }

    @Test
    void anEmptyProjectGetsNoActivitiesFile(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("Plain", root);
        ProjectCreator.writeProject(config, ProjectTemplate.EMPTY, POM.apply(config));

        assertFalse(Files.exists(config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME)));
    }

    /** Every project gets the five directories, whatever its template. */
    @Test
    void theSourceLayoutIsCreated(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("Plain", root);
        ProjectCreator.writeProject(config, ProjectTemplate.EMPTY, POM.apply(config));

        for (String dir : List.of("src/main/java", "src/main/resources", "src/test/java",
                "src/test/resources", "src/main/resources/images")) {
            assertTrue(Files.isDirectory(config.projectPath().resolve(dir)), dir);
        }
    }

    /**
     * Whole-file ownership: a caller may not claim a path creation already writes.
     *
     * <p>Nothing in Studio does today — this is the check that tells a second plugin contributing files that
     * it has claimed a taken path, rather than silently overwriting or merging. Two authors of one file is
     * the mistake the scaffold contract made and was deleted for.
     */
    @Test
    void aCallerCannotClaimAFileCreationWrites(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Map<String, String> colliding = new java.util.LinkedHashMap<>(POM.apply(config));
        colliding.put("src/main/resources/" + ActivitiesConfig.FILE_NAME, "{}");

        assertThrows(IllegalArgumentException.class,
                () -> ProjectCreator.writeProject(config, ProjectTemplate.GAME_BOT, colliding));
    }

    /**
     * All of it or none of it: the refusal lands before a single directory exists.
     *
     * <p>A half-created project is worse than no project — the editor lists it, opening it fails in a
     * different place each time, and the user has to delete it by hand.
     */
    @Test
    void arefusalLeavesNothingBehind(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Map<String, String> colliding = new java.util.LinkedHashMap<>(POM.apply(config));
        colliding.put("src/main/resources/" + ActivitiesConfig.FILE_NAME, "{}");

        assertThrows(IllegalArgumentException.class,
                () -> ProjectCreator.writeProject(config, ProjectTemplate.GAME_BOT, colliding));
        assertFalse(Files.exists(config.projectPath().resolve("src")),
                "nothing may be written before every file has been rendered");
    }

    /** An existing project is refused rather than written over. */
    @Test
    void anExistingProjectIsRefused(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        ProjectCreator.writeProject(config, ProjectTemplate.EMPTY, POM.apply(config));

        assertThrows(IOException.class,
                () -> ProjectCreator.writeProject(config, ProjectTemplate.EMPTY, POM.apply(config)));
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

    // theSpecCarriesTheFullPackage went on 2026-09-01 with ProjectSpecs. It held that the *whole* package
    // (com.mybot) crossed to the SDK where ProjectConfig.packageName() is the last segment alone (mybot) —
    // a half-name that would have compiled the project into the wrong package. There is no boundary left for
    // it to cross: StarterSources and MavenService.pomXml both take the ProjectConfig itself.

    /**
     * The one file a new project starts with is Studio's, and it is the only {@code .java} either half of
     * creation writes.
     *
     * <p>It used to come from the SDK — and before that from a copy {@code ProjectRepair} kept, which lost
     * an import and so "recovered" a project that did not compile. Neither writes source now:
     * {@link StarterSources} composes it once and nothing reads it back, so there is nothing left to drift.
     *
     * <p>There used to be a second shape here, a game bot. It went on 2026-08-30: a game bot is a project
     * that calls the SDK's static API, which is what the gallery already publishes and installs, so a richer
     * starting point is a published template rather than a string constant in Studio.
     */
    @Test
    void aNewProjectStartsWithOneFileAndItIsStudiosOwn(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);

        Map<String, String> starter = StarterSources.of(config);
        assertEquals(List.of("src/main/java/com/mybot/MyBot.java"), List.copyOf(starter.keySet()));

        String main = starter.values().iterator().next();
        assertTrue(main.contains("package com.mybot;"), main);
        assertTrue(main.contains("public class MyBot"), main);
    }

    /**
     * The blank project prints with {@code System.out.println} and imports nothing.
     *
     * <p>It called {@code BotMaker.print} until 2026-08-30, which is an SDK spelling of what the JDK already
     * does — a user's very first line of BotMaker code teaching them a BotMaker word for {@code println}.
     */
    @Test
    void theBlankProjectPrintsWithTheJdk(@TempDir Path root) {
        String main = StarterSources.of(ProjectConfig.forProject("MyBot", root))
                .get("src/main/java/com/mybot/MyBot.java");

        assertTrue(main.contains("System.out.println("), main);
        assertFalse(main.contains("BotMaker.print("), main);
        assertFalse(main.contains("import com.botmaker"), "a blank project imports nothing:\n" + main);
    }
}
