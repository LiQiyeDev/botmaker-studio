package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers recovery of files deleted outside the Studio. The load-bearing guarantee is that recovery
 * <b>only creates what is absent and never overwrites</b> — it must not be able to destroy user work.
 */
class ProjectRepairTest {

    @TempDir
    Path projectsRoot;

    private ProjectConfig config;
    private Path mainDir;

    @BeforeEach
    void setUp() throws IOException {
        config = ProjectConfig.forProject("MyBot", projectsRoot);
        mainDir = config.mainSourceFile().getParent();
        Files.createDirectories(mainDir);
        // Lay down a full game-bot scaffold, the way ProjectCreator would.
        for (Map.Entry<String, String> e :
                ProjectCreator.sourcesFor(ProjectTemplate.GAME_BOT, config.projectName(), config.packageName()).entrySet()) {
            Files.writeString(mainDir.resolve(e.getKey()), e.getValue());
        }
        layDownResources();
    }

    /**
     * The non-Java half of a project: the build file, the two data files and the placeholder image.
     *
     * <p>Written here rather than left out because recovery covers them too — a project missing its
     * {@code pom.xml} is as broken as one missing {@code FlowDriver.java}, and rather more confusingly. A
     * fixture that omits them would make every other assertion in this class count four extra findings.
     */
    private void layDownResources() throws IOException {
        Files.createDirectories(config.resourcesRoot());
        MavenService.writePom(config.projectPath(), config, MavenService.SDK_FALLBACK_VERSION);
        BotSettings.write(config.resourcesRoot(), BotSettings.GAME_DEFAULTS);
        StudioProjectSettings.empty().withTemplate(ProjectTemplate.GAME_BOT).write(config.resourcesRoot());
        ProjectCreator.createDefaultTemplateAt(
                config.imagesRoot().resolve(ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE));
    }

    @Test
    void anIntactProjectHasNothingToRecover() {
        assertTrue(ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty()).isEmpty());
    }

    @Test
    void aGameBotProjectIsDetected() {
        assertTrue(ProjectRepair.looksLikeGameBot(config));
    }

    @Test
    void anEmptyProjectIsNotMistakenForAGameBot() throws IOException {
        for (String name : List.of("FlowDriver.java", "GoHome.java", "ActivityRegistry.java")) {
            Files.delete(mainDir.resolve(name));
        }
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                public class MyBot {
                    public static void main(String[] args) {}
                }
                """);
        assertFalse(ProjectRepair.looksLikeGameBot(config));
    }

    @Test
    void aStrayScaffoldNameDoesNotMakeAnEmptyProjectAGameBot() throws IOException {
        // One file named like scaffolding used to be enough to guess GAME_BOT. That guess feeds FileRole, so a
        // user who wrote their own FlowDriver.java in an empty project had their only file turned read-only.
        for (String name : List.of("FlowDriver.java", "GoHome.java", "ActivityRegistry.java")) {
            Files.delete(mainDir.resolve(name));
        }
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                public class MyBot {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(mainDir.resolve("FlowDriver.java"), """
                package com.mybot;
                public class FlowDriver {
                    public static void run() {}
                }
                """);
        assertFalse(ProjectRepair.looksLikeGameBot(config),
                "a lone FlowDriver.java is a file the user wrote, not a scaffold");
    }

    @Test
    void aFileDeletedOutsideStudioIsFoundAndRestored() throws IOException {
        Path driver = mainDir.resolve("FlowDriver.java");
        Files.delete(driver);   // e.g. an `rm` outside the Studio

        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        assertEquals(1, missing.size());
        assertEquals("FlowDriver.java", missing.get(0).fileName());

        List<Path> written = ProjectRepair.recover(config, missing);
        assertEquals(List.of(driver), written);
        assertTrue(Files.exists(driver));
        assertTrue(Files.readString(driver).contains("class FlowDriver"));
        assertTrue(Files.readString(driver).contains("MAX_STEPS"));
    }

    @Test
    void aDeletedBuildFileIsFoundAndRestored() throws IOException {
        Path pom = config.projectPath().resolve("pom.xml");
        Files.delete(pom);

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        assertEquals(List.of("pom.xml"), missing.stream().map(ProjectRepair.Missing::fileName).toList());
        // The SDK pin is the one thing a rewritten pom cannot recover — it was only ever written down here —
        // so the reason says out loud which version the project is about to be put on.
        assertTrue(missing.getFirst().reason().contains(MavenService.SDK_FALLBACK_VERSION));

        assertEquals(List.of(pom), ProjectRepair.recover(config, missing));
        assertTrue(Files.readString(pom).contains("botmaker-sdk"));
    }

    @Test
    void deletedResourceFilesAreFoundAndRestored() throws IOException {
        Path properties = config.resourcesRoot().resolve(ProjectProperties.FILE_NAME);
        Path settings = config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME);
        Path placeholder = config.imagesRoot().resolve(ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE);
        Files.delete(properties);
        Files.delete(settings);
        Files.delete(placeholder);

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        assertEquals(List.of("botmaker-project.properties", "settings.json", "default_template.png"),
                missing.stream().map(ProjectRepair.Missing::fileName).toList());

        assertEquals(3, ProjectRepair.recover(config, missing).size());
        assertTrue(Files.exists(properties));
        assertTrue(Files.exists(placeholder));
        // The template is the one thing settings.json holds that cannot be re-derived, so it is restored only
        // with the recorded one written back into it — never guessed at from what the source files look like.
        assertEquals(ProjectTemplate.GAME_BOT,
                StudioProjectSettings.read(config.resourcesRoot()).template());
    }

    @Test
    void editorSettingsAreNotInventedForAProjectWhoseTemplateIsUnknown() throws IOException {
        Files.delete(config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME));

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, null, ActivitiesConfig.empty());

        // Writing a template guessed from `looksLikeGameBot` would turn a guess into a recorded fact, which is
        // worse than the absent file: nothing downstream could tell the two apart afterwards.
        assertTrue(missing.stream().noneMatch(m -> m.fileName().equals("settings.json")));
    }

    @Test
    void recoveryNeverOverwritesAnExistingFile() throws IOException {
        // The user's own edits to an editable scaffold file must survive a recovery run.
        Path goHome = mainDir.resolve("GoHome.java");
        String userEdited = "package com.mybot;\n"
                + "import com.botmaker.sdk.api.bot.Activity;\n"
                + "public class GoHome extends Activity<GoHome.Outcome> {\n"
                + "    public static final GoHome INSTANCE = new GoHome();\n"
                + "    public enum Outcome { NEXT }\n"
                + "    @Override public boolean isEnabled() { return true; }\n"
                + "    @Override public Outcome run() { /* mine */ return Outcome.NEXT; }\n"
                + "}\n";
        Files.writeString(goHome, userEdited);

        Files.delete(mainDir.resolve("FlowDriver.java"));   // something else is genuinely missing
        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        ProjectRepair.recover(config, missing);

        assertEquals(userEdited, Files.readString(goHome), "recovery must not clobber an existing file");
    }

    @Test
    void severalMissingFilesAreAllRestored() throws IOException {
        Files.delete(mainDir.resolve("FlowDriver.java"));
        Files.delete(mainDir.resolve("GoHome.java"));
        Files.delete(mainDir.resolve("ActivityRegistry.java"));

        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        assertEquals(3, missing.size());

        ProjectRepair.recover(config, missing);
        assertTrue(Files.exists(mainDir.resolve("FlowDriver.java")));
        assertTrue(Files.exists(mainDir.resolve("GoHome.java")));
        assertTrue(Files.exists(mainDir.resolve("ActivityRegistry.java")));
    }

    @Test
    void missingActivityStubsAreReportedAndDelegated() {
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());

        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities);

        // Everything ActivityService owns for this activity is absent here: its settings, the generated
        // Activities class holding the enable flag, and the subclass stub.
        assertEquals(List.of("activities.json", "Activities.java", "Mining.java"),
                missing.stream().map(ProjectRepair.Missing::fileName).toList());
        // None of them carry a restorer: ActivityService owns generating them, not ProjectRepair.
        assertTrue(missing.stream().allMatch(m -> m.restorer() == null));
        assertTrue(ProjectRepair.needsActivityRegeneration(missing));
    }

    @Test
    void aDeletedActivitiesClassIsRecoverable() {
        // The explorer's delete dialog promises Recover can bring Activities.java back; it must actually
        // report it. It is generated by ActivityService and is in no template's source list, so nothing
        // used to notice it was gone — Recover said "nothing to recover" while the project wouldn't compile.
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());

        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities);
        assertTrue(missing.stream().anyMatch(m -> m.fileName().equals("Activities.java")
                && m.reason().equals("generated activity code")));
    }

    @Test
    void anActivitiesClassIsNotExpectedWhenThereIsNothingToPutInIt() {
        // ActivityService deletes Activities.java when there are no variables at all, so its absence is
        // correct rather than damage. Same for activities.json, which a fresh project never writes.
        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        assertTrue(missing.isEmpty(), "an activity-less project is intact: " + missing);
    }

    @Test
    void recoverSkipsStubsItDoesNotOwn() throws IOException {
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());
        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities);

        List<Path> written = ProjectRepair.recover(config, missing);
        assertTrue(written.isEmpty(), "the stub is ActivityService's to write, not ProjectRepair's");
    }

    @Test
    void summariseGroupsByReason() throws IOException {
        // GoHome, not the driver: with activities present findMissing leaves FlowDriver to ActivityService.
        Files.delete(mainDir.resolve("GoHome.java"));
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());

        Map<String, List<String>> summary =
                ProjectRepair.summarise(ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities));
        assertEquals(List.of("GoHome.java"), summary.get("game-bot scaffold"));
        assertEquals(List.of("Mining.java"), summary.get("activity stub"));
    }

    @Test
    void aMissingEntryPointIsRecoveredForANonGameBotProject() throws IOException {
        for (String name : List.of("FlowDriver.java", "GoHome.java", "ActivityRegistry.java")) {
            Files.delete(mainDir.resolve(name));
        }
        Files.delete(config.mainSourceFile());

        List<ProjectRepair.Missing> missing =
                ProjectRepair.findMissing(config, ProjectTemplate.EMPTY, ActivitiesConfig.empty());
        assertEquals(1, missing.size());
        assertEquals("entry point", missing.get(0).reason());

        ProjectRepair.recover(config, missing);
        assertTrue(Files.exists(config.mainSourceFile()));
    }
}
