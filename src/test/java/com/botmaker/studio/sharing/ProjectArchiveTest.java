package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void publishStripsTheMachineLocalLaunchAndCaptureKeysAndKeepsTheRest(@TempDir Path project) throws Exception {
        write(project.resolve("src/main/resources/botmaker-project.properties"), """
                launch.target=steam:570
                launch.supported=steam,epic
                capture.source=emulator:MyPhone
                capture.width=1920
                capture.height=1080
                debug=false
                """);

        Map<String, byte[]> files = ProjectArchive.collect(project);
        Properties published = new Properties();
        published.load(new ByteArrayInputStream(files.get("src/main/resources/botmaker-project.properties")));

        // The publisher's own Steam id and their own emulator instance say nothing about the bot.
        assertNull(published.getProperty("launch.target"));
        assertNull(published.getProperty("capture.source"));
        // What the bot itself is — including what its author says it runs on — travels.
        assertEquals("steam,epic", published.getProperty("launch.supported"));
        assertEquals("1920", published.getProperty("capture.width"));
        assertEquals("false", published.getProperty("debug"));
    }

    @Test
    void aProjectPropertiesWithNothingMachineLocalIsPublishedByteForByte(@TempDir Path project) throws Exception {
        String original = "capture.width=1920\ncapture.height=1080\n";
        write(project.resolve("src/main/resources/botmaker-project.properties"), original);

        Map<String, byte[]> files = ProjectArchive.collect(project);

        assertEquals(original,
                new String(files.get("src/main/resources/botmaker-project.properties"), StandardCharsets.UTF_8),
                "nothing to strip must mean nothing rewritten — no reordering, no new comment header");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
