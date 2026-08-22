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
import com.botmaker.studio.services.SdkUpgradeService.Migration;
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
        Report r = reportFor(tmp, BOT);

        assertEquals(BreakKind.TYPE_REMOVED, brk(r, "Legacy.anything").orElseThrow().kind());
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

        Break gone = brk(r, "new Point").orElseThrow(
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
    void declaredChangesAreReadFromTheTargetJarAndScopedToTheVersionRange(@TempDir Path tmp)
            throws IOException {
        Report r = reportWithMigrations(tmp, "1.6.0", "2.0.0", """
                {"schema": 1, "versions": {
                  "1.5.0": [{"member": "com.botmaker.sdk.api.Old#thing",
                             "summary": "already behind us", "manual": "nothing to do"}],
                  "2.0.0": [{"member": "com.botmaker.sdk.api.Wait#time",
                             "summary": "time() now counts from the last frame, not from the call",
                             "manual": "check any bot that relied on the old timing"}],
                  "3.0.0": [{"member": "com.botmaker.sdk.api.Future#thing",
                             "summary": "not on this path", "manual": "n/a"}]
                }}
                """);

        assertEquals(List.of("2.0.0"), r.migrations().stream().map(Migration::version).toList(),
                "only versions in (from, to] — an entry for a release the bot is already past is noise, and "
                        + "one for a release beyond the target has not happened to this bot yet");
        assertTrue(r.manual().get(0).manual().startsWith("check any bot"),
                "the SDK author's sentence, verbatim");
        assertFalse(r.nothingBreaks(), "a declared change is not 'nothing breaks'");
        assertFalse(r.canMigrate(), "a manual entry disables the whole span");
    }

    @Test
    void anEntryWithAFixIsAutomaticAndOneWithoutIsNot(@TempDir Path tmp) throws IOException {
        Report r = reportWithMigrations(tmp, "1.0.0", "2.0.0", """
                {"schema": 1, "versions": {
                  "2.0.0": [
                    {"member": "com.botmaker.sdk.api.Wait#seconds",
                     "summary": "seconds() was renamed to pause()",
                     "fix": {"kind": "renameMethod", "to": "pause"}, "when": {"arity": 1}}
                  ]
                }}
                """);

        assertEquals(1, r.automatic().size(), r.migrations().toString());
        assertTrue(r.manual().isEmpty());
        assertEquals("renameMethod", r.automatic().get(0).fix().kind());
        assertEquals("pause", r.automatic().get(0).fix().options().path("to").asText());
        assertEquals(1, r.automatic().get(0).fix().arity(), "when.arity scopes the fix to one overload");
        assertTrue(r.canMigrate(), "every entry has a fix this Studio knows: " + r.migrations());
    }

    @Test
    void anUnknownFixKindDegradesToManualAndDisablesTheWholeSpan(@TempDir Path tmp) throws IOException {
        // The newer-SDK-older-Studio case. The entry must still be SHOWN — an entry silently skipped is a
        // break the user is never told about — and it must take the rest of the span down with it rather
        // than letting half the call sites be rewritten.
        Report r = reportWithMigrations(tmp, "1.0.0", "2.0.0", """
                {"schema": 1, "versions": {
                  "2.0.0": [
                    {"member": "com.botmaker.sdk.api.Wait#seconds",
                     "summary": "seconds() was renamed to pause()",
                     "fix": {"kind": "renameMethod", "to": "pause"}},
                    {"member": "com.botmaker.sdk.api.Mouse#click",
                     "summary": "click() moved behind a builder",
                     "fix": {"kind": "wrapInBuilderSomethingFromTheFuture", "to": "x"}}
                  ]
                }}
                """);

        assertEquals(2, r.migrations().size(), "neither entry may be dropped: " + r.migrations());
        assertEquals(1, r.manual().size());
        assertTrue(r.manual().get(0).degraded(),
                "the reason matters — 'needs a newer Studio' is not 'no rewrite can express this'");
        assertTrue(r.manual().get(0).summary().contains("builder"), "the SDK's own summary still shows");
        assertFalse(r.canMigrate(), "one unknown kind disables the span, including the renameMethod beside it");
    }

    @Test
    void aMigrationsFileFromANewerStudioIsRefusedWholeButBreaksAreStillReported(@TempDir Path tmp)
            throws IOException {
        Report r = reportWithMigrations(tmp, "1.0.0", "2.0.0", """
                {"schema": 99, "versions": {
                  "2.0.0": [{"member": "com.botmaker.sdk.api.Wait#seconds",
                             "summary": "written in a grammar this Studio does not have",
                             "fix": {"kind": "renameMethod", "to": "pause"}}]
                }}
                """);

        assertTrue(r.migrations().isEmpty(), "a grammar we might MISREAD is refused whole, not best-effort");
        assertTrue(r.isIncomplete(), "and the refusal is stated rather than looking like an empty file");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("schema 99")), r.problems().toString());
        assertFalse(r.canMigrate());
        // The point of refusing only the file: breaks come from scanning the jar and need it not at all.
        assertTrue(brk(r, "Wait.seconds").isPresent(), "breaks must survive an unreadable migrations file");
    }

    @Test
    void aDeclaredChangeIsShownAgainstTheLinesItAffects(@TempDir Path tmp) throws IOException {
        Report r = reportWithMigrations(tmp, "1.0.0", "2.0.0", """
                {"schema": 1, "versions": {
                  "2.0.0": [{"member": "com.botmaker.sdk.api.Wait#seconds",
                             "summary": "seconds() was renamed to pause()",
                             "fix": {"kind": "renameMethod", "to": "pause"}}]
                }}
                """);

        assertEquals(1, r.automatic().get(0).sites().size(), r.automatic().toString());
        assertTrue(r.automatic().get(0).sites().get(0).toString().endsWith(":6"),
                "the line Wait.seconds(3) is on: " + r.automatic().get(0).sites());
    }

    @Test
    void aLaterEntryIsShownAgainstTheNameTheProjectStillSpells(@TempDir Path tmp) throws IOException {
        // The ordered-replay trap, in report form. 2.0.0 renames seconds → pause; 3.0.0 then says something
        // about `pause`. A bot jumping from 1.x has run neither, so its source still says `seconds` — and an
        // entry that looked for `pause` would find nothing and show a note about a name appearing nowhere.
        Report r = reportWithMigrations(tmp, "1.0.0", "3.0.0", """
                {"schema": 1, "versions": {
                  "2.0.0": [{"member": "com.botmaker.sdk.api.Wait#seconds",
                             "summary": "seconds() was renamed to pause()",
                             "fix": {"kind": "renameMethod", "to": "pause"}}],
                  "3.0.0": [{"member": "com.botmaker.sdk.api.Wait#pause",
                             "summary": "pause() now counts from the last frame",
                             "manual": "check any bot that relied on the old timing"}]
                }}
                """);

        Migration later = r.migrations().stream().filter(m -> m.version().equals("3.0.0")).findFirst()
                .orElseThrow();
        assertEquals(1, later.sites().size(), "resolved back through 2.0.0's rename: " + r.migrations());
        assertTrue(later.sites().get(0).toString().endsWith(":6"), later.sites().toString());
    }

    @Test
    void aTargetThatDeclaresNothingIsNotATargetThatFailedToBeRead(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, BOT);

        assertTrue(r.migrations().isEmpty());
        assertFalse(r.isIncomplete(), "no migrations file at all is silence, not a problem");
        assertFalse(r.canMigrate(), "and there is nothing to apply");
    }
}
