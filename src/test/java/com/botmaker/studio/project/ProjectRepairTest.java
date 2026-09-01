package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.sdk.authoring.TemplateLibrary;
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
        layDownScaffold();
        layDownResources();
    }

    /**
     * A full game-bot scaffold, the way {@code ProjectCreator} used to lay one down.
     *
     * <p>Written here as fixture text since 2026-08-25, because {@code ProjectCreator.sourcesFor} refuses for
     * {@code GAME_BOT} until the SDK's generator lands (inversion phase 2). What this class is about is
     * <em>presence</em> — which files a project must have and what happens when one is gone — and that is not
     * a question about the text, so a stand-in serves it exactly as well as the real thing. The one place the
     * text mattered was an assertion that a restored {@code FlowDriver} contained {@code MAX_STEPS}, and there
     * is no restoring to assert on any more.
     *
     * <p>It is three files now, not five. {@code FlowDriver} and {@code ActivityRegistry} were both derived
     * entirely from the model and are read at run time instead ({@code FlowGraph.load}), so a game bot's
     * whole scaffold is its entry point, {@code GoHome} and {@code Popups}.
     */
    private void layDownScaffold() throws IOException {
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                public class MyBot {
                    public static void main(String[] args) {
                        Bot.start(() -> FlowGraph.run(MyBot.class, GoHome.INSTANCE::execute),
                                GoHome.INSTANCE::execute);
                    }
                }
                """);
        for (String name : List.of("GoHome.java", "Popups.java")) {
            String type = name.substring(0, name.length() - ".java".length());
            Files.writeString(mainDir.resolve(name),
                    "package com.mybot;\npublic class " + type + " {\n}\n");
        }
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
        TemplateLibrary.writePlaceholderAt(
                config.imagesRoot().resolve(TemplateLibrary.DEFAULT_TEMPLATE_FILE));
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
        for (String name : List.of("GoHome.java", "Popups.java")) {
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
        // user who wrote their own GoHome.java in an empty project had their only file turned read-only.
        for (String name : List.of("GoHome.java", "Popups.java")) {
            Files.delete(mainDir.resolve(name));
        }
        Files.writeString(config.mainSourceFile(), """
                package com.mybot;
                public class MyBot {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(mainDir.resolve("GoHome.java"), """
                package com.mybot;
                public class GoHome {
                    public static void run() {}
                }
                """);
        assertFalse(ProjectRepair.looksLikeGameBot(config),
                "a lone GoHome.java is a file the user wrote, not a scaffold");
    }

    /**
     * A deleted {@code .java} is not reported and not restored — the reversal of 2026-08-29.
     *
     * <p>It used to be both: a game bot's file set came from the generator, and each entry carried a
     * restorer that asked the project's own SDK to emit it again. Nothing generates a project's Java now, so
     * there is no list of files a project "must" have and nothing that could write one back. A file that is
     * gone is a file its owner deleted, and inventing a starting point for code that has since been written
     * and thrown away is worse than leaving the gap.
     */
    @Test
    void aDeletedSourceFileIsLeftAlone() throws IOException {
        Files.delete(mainDir.resolve("Popups.java"));

        assertTrue(ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty())
                .isEmpty());
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
        Path placeholder = config.imagesRoot().resolve(TemplateLibrary.DEFAULT_TEMPLATE_FILE);
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

        Files.delete(mainDir.resolve("Popups.java"));   // something else is genuinely missing
        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, ActivitiesConfig.empty());
        ProjectRepair.recover(config, missing);

        assertEquals(userEdited, Files.readString(goHome), "recovery must not clobber an existing file");
    }

    /**
     * An activity implies no file. Its settings are still expected — {@code activities.json} is the model,
     * and this class holds the only copy left of it once the file is gone — but there is no subclass stub to
     * miss, because an activity's behaviour is an {@code Activities.define} call in a file the user owns and
     * BotMaker never knew where it was.
     */
    @Test
    void anActivityImpliesItsSettingsAndNoSource() {
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());

        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities);

        assertEquals(List.of("activities.json"),
                missing.stream().map(ProjectRepair.Missing::fileName).toList());
        assertTrue(missing.stream().allMatch(m -> m.restorer() != null));
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
    void recoverWritesTheActivitySettingsBack() throws IOException {
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());
        List<ProjectRepair.Missing> missing = ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities);

        List<Path> written = ProjectRepair.recover(config, missing);
        assertEquals(List.of("activities.json"),
                written.stream().map(p -> p.getFileName().toString()).toList());
        assertTrue(written.stream().allMatch(Files::exists));
    }

    @Test
    void summariseGroupsByReason() throws IOException {
        Files.delete(config.projectPath().resolve("pom.xml"));
        ActivitiesConfig activities = ActivitiesConfig.of(
                List.of(new ActivityDefinition("Mining", true, "", List.of(), true, true)), List.of());

        Map<String, List<String>> summary =
                ProjectRepair.summarise(ProjectRepair.findMissing(config, ProjectTemplate.GAME_BOT, activities));
        assertEquals(List.of("activities.json"), summary.get("activity settings"));
        assertEquals(List.of("pom.xml"), summary.entrySet().stream()
                .filter(e -> e.getKey().startsWith("build file")).findFirst().orElseThrow().getValue());
    }

    /** A deleted entry point is the user's deletion too, whatever shape of project it is. */
    @Test
    void aMissingEntryPointIsNotRecovered() throws IOException {
        Files.delete(config.mainSourceFile());

        assertTrue(ProjectRepair.findMissing(config, ProjectTemplate.EMPTY, ActivitiesConfig.empty())
                .isEmpty());
    }
}
