package com.botmaker.studio.services;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.BreakKind;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The upgrade report: given the bot's current SDK jar and the one it might move to, what would break.
 *
 * <p>Two real jars are compiled here rather than mocked, for the reason the whole feature exists: the
 * question is about bytecode, and the interesting cases — a method gone, an overload's arity changed, a class
 * that disappeared, a survivor now {@code @Deprecated} — are exactly the ones no pair of published SDK
 * versions exhibits yet. A fixture that only ever encodes what already shipped would pass forever without
 * proving the diff works.
 *
 * <p>The jars get unique names per call: {@link TypeSummaryManager}'s disk cache is keyed by jar
 * <em>filename</em> and settles ties by mtime, so two same-named fixtures written in the same millisecond
 * would have the second one read the first one's scan.
 */
class SdkUpgradeServiceTest {

    private static final String PKG = "com.botmaker.sdk.api";
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    // -------------------------------------------------------------------------
    // The two fixture SDKs
    // -------------------------------------------------------------------------

    /**
     * The version the bot is on: four facades, everything the bot calls present and live.
     */
    private static Map<String, String> oldSdk() {
        return Map.of(
                "Mouse", """
                        package %s;
                        public class Mouse {
                            public static void click(int x, int y) {}
                            public static void doubleClick(int x, int y) {}
                        }
                        """.formatted(PKG),
                "Wait", """
                        package %s;
                        public class Wait {
                            public static void seconds(int s) {}
                            public static void time(long ms) {}
                        }
                        """.formatted(PKG),
                "Legacy", """
                        package %s;
                        public class Legacy {
                            public static void anything() {}
                        }
                        """.formatted(PKG),
                "Point", """
                        package %s;
                        public class Point {
                            public Point(int x, int y) {}
                        }
                        """.formatted(PKG));
    }

    /**
     * The version it might move to. One of each kind of change: {@code Mouse.click} gained a required third
     * argument, {@code Wait.seconds} is gone, {@code Wait.time} is deprecated, {@code Legacy} does not exist,
     * {@code Mouse.dragTo} and {@code Session} are new, {@code Point} and {@code Mouse.doubleClick} are
     * untouched.
     */
    private static Map<String, String> newSdk() {
        return Map.of(
                "Mouse", """
                        package %s;
                        public class Mouse {
                            public static void click(int x, int y, long delayMs) {}
                            public static void doubleClick(int x, int y) {}
                            public static void dragTo(int x, int y) {}
                        }
                        """.formatted(PKG),
                "Wait", """
                        package %s;
                        public class Wait {
                            @Deprecated(since = "2.0.0", forRemoval = true)
                            public static void time(long ms) {}
                        }
                        """.formatted(PKG),
                "Session", """
                        package %s;
                        public class Session {
                            public static void open() {}
                        }
                        """.formatted(PKG),
                "Point", """
                        package %s;
                        public class Point {
                            public Point(int x, int y) {}
                        }
                        """.formatted(PKG));
    }

    /** Compiles {@code classes} into a jar, optionally carrying the two {@code META-INF} files. */
    private static Path jarOf(Path dir, String label, Map<String, String> classes,
                              Map<String, String> resources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jars cannot be built");

        Path src = dir.resolve("src-" + label).resolve(PKG.replace('.', '/'));
        Files.createDirectories(src);
        List<String> paths = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : classes.entrySet()) {
            Path file = src.resolve(e.getKey() + ".java");
            Files.writeString(file, e.getValue());
            paths.add(file.toString());
        }

        Path out = dir.resolve("classes-" + label);
        Files.createDirectories(out);
        List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString()));
        args.addAll(paths);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the " + label + " fixture sources must compile");

        for (Map.Entry<String, String> e : resources.entrySet()) {
            Path file = out.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }

        Path jar = dir.resolve("botmaker-sdk-" + label + "-" + UNIQUE.incrementAndGet() + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jar;
    }

    // -------------------------------------------------------------------------
    // The bot
    // -------------------------------------------------------------------------

    /** A bot that touches every case: two breaks, a deprecation, a constructor, and one untouched call. */
    private static final String BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    Mouse.click(10, 20);
                    Mouse.doubleClick(10, 20);
                    Wait.seconds(3);
                    Wait.time(500);
                    Legacy.anything();
                    Point p = new Point(1, 2);
                }
            }
            """;

    private static SdkUpgradeService serviceOver(Path tmp, String... sources) throws IOException {
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/com/mybot"));

        ProjectState state = new ProjectState();
        int n = 0;
        for (String source : sources) {
            Path file = project.resolve("src/main/java/com/mybot/Subject" + (n == 0 ? "" : n) + ".java");
            Files.writeString(file, source);
            state.addFile(new ProjectFile(file, source));
            n++;
        }

        ProjectConfig config = ProjectConfig.forProject("fixture", project);
        EventBus bus = new EventBus(false);
        LibraryService libraries = new LibraryService(config, state, new TypeSummaryManager(), bus);
        return new SdkUpgradeService(config, state, libraries, new JitPackSearch());
    }

    private static Report reportFor(Path tmp, String... sources) throws IOException {
        SdkUpgradeService service = serviceOver(tmp, sources);
        Path oldJar = jarOf(tmp, "old", oldSdk(), Map.of());
        Path newJar = jarOf(tmp, "new", newSdk(), Map.of());
        return service.compare(oldJar, newJar, "1.0.0", "2.0.0");
    }

    private static Optional<Break> brk(Report r, String display) {
        return r.breaks().stream().filter(b -> b.display().equals(display)).findFirst();
    }

    // -------------------------------------------------------------------------
    // What breaks
    // -------------------------------------------------------------------------

    @Test
    void aRemovedMethodIsReportedWithItsCallSite(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        Break gone = brk(r, "Wait.seconds").orElseThrow(
                () -> new AssertionError("Wait.seconds is gone on the target and the bot calls it: " + r.breaks()));
        assertEquals(BreakKind.MEMBER_REMOVED, gone.kind());
        assertEquals(1, gone.sites().size());
        assertEquals(6, gone.sites().get(0).line(), "the line the call is actually written on");
        assertTrue(gone.sites().get(0).file().endsWith("Subject.java"),
                "the path must be the user's, project-relative: " + gone.sites().get(0).file());
    }

    @Test
    void aClassThatDisappearedIsAReportedBreak(@TempDir Path tmp) throws IOException {
        // Reported against the type, not each member of it: the question the user has to answer is what
        // replaces Legacy, and one line per method it happened to call is noise around that one decision.
        Report r = reportFor(tmp, BOT);

        Break gone = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_REMOVED, gone.kind());
        assertEquals(8, gone.sites().get(0).line(), "the line Legacy.anything() is on");
    }

    @Test
    void anOverloadThatGainedARequiredArgumentBreaksAndSaysWhat(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        Break changed = brk(r, "Mouse.click").orElseThrow();
        assertEquals(BreakKind.SIGNATURE_CHANGED, changed.kind());
        assertTrue(changed.detail().contains("click(int, int)"), "names the old shape: " + changed.detail());
        assertTrue(changed.detail().contains("click(int, int, long)"),
                "and the new one, so the user can see what to add: " + changed.detail());
    }

    @Test
    void callsThatStillCompileAreNotReported(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertTrue(brk(r, "Mouse.doubleClick").isEmpty(), "unchanged method reported as a break");
        assertTrue(brk(r, "new Point").isEmpty(), "unchanged constructor reported as a break");
        // The bot's own class is not the SDK's, and must not be judged against it.
        assertTrue(brk(r, "Subject.run").isEmpty());
    }

    @Test
    void aConstructorIsACallSiteLikeAnyOther(@TempDir Path tmp) throws IOException {
        // Point survives here, so the proof is the other direction: drop it from the target and the
        // `new Point(1, 2)` in the bot must be found. A constructor scanning as zero call sites is the
        // failure mode MethodReferences was written to close, and it is just as wrong here.
        SdkUpgradeService service = serviceOver(tmp, BOT);
        Map<String, String> without = new java.util.HashMap<>(newSdk());
        without.remove("Point");
        Report r = service.compare(jarOf(tmp, "old", oldSdk(), Map.of()),
                jarOf(tmp, "new", without, Map.of()), "1.0.0", "2.0.0");

        // A withdrawn type is reported once, against the type — so the proof the constructor was scanned at
        // all is that its line is among the sites.
        Break gone = brk(r, "Point").orElseThrow(
                () -> new AssertionError("the constructor call was not found: " + r.breaks()));
        assertEquals(BreakKind.TYPE_REMOVED, gone.kind());
        assertEquals(9, gone.sites().get(0).line());
    }

    // -------------------------------------------------------------------------
    // Deprecation, additions
    // -------------------------------------------------------------------------

    @Test
    void aSurvivingButDeprecatedCallIsNoticeNotABreak(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertTrue(brk(r, "Wait.time").isEmpty(), "a deprecated call still compiles — it is not a break");
        assertEquals(List.of("Wait.time"), r.deprecated().stream().map(Deprecation::display).toList());
    }

    @Test
    void whatIsNewListsAddedClassesAndMembers(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertTrue(r.added().contains("Session (new class)"), r.added().toString());
        assertTrue(r.added().contains("Mouse.dragTo(…)"), r.added().toString());
        assertFalse(r.added().contains("Point (new class)"), "an unchanged class is not new");
    }

    // -------------------------------------------------------------------------
    // Fields, constants and enum constants
    // -------------------------------------------------------------------------

    /** The constants fixture, before: two enums and a class of constants, all present. */
    private static Map<String, String> oldConstants() {
        Map<String, String> m = new java.util.HashMap<>(oldSdk());
        m.put("Key", "package %s; public enum Key { ENTER, ESCAPE, TAB }".formatted(PKG));
        m.put("Direction", "package %s; public enum Direction { UP, DOWN }".formatted(PKG));
        m.put("Precision", """
                package %s;
                public class Precision {
                    public static final int TIGHT = 1;
                    public static final int LOOSE = 2;
                }
                """.formatted(PKG));
        return m;
    }

    /** After: {@code Key.ENTER}, {@code Key.ESCAPE}, {@code Direction.UP} and {@code Precision.TIGHT} gone. */
    private static Map<String, String> newConstants() {
        Map<String, String> m = new java.util.HashMap<>(newSdk());
        m.put("Key", "package %s; public enum Key { TAB }".formatted(PKG));
        m.put("Direction", "package %s; public enum Direction { DOWN }".formatted(PKG));
        m.put("Precision", """
                package %s;
                public class Precision {
                    public static final int LOOSE = 2;
                }
                """.formatted(PKG));
        return m;
    }

    /** All three shapes a constant is written in: qualified, statically imported, and a {@code case} label. */
    private static final String CONSTANTS_BOT = """
            package com.mybot;
            import static com.botmaker.sdk.api.Key.ESCAPE;
            public class Subject {
                public void run() {
                    Object enter = Key.ENTER;
                    int tight = Precision.TIGHT;
                    Object esc = ESCAPE;
                    Direction d = Direction.DOWN;
                    switch (d) {
                        case UP -> {}
                        default -> {}
                    }
                }
            }
            """;

    private static Report constantsReport(Path tmp, Map<String, String> after) throws IOException {
        SdkUpgradeService service = serviceOver(tmp, CONSTANTS_BOT);
        return service.compare(jarOf(tmp, "old", oldConstants(), Map.of()),
                jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");
    }

    @Test
    void aDeletedEnumConstantIsABreakWhereverItIsWritten(@TempDir Path tmp) throws IOException {
        Report r = constantsReport(tmp, newConstants());

        Break enter = brk(r, "Key.ENTER").orElseThrow(
                () -> new AssertionError("a deleted enum constant is as breaking as a deleted method: "
                        + r.breaks()));
        assertEquals(BreakKind.FIELD_REMOVED, enter.kind());
        assertEquals(5, enter.sites().get(0).line(), "the qualified read's own line");

        assertEquals(BreakKind.FIELD_REMOVED, brk(r, "Precision.TIGHT").orElseThrow().kind());
        assertEquals(6, brk(r, "Precision.TIGHT").orElseThrow().sites().get(0).line());
    }

    @Test
    void aStaticallyImportedConstantIsFoundUnderItsBareName(@TempDir Path tmp) throws IOException {
        Report r = constantsReport(tmp, newConstants());

        Break escape = brk(r, "Key.ESCAPE").orElseThrow(
                () -> new AssertionError("the bare ESCAPE reaches the static import: " + r.breaks()));
        assertEquals(7, escape.sites().get(0).line(),
                "attributed to the use, not to the import line");
    }

    @Test
    void aCaseLabelIsAConstantUseEvenThoughItsTypeIsNotWrittenThere(@TempDir Path tmp) throws IOException {
        Report r = constantsReport(tmp, newConstants());

        Break up = brk(r, "Direction.UP").orElseThrow(
                () -> new AssertionError("`case UP ->` is a use of Direction.UP: " + r.breaks()));
        assertEquals(BreakKind.FIELD_REMOVED, up.kind());
        assertEquals(10, up.sites().get(0).line());
        // The other label in the same switch survives, and must not be dragged in with it.
        assertTrue(brk(r, "Direction.DOWN").isEmpty(), r.breaks().toString());
    }

    @Test
    void aCaseLabelTwoSdkTypesCouldOwnIsAnUnansweredQuestionNotAGuess(@TempDir Path tmp) throws IOException {
        // A second enum declaring UP makes the label ambiguous: without bindings the switch expression's
        // type is unreadable, and naming the wrong class is worse than admitting it.
        Map<String, String> before = new java.util.HashMap<>(oldConstants());
        before.put("Axis", "package %s; public enum Axis { UP, SIDEWAYS }".formatted(PKG));
        SdkUpgradeService service = serviceOver(tmp, CONSTANTS_BOT);
        Report r = service.compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", newConstants(), Map.of()), "1.0.0", "2.0.0");

        assertTrue(brk(r, "Direction.UP").isEmpty(), "guessed an owner it could not know: " + r.breaks());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("UP") && p.contains("Axis")),
                r.problems().toString());
        assertTrue(r.isIncomplete());
    }

    @Test
    void aBotUsingOnlyConstantsNoLongerReportsThatNothingBreaks(@TempDir Path tmp) throws IOException {
        // The regression this phase exists for: before fields were scanned, a release deleting every
        // constant this bot reads answered "nothing breaks".
        SdkUpgradeService service = serviceOver(tmp, CONSTANTS_BOT);
        Report r = service.compare(jarOf(tmp, "old", oldConstants(), Map.of()),
                jarOf(tmp, "new", newConstants(), Map.of()), "1.0.0", "2.0.0");

        assertFalse(r.nothingBreaks());
        assertFalse(r.breaks().isEmpty());
    }

    @Test
    void aConstantThatSurvivedIsNotABreakAndANewOneReadsAsAValue(@TempDir Path tmp) throws IOException {
        Map<String, String> after = new java.util.HashMap<>(newConstants());
        after.put("Precision", """
                package %s;
                public class Precision {
                    public static final int LOOSE = 2;
                    public static final int EXACT = 3;
                }
                """.formatted(PKG));
        Report r = constantsReport(tmp, after);

        assertTrue(brk(r, "Precision.LOOSE").isEmpty(), "an untouched constant is not a break");
        assertTrue(r.added().contains("Precision.EXACT"),
                "a constant is read as a value, not called: " + r.added());
        assertFalse(r.added().contains("Precision.EXACT(…)"), r.added().toString());
    }

    // -------------------------------------------------------------------------
    // Honesty about what could not be read
    // -------------------------------------------------------------------------

    @Test
    void aFileThatDoesNotParseIsNamedRatherThanSkipped(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT, "package com.mybot; class Broken { void run( {");

        assertTrue(r.isIncomplete(), "an unparseable file must show up as an unanswered question");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("Subject1.java")), r.problems().toString());
        // The point of the distinction: breaks were still found, but "nothing breaks" is not claimed.
        assertFalse(r.breaks().isEmpty());
        assertFalse(r.nothingBreaks());
    }

    @Test
    void anUnchangedSdkBreaksNothingAndSaysSoCleanly(@TempDir Path tmp) throws IOException {
        SdkUpgradeService service = serviceOver(tmp, BOT);
        Report r = service.compare(jarOf(tmp, "old", oldSdk(), Map.of()),
                jarOf(tmp, "same", oldSdk(), Map.of()), "1.0.0", "1.0.1");

        assertTrue(r.nothingBreaks(), "breaks=" + r.breaks() + " problems=" + r.problems());
        assertTrue(r.deprecated().isEmpty());
        assertTrue(r.added().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Pairing: which element in the new jar takes the old one's place
    // -------------------------------------------------------------------------

    /**
     * The two pointer annotations themselves, compiled into whichever fixture jar wants them. CLASS retention
     * is the default, which is exactly what the real ones declare — and what makes them readable off a jar.
     */
    private static Map<String, String> withPointers(Map<String, String> base) {
        Map<String, String> out = new java.util.HashMap<>(base);
        out.put("ReplacedBy", """
                package %s;
                public @interface ReplacedBy { String value() default ""; }
                """.formatted(PKG));
        out.put("Replaces", """
                package %s;
                public @interface Replaces { String[] value(); }
                """.formatted(PKG));
        return out;
    }

    /**
     * The old jar with {@code Legacy} carrying {@code legacyAnnotation}, and the new jar with
     * {@code newClasses} added to it. Everything else is the standard fixture pair.
     */
    private static Report pointerReport(Path tmp, String legacyAnnotation, Map<String, String> newClasses,
                                        String from, String to) throws IOException {
        Map<String, String> before = withPointers(oldSdk());
        before.put("Legacy", """
                package %s;
                %s
                public class Legacy {
                    public static void anything() {}
                }
                """.formatted(PKG, legacyAnnotation));
        Map<String, String> after = withPointers(newSdk());
        after.putAll(newClasses);
        return serviceOver(tmp, BOT).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", after, Map.of()), from, to);
    }

    /** The class that took {@code Legacy}'s place, carrying whatever backward pointer the test wants. */
    private static String modern(String annotation) {
        return """
                package %s;
                %s
                public class Modern {
                    public static void anything() {}
                }
                """.formatted(PKG, annotation);
    }

    private static void assertPaired(Report r) {
        Break renamed = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_RENAMED, renamed.kind(), r.breaks() + " " + r.problems());
        assertTrue(renamed.detail().contains("Modern"), renamed.detail());
        assertTrue(renamed.isRepairable());
        assertTrue(r.canMigrate(), "a paired rename is repairable: " + r.breaks() + " " + r.problems());
    }

    @Test
    void theOldJarsForwardPointerAloneIsEnoughToPairARename(@TempDir Path tmp) throws IOException {
        // The bot spells the type the old way, so the old jar — the one it is pinned to — is the first place
        // worth asking, and on its own it answers.
        assertPaired(pointerReport(tmp, "@ReplacedBy(\"com.botmaker.sdk.api.Modern\")",
                Map.of("Modern", modern("")), "1.0.0", "2.0.0"));
    }

    @Test
    void theNewJarsBackwardPointerAloneIsEnoughToo(@TempDir Path tmp) throws IOException {
        // The half that survives the deletion: once Legacy is finally removed, @Replaces on the survivor is
        // the only remaining record that it was ever called that.
        assertPaired(pointerReport(tmp, "",
                Map.of("Modern", modern("@Replaces(\"com.botmaker.sdk.api.Legacy@1.5.0\")")),
                "1.0.0", "2.0.0"));
    }

    @Test
    void bothEndsAgreeingIsStillOneAnswer(@TempDir Path tmp) throws IOException {
        assertPaired(pointerReport(tmp, "@ReplacedBy(\"com.botmaker.sdk.api.Modern\")",
                Map.of("Modern", modern("@Replaces(\"com.botmaker.sdk.api.Legacy@1.5.0\")")),
                "1.0.0", "2.0.0"));
    }

    @Test
    void thePointersComposeIntoAChainWithNoIntermediateJar(@TempDir Path tmp) throws IOException {
        // The case the two halves exist for. `Legacy → Middle` was announced by the release the bot is on;
        // `Middle → Modern` by a later one. Middle is in NEITHER jar in hand, and the bot still says Legacy.
        assertPaired(pointerReport(tmp, "@ReplacedBy(\"com.botmaker.sdk.api.Middle\")",
                Map.of("Modern", modern("@Replaces(\"com.botmaker.sdk.api.Middle@2.5.0\")")),
                "1.0.0", "3.0.0"));
    }

    @Test
    void anEntryFromBeforeTheBotsOwnVersionIsNotAboutThisBot(@TempDir Path tmp) throws IOException {
        // The entry records the last release in which the old spelling existed. A bot pinned after that never
        // wrote it that way, so its `Legacy` is a different Legacy — reintroduced and removed again — and
        // pairing the two would be the invented answer the design refuses.
        Report r = pointerReport(tmp, "",
                Map.of("Modern", modern("@Replaces(\"com.botmaker.sdk.api.Legacy@0.9.0\")")),
                "1.0.0", "2.0.0");

        assertEquals(BreakKind.TYPE_REMOVED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
    }

    @Test
    void aPointerToSomethingTheTargetDoesNotHaveIsNoPointerAtAll(@TempDir Path tmp) throws IOException {
        Report r = pointerReport(tmp, "@ReplacedBy(\"com.botmaker.sdk.api.Ghost\")",
                Map.of("Modern", modern("")), "1.0.0", "2.0.0");

        assertEquals(BreakKind.TYPE_REMOVED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
    }

    @Test
    void anEmptyPointerIsTheAuthorSayingNothingTakesItsPlace(@TempDir Path tmp) throws IOException {
        // Not an omission — the bare annotation is required on every deprecated element, and it reads as an
        // answer. javac writes no value element for it, so this also proves an absent value is not misread
        // as an absent annotation.
        Report r = pointerReport(tmp, "@ReplacedBy", Map.of("Modern", modern("")), "1.0.0", "2.0.0");

        assertEquals(BreakKind.TYPE_REMOVED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
    }

    @Test
    void anOldNameTwoSurvivorsBothClaimIsAQuestionNotAGuess(@TempDir Path tmp) throws IOException {
        Report r = pointerReport(tmp, "", Map.of(
                        "Modern", modern("@Replaces(\"com.botmaker.sdk.api.Legacy@1.5.0\")"),
                        "Other", """
                                package %s;
                                @Replaces("com.botmaker.sdk.api.Legacy@1.5.0")
                                public class Other {
                                    public static void anything() {}
                                }
                                """.formatted(PKG)),
                "1.0.0", "2.0.0");

        assertEquals(BreakKind.TYPE_REMOVED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("Legacy") && p.contains("Modern")),
                "the user is told why it could not be answered: " + r.problems());
        assertFalse(r.canMigrate());
    }

    @Test
    void aPointerPairsThatOneElementOnlySoAMemberThatWentIsStillABreakOfItsOwn(@TempDir Path tmp)
            throws IOException {
        // The rule that keeps a pointer kept across a redesign from becoming a silently wrong rewrite: the
        // class paired, and the members were still resolved one at a time against it.
        Report r = pointerReport(tmp, "@ReplacedBy(\"com.botmaker.sdk.api.Modern\")", Map.of("Modern", """
                package %s;
                public class Modern {
                    public static void somethingElse() {}
                }
                """.formatted(PKG)), "1.0.0", "2.0.0");

        assertEquals(BreakKind.TYPE_RENAMED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
        Break gone = brk(r, "Legacy.anything").orElseThrow(
                () -> new AssertionError("the member is still judged on its own: " + r.breaks()));
        assertEquals(BreakKind.MEMBER_REMOVED, gone.kind());
        assertTrue(r.canMigrate(), "both halves are repairable — a rename and a default value");
    }

    @Test
    void aMemberPairsOnItsOwnPointerWithTheTypeUntouched(@TempDir Path tmp) throws IOException {
        // What @ApiId could never express: the type did not move at all, one method on it was renamed.
        Map<String, String> before = withPointers(oldSdk());
        before.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                    @Deprecated
                    @ReplacedBy("com.botmaker.sdk.api.Mouse#twoClicks")
                    public static void doubleClick(int x, int y) {}
                }
                """.formatted(PKG));
        Map<String, String> after = withPointers(newSdk());
        after.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y, long delayMs) {}
                    @Replaces("com.botmaker.sdk.api.Mouse#doubleClick@1.5.0")
                    public static void twoClicks(int x, int y) {}
                }
                """.formatted(PKG));
        Report r = serviceOver(tmp, BOT).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");

        assertTrue(brk(r, "Mouse.doubleClick").isEmpty(),
                "a member with a pointer that resolves is a rename, not a removal: " + r.breaks());
        // And the type itself was never in question: the other break on Mouse is still reported.
        assertEquals(BreakKind.SIGNATURE_CHANGED, brk(r, "Mouse.click").orElseThrow().kind());
    }

    @Test
    void aRemovedTypeWithNoPointerIsTheOneBreakThatRefusesTheUpgrade(@TempDir Path tmp) throws IOException {
        // Absence of a pointer IS the signal. A default value has nowhere to go in `Legacy l = …;`, and
        // Object would be silently wrong, so this is the one case the model cannot repair.
        Report r = reportFor(tmp, BOT);

        Break gone = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_REMOVED, gone.kind());
        assertFalse(gone.isRepairable());
        assertFalse(r.canMigrate(), "one unpaired removed type disables the whole span");
        assertEquals(List.of(gone), r.unrepairable());
    }

    @Test
    void aTargetThatPointsNowhereIsNotATargetThatFailedToBeRead(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertFalse(r.problems().stream().anyMatch(p -> p.contains("claimed")),
                "no pointers at all is silence, not a problem: " + r.problems());
    }
}
