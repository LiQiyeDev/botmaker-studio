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
    // What the SDK jar itself says
    // -------------------------------------------------------------------------

    /** Builds a target jar carrying {@code json} as its migrations file, and reports against it. */
    private static Report reportWithMigrations(Path tmp, String from, String to, String json)
            throws IOException {
        SdkUpgradeService service = serviceOver(tmp, BOT);
        return service.compare(
                jarOf(tmp, "old", oldSdk(), Map.of()),
                jarOf(tmp, "new", newSdk(), Map.of("META-INF/botmaker/migrations.json", json)),
                from, to);
    }

    @Test
    void declaredRenamesAreReadFromTheTargetJarAndScopedToTheVersionRange(@TempDir Path tmp)
            throws IOException {
        Report r = reportWithMigrations(tmp, "1.6.0", "2.0.0", """
                {"schema": 2, "versions": {
                  "1.5.0": [{"from": "com.botmaker.sdk.api.Old", "to": "com.botmaker.sdk.api.Older"}],
                  "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy", "to": "com.botmaker.sdk.api.Modern"}],
                  "3.0.0": [{"from": "com.botmaker.sdk.api.Future", "to": "com.botmaker.sdk.api.Later"}]
                }}
                """);

        assertEquals(List.of("2.0.0"), r.renames().stream().map(x -> x.version()).toList(),
                "only versions in (from, to] — an entry for a release the bot is already past is noise, and "
                        + "one for a release beyond the target has not happened to this bot yet");
    }

    // -------------------------------------------------------------------------
    // Pairing: which type in the new jar takes the old one's place
    // -------------------------------------------------------------------------

    /** {@code @ApiId} itself, compiled into whichever fixture jar wants it. CLASS retention is the default. */
    private static final String API_ID_SOURCE = """
            package %s;
            public @interface ApiId { String value(); }
            """.formatted(PKG);

    private static Map<String, String> sdkWith(Map<String, String> base, String name, String source) {
        Map<String, String> out = new java.util.HashMap<>(base);
        out.put("ApiId", API_ID_SOURCE);
        out.put(name, source);
        return out;
    }

    /** The old jar with {@code Legacy} carrying an id, and a new jar where the id sits on a renamed class. */
    private static Report pairingReport(Path tmp, String oldLegacyId, String newClass, String newClassSource,
                                        String migrationsJson) throws IOException {
        Map<String, String> before = sdkWith(oldSdk(), "Legacy", """
                package %s;
                %s
                public class Legacy {
                    public static void anything() {}
                }
                """.formatted(PKG, oldLegacyId));
        Map<String, String> after = sdkWith(newSdk(), newClass, newClassSource);
        Map<String, String> resources = migrationsJson == null
                ? Map.of()
                : Map.of("META-INF/botmaker/migrations.json", migrationsJson);
        return serviceOver(tmp, BOT).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", after, resources), "1.0.0", "2.0.0");
    }

    @Test
    void aKeptApiIdPairsARenamedClassWithNothingDeclaredAnywhere(@TempDir Path tmp) throws IOException {
        // The point of @ApiId: both releases spell the id the same way, so the rename is a fact read out of
        // the jars rather than something the SDK author had to remember to write down.
        Report r = pairingReport(tmp, "@ApiId(\"legacy\")", "Modern", """
                package %s;
                @ApiId("legacy")
                public class Modern {
                    public static void anything() {}
                }
                """.formatted(PKG), null);

        Break renamed = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_RENAMED, renamed.kind());
        assertTrue(renamed.detail().contains("Modern"), renamed.detail());
        assertTrue(renamed.isRepairable());
        assertTrue(r.canMigrate(), "a paired rename is repairable: " + r.breaks());
    }

    @Test
    void aDeclaredRenamePairsAClassThatCarriesNoId(@TempDir Path tmp) throws IOException {
        // v1.0.26 and earlier carry no ids at all, which is exactly what the rename file is the fallback for.
        Report r = pairingReport(tmp, "", "Modern", """
                package %s;
                public class Modern {
                    public static void anything() {}
                }
                """.formatted(PKG), """
                {"schema": 2, "versions": {
                  "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy", "to": "com.botmaker.sdk.api.Modern"}]
                }}
                """);

        assertEquals(BreakKind.TYPE_RENAMED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
        assertTrue(r.canMigrate());
    }

    @Test
    void anIdPairsTheTypeNameOnlySoAMemberThatWentIsStillABreakOfItsOwn(@TempDir Path tmp) throws IOException {
        // The rule that keeps a reused id from becoming a silently wrong rewrite: the class paired, and the
        // members were still resolved one at a time against it.
        Report r = pairingReport(tmp, "@ApiId(\"legacy\")", "Modern", """
                package %s;
                @ApiId("legacy")
                public class Modern {
                    public static void somethingElse() {}
                }
                """.formatted(PKG), null);

        assertEquals(BreakKind.TYPE_RENAMED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
        Break gone = brk(r, "Legacy.anything").orElseThrow(
                () -> new AssertionError("the member is still judged on its own: " + r.breaks()));
        assertEquals(BreakKind.MEMBER_REMOVED, gone.kind());
        assertTrue(r.canMigrate(), "both halves are repairable — a rename and a default value");
    }

    @Test
    void aRemovedTypeWithNoPairingIsTheOneBreakThatRefusesTheUpgrade(@TempDir Path tmp) throws IOException {
        // Absence of an id IS the signal. A default value has nowhere to go in `Legacy l = …;`, and Object
        // would be silently wrong, so this is the one case the model cannot repair.
        Report r = reportFor(tmp, BOT);

        Break gone = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_REMOVED, gone.kind());
        assertFalse(gone.isRepairable());
        assertFalse(r.canMigrate(), "one unpaired removed type disables the whole span");
        assertEquals(List.of(gone), r.unrepairable());
    }

    @Test
    void renamesComposeAcrossVersionsRatherThanBeingReplayed(@TempDir Path tmp) throws IOException {
        // A bot on 1.x has run neither pass, so its source still says `Legacy`. Only the composed fact
        // Legacy → Modern pairs it; following one link would stop at a class that never existed in either jar.
        Report r = pairingReport(tmp, "", "Modern", """
                package %s;
                public class Modern {
                    public static void anything() {}
                }
                """.formatted(PKG), """
                {"schema": 2, "versions": {
                  "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy", "to": "com.botmaker.sdk.api.Middle"}],
                  "3.0.0": [{"from": "com.botmaker.sdk.api.Middle", "to": "com.botmaker.sdk.api.Modern"}]
                }}
                """);
        // compare() above spans 1.0.0 → 2.0.0, so widen it: the composition is what is under test.
        Report full = serviceOver(tmp, BOT).compare(
                jarOf(tmp, "old2", sdkWith(oldSdk(), "Legacy", """
                        package %s;
                        public class Legacy { public static void anything() {} }
                        """.formatted(PKG)), Map.of()),
                jarOf(tmp, "new2", sdkWith(newSdk(), "Modern", """
                        package %s;
                        public class Modern { public static void anything() {} }
                        """.formatted(PKG)), Map.of("META-INF/botmaker/migrations.json", """
                        {"schema": 2, "versions": {
                          "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy",
                                     "to": "com.botmaker.sdk.api.Middle"}],
                          "3.0.0": [{"from": "com.botmaker.sdk.api.Middle",
                                     "to": "com.botmaker.sdk.api.Modern"}]
                        }}
                        """)),
                "1.0.0", "3.0.0");

        assertFalse(r.breaks().isEmpty(), "the narrow span still reports something");
        assertEquals(BreakKind.TYPE_RENAMED,
                brk(full, "Legacy").orElseThrow(() -> new AssertionError(full.breaks().toString())).kind());
        assertTrue(full.canMigrate());
    }

    @Test
    void aRenameUndoneByALaterVersionComposesToNothingRatherThanLooping(@TempDir Path tmp) throws IOException {
        // `a → b` then `b → a` is a legal pair of releases and an infinite loop for anything that re-runs a
        // set of passes until nothing changes. Composition drops the identity and never notices it was hard.
        Report r = reportWithMigrations(tmp, "1.0.0", "3.0.0", """
                {"schema": 2, "versions": {
                  "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy", "to": "com.botmaker.sdk.api.Middle"}],
                  "3.0.0": [{"from": "com.botmaker.sdk.api.Middle", "to": "com.botmaker.sdk.api.Legacy"}]
                }}
                """);

        // Legacy is genuinely gone from this target, and the round trip pairs it with nothing.
        assertEquals(BreakKind.TYPE_REMOVED,
                brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString())).kind());
    }

    @Test
    void aMigrationsFileFromANewerStudioIsRefusedWholeButBreaksAreStillReported(@TempDir Path tmp)
            throws IOException {
        Report r = reportWithMigrations(tmp, "1.0.0", "2.0.0", """
                {"schema": 99, "versions": {
                  "2.0.0": [{"from": "com.botmaker.sdk.api.Legacy", "to": "com.botmaker.sdk.api.Modern"}]
                }}
                """);

        assertTrue(r.isIncomplete(), "a grammar we might MISREAD is refused whole, not best-effort");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("schema 99")), r.problems().toString());
        assertFalse(r.canMigrate());
        // The point of refusing only the file: breaks come from scanning the jar and need it not at all.
        assertTrue(brk(r, "Wait.seconds").isPresent(), "breaks must survive an unreadable migrations file");
    }

    @Test
    void aTargetThatDeclaresNothingIsNotATargetThatFailedToBeRead(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertTrue(r.renames().isEmpty());
        assertFalse(r.isIncomplete(), "no migrations file at all is silence, not a problem");
    }
}
