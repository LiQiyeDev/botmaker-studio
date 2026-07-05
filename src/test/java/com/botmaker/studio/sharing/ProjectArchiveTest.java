package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArchiveTest {

    @Test
    void collectsSourcesAndExcludesBuildAndLocalFiles(@TempDir Path project) throws Exception {
        write(project.resolve("pom.xml"), "<project/>");
        write(project.resolve("src/main/java/com/testbot/TestBot.java"), "class TestBot {}");
        write(project.resolve("src/main/resources/app.properties"), "k=v");
        write(project.resolve("target/classes/TestBot.class"), "bytecode");
        write(project.resolve(".git/config"), "[core]");
        write(project.resolve(BotSource.FILE_NAME), "{}");

        Map<String, byte[]> files = ProjectArchive.collect(project);

        assertTrue(files.containsKey("pom.xml"));
        assertTrue(files.containsKey("src/main/java/com/testbot/TestBot.java"));
        assertTrue(files.containsKey("src/main/resources/app.properties"));
        assertFalse(files.containsKey("target/classes/TestBot.class"), "target/ must be excluded");
        assertFalse(files.containsKey(".git/config"), ".git/ must be excluded");
        assertFalse(files.containsKey(BotSource.FILE_NAME), "provenance must be excluded");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
