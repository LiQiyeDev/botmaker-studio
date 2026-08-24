package com.botmaker.studio.services;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code Activities} class, compiled and run for real.
 *
 * <p>Asserting on the source text would pass on source that does not compile, and the whole risk of a code
 * generator is source that does not compile. So each case writes the class out, runs {@code javac} over it,
 * loads it — which is what triggers the static initializer — and reads the fields back.
 */
class ActivityGenerationTest {

    private static ActivityVariable variable(String name, BotType type, String value) {
        return ActivityVariable.create(name, BotType.Choice.of(type)).withValue(value);
    }

    private static ActivitiesConfig sample() {
        return ActivitiesConfig.of(List.of(), List.of(
                variable("count", BotType.WHOLE_NUMBER, "0"),
                variable("label", BotType.TEXT, ""),
                variable("startTime", BotType.TIME_OF_DAY, "00:00"),
                variable("startDate", BotType.DATE, "2000-01-01"),
                variable("giveUpAfter", BotType.DURATION, "0s")));
    }

    private static String source(Path root, ActivitiesConfig cfg) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("actbot", root);
        return new ActivityService(config, null, null).generateSource(cfg);
    }

    /** The stored value of a variable, in the shape {@code activities.json} holds it. */
    private static String json(ActivitiesConfig cfg) {
        StringBuilder out = new StringBuilder("{ \"variables\": [");
        List<ActivityVariable> variables = cfg.variables();
        for (int i = 0; i < variables.size(); i++) {
            ActivityVariable v = variables.get(i);
            out.append("{ \"name\": \"").append(v.name()).append("\", \"value\": [");
            out.append(String.join(", ", v.value().stream().map(s -> '"' + s + '"').toList()));
            out.append("] }").append(i < variables.size() - 1 ? ", " : "");
        }
        return out.append("] }").toString();
    }

    @Test
    void everyTypeRoundTripsThroughTheGeneratedClass(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                variable("count", BotType.WHOLE_NUMBER, "7"),
                variable("label", BotType.TEXT, "hello"),
                variable("startTime", BotType.TIME_OF_DAY, "07:30"),
                variable("startDate", BotType.DATE, "2021-06-01"),
                variable("giveUpAfter", BotType.DURATION, "1m30s"),
                variable("ratio", BotType.DECIMAL_NUMBER, "0.75"),
                variable("busy", BotType.YES_NO, "true")));
        Class<?> activities = compileAndLoad(root, source(root, cfg), json(cfg));

        assertEquals(7, activities.getField("count").getInt(null));
        assertEquals("hello", activities.getField("label").get(null));
        assertEquals(LocalTime.of(7, 30), activities.getField("startTime").get(null));
        assertEquals(LocalDate.of(2021, 6, 1), activities.getField("startDate").get(null));
        assertEquals(Duration.ofSeconds(90), activities.getField("giveUpAfter").get(null));
        assertEquals(0.75, activities.getField("ratio").getDouble(null), 1e-9);
        assertTrue(activities.getField("busy").getBoolean(null));
    }

    @Test
    void aMissingKeyFallsBackToTheTypeDefault(@TempDir Path root) throws Exception {
        // Only "label" is stored; everything else has to come back as its type's default rather than throw.
        String stored = """
                { "variables": [ { "name": "label", "value": ["hello"] } ] }
                """;
        Class<?> activities = compileAndLoad(root, source(root, sample()), stored);

        assertEquals(0, activities.getField("count").getInt(null));
        assertEquals("hello", activities.getField("label").get(null));
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null));
        assertEquals(LocalDate.of(2000, 1, 1), activities.getField("startDate").get(null));
        assertEquals(Duration.ZERO, activities.getField("giveUpAfter").get(null));
    }

    @Test
    void aValueOfTheWrongShapeFallsBackRatherThanThrowing(@TempDir Path root) throws Exception {
        // What a hand-edited activities.json looks like: a bare value where the array belongs, and text where
        // a time belongs. The bot has to start anyway.
        String stored = """
                { "variables": [
                    { "name": "count", "value": "12" },
                    { "name": "startTime", "value": ["not a time"] }
                ] }
                """;
        Class<?> activities = compileAndLoad(root, source(root, sample()), stored);

        assertEquals(0, activities.getField("count").getInt(null), "a bare value is not the stored shape");
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null));
    }

    @Test
    void malformedJsonFallsBackToDefaults(@TempDir Path root) throws Exception {
        Class<?> activities = compileAndLoad(root, source(root, sample()), "this is not valid json {{{");

        assertEquals(0, activities.getField("count").getInt(null));
        assertEquals("", activities.getField("label").get(null));
        assertEquals(LocalTime.MIDNIGHT, activities.getField("startTime").get(null));
    }

    @Test
    void aMissingFileFallsBackToDefaults(@TempDir Path root) throws Exception {
        Class<?> activities = compileAndLoad(root, source(root, sample()), null);

        assertEquals(0, activities.getField("count").getInt(null));
        assertEquals("", activities.getField("label").get(null));
        assertEquals(LocalDate.of(2000, 1, 1), activities.getField("startDate").get(null));
    }

    @Test
    void aListVariableReadsBackAsItsItems(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                ActivityVariable.create("skills", BotType.Choice.listOf(BotType.TEXT))
                        .withValue(List.of("mine", "cook")),
                ActivityVariable.create("counts", BotType.Choice.listOf(BotType.WHOLE_NUMBER))
                        .withValue(List.of("1", "2", "3"))));
        Class<?> activities = compileAndLoad(root, source(root, cfg), json(cfg));

        assertEquals(List.of("mine", "cook"), activities.getField("skills").get(null));
        assertEquals(List.of(1, 2, 3), activities.getField("counts").get(null),
                "a list of a primitive comes back boxed, which is the only thing a List can hold");
    }

    @Test
    void aChoiceIsAPlainStringInTheGeneratedCode(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(List.of(), List.of(
                ActivityVariable.create("mode", new BotType.Choice(BotType.TEXT, BotType.Shape.ONE_OF))
                        .withOptions(List.of("fast", "safe")).withValue("safe")));
        String generated = source(root, cfg);

        assertTrue(generated.contains("public static final String mode;"), generated);
        assertFalse(generated.contains("\"fast\""), "the option list is the editor's, and never reaches the bot");
        assertEquals("safe", compileAndLoad(root, generated, json(cfg)).getField("mode").get(null));
    }

    /**
     * An activity's enable flag is a field like any other — and a blank final assigned in the static block,
     * never an inline initializer. That is the JLS §4.12.4 constant-folding trap: {@code static final boolean
     * X = false} would make {@code while (Activities.X)} an unreachable-statement compile error, so a bot
     * would stop compiling because its user unticked a box.
     */
    @Test
    void anEnableFlagIsABlankFinalAssignedInTheStaticBlock(@TempDir Path root) throws Exception {
        ActivitiesConfig cfg = ActivitiesConfig.of(
                List.of(ActivityDefinition.create("Mining", "").withEnabled(true)), List.of());
        String generated = source(root, cfg);

        assertTrue(generated.contains("public static final boolean Mining;"), generated);
        assertFalse(generated.contains("boolean Mining ="), "an inline initializer folds at every use site");
        assertTrue(generated.contains("Mining = Wire.flag(Wire.one(\"Mining\"));"), generated);

        String stored = "{ \"activities\": [ { \"name\": \"Mining\", \"enabled\": true } ] }";
        assertTrue(compileAndLoad(root, generated, stored).getField("Mining").getBoolean(null));
    }

    /**
     * Compiles {@code source} (package {@code com.actbot}) into a fresh classpath dir with {@code stored}
     * (when non-null) as {@code /activities.json}, then loads and initializes {@code com.actbot.Activities}.
     */
    private Class<?> compileAndLoad(Path root, String source, String stored) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes-" + Math.abs(source.hashCode())));
        Path srcDir = Files.createDirectories(root.resolve("src-" + Math.abs(source.hashCode()) + "/com/actbot"));
        Path srcFile = srcDir.resolve("Activities.java");
        Files.writeString(srcFile, source);
        if (stored != null) Files.writeString(classes.resolve(ActivitiesConfig.FILE_NAME), stored);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                srcFile.toString());
        assertEquals(0, rc, "generated Activities.java should compile:\n" + source);

        // A loader with NO parent but the bootstrap one, over this project's classes first and everything
        // else after. Both halves of that matter now that the reading is the SDK's rather than a text block:
        // the SDK's ConfigStore resolves /activities.json through *its own* loader and caches it in a static,
        // so an ordinary child loader would delegate ConfigStore to the test classpath — where the fixture
        // written below is not — and then hold the first test's answer for every test after it. Isolating the
        // whole SDK per case gives each one its own ConfigStore, reading its own file.
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), null);
        return Class.forName("com.actbot.Activities", true, loader); // triggers static init
    }
}
