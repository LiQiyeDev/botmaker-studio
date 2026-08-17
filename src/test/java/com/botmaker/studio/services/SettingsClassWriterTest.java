package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code Settings} class is the store, so these tests do not check text — they compile it with a
 * real {@code javac} and read the values back by reflection. A generated file that parses but assigns the
 * wrong thing is exactly the failure a string comparison would miss.
 */
class SettingsClassWriterTest {

    private static final String PKG = "setbot";

    private static List<Setting> sample() {
        return List.of(
                Setting.enableFlag("Mining", true),
                Setting.create("GIVE_UP_AFTER", SettingType.DURATION, "Mining").withValue("90s")
                        .withLabel("Give up after").withVisibility(ParamVisibility.PUBLIC),
                Setting.create("ORE", SettingType.CHOICE, "Mining")
                        .withOptions(List.of("Iron", "Gold", "Coal")).withValue("Gold"),
                Setting.create("RETRIES", SettingType.INT, "Mining")
                        .withBounds(new Setting.Bounds("1", "10", "1")).withValue("3"),
                Setting.create("RATIO", SettingType.DOUBLE, "").withValue("0.75"),
                Setting.create("GREETING", SettingType.TEXT, "").withValue("say \"hi\"\nplease"),
                Setting.create("START_AT", SettingType.TIME, "").withValue("07:30"),
                Setting.create("EXPIRES", SettingType.DATE, "").withValue("2026-08-17"),
                Setting.create("SKILLS", SettingType.MULTI_CHOICE, "")
                        .withOptions(List.of("mine", "fish", "cook")).withValues(List.of("mine", "cook")),
                Setting.create("JUMP", SettingType.KEY, ""),
                Setting.create("CLICK_WITH", SettingType.MOUSE_BUTTON, ""));
    }

    // ---- the values survive the trip through Java ------------------------------------------------------

    @Test
    void everyTypeCompilesToTheValueItWasGiven(@TempDir Path root) throws Exception {
        Class<?> settings = compile(root, sample());

        assertTrue(settings.getField("Mining").getBoolean(null));
        assertEquals(Duration.ofSeconds(90), settings.getField("GIVE_UP_AFTER").get(null));
        assertEquals("Gold", settings.getField("ORE").get(null));
        assertEquals(3, settings.getField("RETRIES").getInt(null));
        assertEquals(0.75d, settings.getField("RATIO").getDouble(null));
        assertEquals("say \"hi\"\nplease", settings.getField("GREETING").get(null),
                "a quote and a newline the editor typed must survive escaping");
        assertEquals(LocalTime.of(7, 30), settings.getField("START_AT").get(null));
        assertEquals(LocalDate.of(2026, 8, 17), settings.getField("EXPIRES").get(null));
        assertEquals(List.of("mine", "cook"), settings.getField("SKILLS").get(null));
        assertNotNull(settings.getField("JUMP").get(null), "a Key resolves to a real enum constant");
        assertNotNull(settings.getField("CLICK_WITH").get(null));
    }

    /**
     * The trap this whole file shape exists to avoid. An inline initializer would make each field a JLS
     * §4.12.4 constant variable, javac would fold it, and a loop guarded by a flag turned off in the Runner
     * would become an {@code unreachable statement} <em>compile error</em> — a bot that stops compiling
     * because its user unticked a box.
     */
    @Test
    void aBotLoopingOnAFalseFlagStillCompiles(@TempDir Path root) throws Exception {
        List<Setting> settings = List.of(Setting.create("KEEP_GOING", SettingType.BOOL, "").withValue("false"));
        String bot = """
                package com.%s;

                public final class Bot {
                    public static int spin() {
                        int laps = 0;
                        while (Settings.KEEP_GOING) {
                            laps++;
                        }
                        return laps;
                    }
                }
                """.formatted(PKG);

        Class<?> loaded = compile(root, settings, bot);

        assertFalse(loaded.getField("KEEP_GOING").getBoolean(null));
    }

    /** Nothing is read at startup any more — that is the whole point of moving the values into Java. */
    @Test
    void theGeneratedClassReadsNothingAtRuntime() {
        String source = SettingsClassWriter.settingsSource(PKG, sample());

        assertFalse(source.contains("JsonNode"), "no Jackson");
        assertFalse(source.contains("ObjectMapper"), "no Jackson");
        assertFalse(source.contains("getResourceAsStream"), "nothing read off the classpath");
        assertFalse(source.contains("import "), "fully-qualified throughout, so no import can be forgotten");
    }

    /** Every field blank-final, every assignment in the static block. Stated as text because it is a shape. */
    @Test
    void everyFieldIsABlankFinalAssignedInTheStaticBlock() {
        String source = SettingsClassWriter.settingsSource(PKG, sample());
        String fields = source.substring(0, source.indexOf("static {"));

        for (String line : fields.lines().toList()) {
            if (!line.contains("public static final ")) continue;
            assertFalse(line.contains("="), "an inline initializer would be folded by javac: " + line.trim());
        }
        for (Setting setting : sample()) {
            assertTrue(source.contains("        " + setting.name() + " = "),
                    setting.name() + " is not assigned in the static block");
        }
    }

    // ---- the annotation is what Studio reads back -----------------------------------------------------

    @Test
    void theAnnotationCarriesEverythingStudioNeedsToReadTheSettingBack(@TempDir Path root) throws Exception {
        Class<?> settings = compile(root, sample());

        Annotation duration = annotationOn(settings, "GIVE_UP_AFTER");
        assertEquals("DURATION", element(duration, "type"));
        assertEquals("Mining", element(duration, "tag"));
        assertEquals("Give up after", element(duration, "label"));
        assertEquals(true, element(duration, "shared"));
        assertEquals("1m30s", element(duration, "value"), "the canonical wire form, not what was typed");

        Annotation retries = annotationOn(settings, "RETRIES");
        assertEquals("1", element(retries, "min"));
        assertEquals("10", element(retries, "max"));
        assertEquals("1", element(retries, "step"));

        Annotation ore = annotationOn(settings, "ORE");
        assertArrayIs(List.of("Iron", "Gold", "Coal"), element(ore, "options"));
        assertEquals("Gold", element(ore, "value"));

        Annotation skills = annotationOn(settings, "SKILLS");
        assertArrayIs(List.of("mine", "cook"), element(skills, "values"));
        assertEquals("", element(skills, "value"), "a multi-valued setting says nothing in value()");
    }

    /**
     * KEY and MOUSE_BUTTON take their choices from the SDK's own enum, so writing them out would freeze a copy
     * of that list into every project — the exact hand-mirroring {@code SdkType} exists to prevent.
     */
    @Test
    void theSdkEnumTypesDoNotCopyTheirOptionsIntoTheFile(@TempDir Path root) throws Exception {
        Class<?> settings = compile(root, sample());

        assertArrayIs(List.of(), element(annotationOn(settings, "JUMP"), "options"));
    }

    @Test
    void whatIsAtItsDefaultIsLeftOut() {
        String line = SettingsClassWriter.annotationFor(Setting.create("RETRIES", SettingType.INT, ""));

        assertEquals("@Setting(type = \"INT\", value = \"0\")", line);
    }

    // ---- the file's shape -----------------------------------------------------------------------------

    /** Grouped so the file reads in the order the dialog that produced it shows. */
    @Test
    void settingsAreGroupedByTagInFirstAppearanceOrder() {
        String source = SettingsClassWriter.settingsSource(PKG, sample());

        assertTrue(source.indexOf("// Mining") < source.indexOf("// General"));
        // On the declarations, not the names: "DURATION" contains "RATIO", and an indexOf over the whole file
        // finds the annotation of one setting before the declaration of the other.
        assertTrue(source.indexOf(" GIVE_UP_AFTER;") < source.indexOf(" RATIO;"),
                "a General setting declared between two Mining ones still lands with General");
    }

    /**
     * A repeated or unusable name is a compile error in a file nobody can fix by hand, so it is dropped rather
     * than emitted. The model refuses to make one; this is the generator refusing to trust that.
     */
    @Test
    void aNameThatCannotBeAFieldIsSkippedRatherThanEmitted(@TempDir Path root) throws Exception {
        List<Setting> settings = new ArrayList<>(List.of(
                Setting.create("KEEP", SettingType.INT, "").withValue("1"),
                Setting.create("KEEP", SettingType.TEXT, "").withValue("duplicate"),
                Setting.create("2fast", SettingType.INT, "")));

        Class<?> loaded = compile(root, settings);

        assertEquals(1, loaded.getField("KEEP").getInt(null), "the first of a repeated name wins");
        assertEquals(1, loaded.getDeclaredFields().length, "only the one legal, unrepeated field");
    }

    /** A project with nothing set still generates the class: a hand-written import has to keep compiling. */
    @Test
    void anEmptyProjectStillGeneratesACompilableClass(@TempDir Path root) throws Exception {
        assertEquals(0, compile(root, List.of()).getDeclaredFields().length);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private Class<?> compile(Path root, List<Setting> settings, String... extraSources) throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path srcDir = Files.createDirectories(root.resolve("src/com/" + PKG));

        List<String> files = new ArrayList<>();
        files.add(write(srcDir.resolve("Setting.java"), SettingsClassWriter.annotationSource(PKG)));
        files.add(write(srcDir.resolve("Settings.java"), SettingsClassWriter.settingsSource(PKG, settings)));
        for (String extra : extraSources) {
            files.add(write(srcDir.resolve(publicClassNameOf(extra) + ".java"), extra));
        }
        // Templates.java: a TEMPLATE setting compiles to Templates.<CONST>, and an empty one is what a project
        // with no images has. None of the sample settings is a template, so the class only has to exist.
        files.add(write(srcDir.resolve("Templates.java"),
                com.botmaker.studio.project.TemplateConstants.generateSource(PKG, List.of())));

        List<String> args = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        args.addAll(files);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the generated settings should compile");

        URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        return Class.forName("com." + PKG + ".Settings", true, loader);
    }

    /** The name a public class has to be filed under — javac refuses any other. */
    private static String publicClassNameOf(String source) {
        Matcher matcher = Pattern.compile("public\\s+(?:final\\s+)?class\\s+(\\w+)").matcher(source);
        assertTrue(matcher.find(), "no public class in the extra source");
        return matcher.group(1);
    }

    private static String write(Path file, String source) throws Exception {
        Files.writeString(file, source);
        return file.toString();
    }

    private static Annotation annotationOn(Class<?> settings, String fieldName) throws Exception {
        Field field = settings.getField(fieldName);
        for (Annotation a : field.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("Setting")) return a;
        }
        throw new AssertionError(fieldName + " carries no @Setting");
    }

    private static Object element(Annotation annotation, String name) throws Exception {
        return annotation.annotationType().getMethod(name).invoke(annotation);
    }

    private static void assertArrayIs(List<String> expected, Object actual) {
        assertEquals(expected, List.of((String[]) actual));
    }
}
