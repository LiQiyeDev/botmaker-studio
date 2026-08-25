package com.botmaker.studio.project;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A project's name is the user's; its Java class name is derived from it.
 *
 * <p>These used to be the same string, so the New Project dialog had to reject a lowercase first letter —
 * {@code myBot} would have produced {@code class myBot}. Deriving the class name instead lets the user name
 * their project whatever they like and keeps the generated code legal Java. The directory and the Maven
 * artifactId stay exactly as typed: the list should show the user what they wrote.
 */
class ProjectNamingTest {

    private static final Path ROOT = Paths.get("/tmp/projects");

    @Test
    void aLowercaseProjectNameStillYieldsAProperClassName() {
        ProjectConfig config = ProjectConfig.forProject("myBot", ROOT);

        assertEquals("myBot", config.projectName(), "the name is the user's — keep it verbatim");
        assertEquals("MyBot", config.className());
        assertEquals("mybot", config.packageName());
        assertEquals("com.mybot.MyBot", config.mainClassName());
        assertEquals("MyBot.java", config.mainSourceFile().getFileName().toString());
        assertTrue(config.projectPath().endsWith("myBot"), "the directory is named what the user typed");
    }

    @Test
    void anUppercaseProjectNameIsUnchanged() {
        ProjectConfig config = ProjectConfig.forProject("MyBot", ROOT);
        assertEquals("MyBot", config.projectName());
        assertEquals("MyBot", config.className());
        assertEquals("com.mybot.MyBot", config.mainClassName());
    }

    @Test
    void theGeneratedSourcesUseTheClassName() {
        ProjectConfig config = ProjectConfig.forProject("myBot", ROOT);
        String main = ProjectSpecs.generatedSource(config, ProjectTemplate.EMPTY,
                MavenService.SDK_FALLBACK_VERSION, "MyBot.java");

        assertNotNull(main, "expected MyBot.java among "
                + ProjectSpecs.generatedFileNames(config, ProjectTemplate.EMPTY,
                        MavenService.SDK_FALLBACK_VERSION));
        assertTrue(main.contains("class MyBot"),
                "the class must be capitalized or the project doesn't compile:\n" + main);
        assertTrue(main.contains("package com.mybot;"), main);
    }

    @Test
    void theGameBotScaffoldAlsoUsesTheClassName() {
        // Asked of the file *names*: the naming rule this test is about was never in the text, and the names
        // come from the generator that writes them, so a file that stops being emitted stops being asserted.
        // The failure it guards against is unchanged — a project whose entry point is called `myBot.java`
        // and declares `class MyBot`.
        ProjectConfig config = ProjectConfig.forProject("myBot", ROOT);
        assertEquals("MyBot.java", ProjectSpecs.generatedFileNames(config, ProjectTemplate.GAME_BOT,
                MavenService.SDK_FALLBACK_VERSION).getFirst());
    }

    @Test
    void theEntryPointPathIsDerivedTheSameWayEverywhere() {
        // ProjectManager used to rebuild this path itself, so it kept its own copy of the naming rule.
        ProjectConfig config = ProjectConfig.forProject("myBot", com.botmaker.studio.config.Constants.PROJECTS_ROOT);
        assertEquals(config.mainSourceFile(), new ProjectManager().getSourceFilePath("myBot"));
    }

    @Test
    void toClassNameLeavesAnythingElseAlone() {
        assertEquals("MyBot", ProjectConfig.toClassName("myBot"));
        assertEquals("MyBot", ProjectConfig.toClassName("MyBot"));
        assertEquals("M", ProjectConfig.toClassName("m"));
        assertEquals("", ProjectConfig.toClassName(""));
        assertNull(ProjectConfig.toClassName(null));
    }
}
