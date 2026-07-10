package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the generated {@code Activities} class is startup-safe: a missing file, a missing key, a
 * wrong-type value, or a malformed JSON file must all fall back to type defaults without throwing at
 * class-init (which would be an {@code ExceptionInInitializerError} that crashes the bot at launch).
 */
class ActivityGenerationTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static ActivitiesConfig sample() {
        return new ActivitiesConfig(List.of(
                new ActivityVariable("count", ActivityType.INT, JSON.numberNode(0)),
                new ActivityVariable("label", ActivityType.TEXT, JSON.textNode("")),
                new ActivityVariable("startTime", ActivityType.TIME, JSON.textNode("00:00")),
                new ActivityVariable("startDate", ActivityType.DATE, JSON.textNode("2000-01-01"))));
    }

    private static String source(Path root) {
        ProjectConfig cfg = ProjectConfig.forProject("actbot", root);
        return new ActivityService(cfg, null, null).generateSource(sample());
    }

    @Test
    void missingKeyAndWrongTypeFallBackToDefaults(@TempDir Path root) throws Exception {
        // "count" absent (missing key), "startTime" a number (wrong type), "label" valid, "startDate" valid.
        String json = """
                { "activities": [
                    { "name": "label", "value": "hello" },
                    { "name": "startTime", "value": 123 },
                    { "name": "startDate", "value": "2021-06-01" }
                ] }
                """;
        Class<?> activities = compileAndLoad(root, source(root), json);

        assertEquals(0, activities.getField("count").getInt(null), "missing key → int default");
        assertEquals("hello", activities.getField("label").get(null));
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null),
                "wrong-type time → default, not a parse crash");
        assertEquals(LocalDate.of(2021, 6, 1), activities.getField("startDate").get(null));
    }

    @Test
    void malformedJsonFileFallsBackToDefaults(@TempDir Path root) throws Exception {
        Class<?> activities = compileAndLoad(root, source(root), "this is not valid json {{{");

        assertEquals(0, activities.getField("count").getInt(null));
        assertEquals("", activities.getField("label").get(null));
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null));
        assertEquals(LocalDate.of(2000, 1, 1), activities.getField("startDate").get(null));
    }

    @Test
    void missingFileFallsBackToDefaults(@TempDir Path root) throws Exception {
        Class<?> activities = compileAndLoad(root, source(root), null); // no activities.json on classpath

        assertEquals(0, activities.getField("count").getInt(null));
        assertEquals("", activities.getField("label").get(null));
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null));
        assertEquals(LocalDate.of(2000, 1, 1), activities.getField("startDate").get(null));
    }

    /** Compiles {@code source} (package {@code com.actbot}) into a fresh classpath dir with {@code json}
     *  (when non-null) as {@code /activities.json}, then loads and initializes {@code com.actbot.Activities}. */
    private Class<?> compileAndLoad(Path root, String source, String json) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path srcDir = Files.createDirectories(root.resolve("src/com/actbot"));
        Path srcFile = srcDir.resolve("Activities.java");
        Files.writeString(srcFile, source);
        if (json != null) Files.writeString(classes.resolve(ActivitiesConfig.FILE_NAME), json);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                srcFile.toString());
        assertEquals(0, rc, "generated Activities.java should compile");

        URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        Class<?> clazz = Class.forName("com.actbot.Activities", true, loader); // triggers static init
        assertTrue(clazz != null);
        return clazz;
    }
}
