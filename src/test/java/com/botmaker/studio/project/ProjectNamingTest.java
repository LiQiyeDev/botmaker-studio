package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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

        var starter = StarterSources.of(config);
        assertEquals(List.of("src/main/java/com/mybot/MyBot.java"), List.copyOf(starter.keySet()),
                "the file is named for the class, not for the project");
        String main = starter.values().iterator().next();
        assertTrue(main.contains("class MyBot"),
                "the class must be capitalized or the project doesn't compile:\n" + main);
        assertTrue(main.contains("package com.mybot;"), main);
    }

    @Test
    void theEntryPointPathIsDerivedTheSameWayEverywhere() {
        // ProjectManager used to rebuild this path itself, so it kept its own copy of the naming rule.
        ProjectConfig config = ProjectConfig.forProject("myBot", com.botmaker.studio.config.Constants.PROJECTS_ROOT);
        assertEquals(config.mainSourceFile(), new ProjectManager().getSourceFilePath("myBot"));
    }

    /**
     * The entry point is <b>found</b>, not assumed — the derived name when it is there, otherwise the one
     * class in the package that declares a {@code main}.
     *
     * <p>Two things make the derived name wrong: a user who renames or splits their entry class, and a
     * project made from a published template, which keeps its author's class name. Run and the debugger both
     * launch this, so assuming would mean launching a class that does not exist.
     */
    @Test
    void theEntryPointIsFoundWhenItIsNotNamedAfterTheProject(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        Path packageDir = config.mainSourceFile().getParent();
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("Helper.java"),
                "package com.myfarmer;\nfinal class Helper {}\n");
        Files.writeString(packageDir.resolve("GameBot.java"),
                "package com.myfarmer;\npublic class GameBot { public static void main(String[] a) {} }\n");

        assertEquals("GameBot.java", config.entrySourceFile().getFileName().toString());
        assertEquals("com.myfarmer.GameBot", config.entryClassName());
    }

    @Test
    void theDerivedEntryPointWinsWhenItExists(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        Path packageDir = config.mainSourceFile().getParent();
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("GameBot.java"),
                "package com.myfarmer;\npublic class GameBot { public static void main(String[] a) {} }\n");
        Files.writeString(config.mainSourceFile(),
                "package com.myfarmer;\npublic class MyFarmer { public static void main(String[] a) {} }\n");

        assertEquals(config.mainSourceFile(), config.entrySourceFile(),
                "a project Studio created keeps answering with the file it created");
    }

    @Test
    void anEmptyProjectStillNamesAPathRatherThanNothing(@TempDir Path root) {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        assertEquals(config.mainSourceFile(), config.entrySourceFile(),
                "callers want a path to complain about, not a null");
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
