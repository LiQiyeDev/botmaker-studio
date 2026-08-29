package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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

    /**
     * The failure this guards against is a project whose entry point is called {@code myBot.java} and
     * declares {@code class MyBot} — which does not compile, and which a lowercase project name produces
     * unless the capitalisation is applied in both places at once.
     */
    @Test
    void theStarterFileNameAndItsClassAgree() {
        ProjectConfig config = ProjectConfig.forProject("myBot", ROOT);

        for (ProjectTemplate template : ProjectTemplate.values()) {
            var starter = StarterSources.of(config, template);
            assertEquals(List.of("src/main/java/com/mybot/MyBot.java"), List.copyOf(starter.keySet()),
                    "the file is named for the class, not for the project: " + template);
            String main = starter.values().iterator().next();
            assertTrue(main.contains("class MyBot"),
                    "the class must be capitalized or the project doesn't compile:\n" + main);
            assertTrue(main.contains("package com.mybot;"), main);
        }
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
