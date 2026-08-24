package com.botmaker.studio.services;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.services.SdkUpgradeService.Break;
import com.botmaker.studio.services.SdkUpgradeService.BreakKind;
import com.botmaker.studio.services.SdkUpgradeService.Deprecation;
import com.botmaker.studio.services.SdkUpgradeService.Report;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The upgrade report: given the bot's current SDK jar and the one it might move to, what would break.
 *
 * <p>Two real jars are compiled per case rather than mocked, for the reason the whole feature exists: the
 * question is about bytecode, and the interesting cases — a method gone, an overload's arity changed, a class
 * that disappeared, a survivor now {@code @Deprecated} — are exactly the ones no pair of published SDK
 * versions exhibits yet. A fixture that only ever encodes what already shipped would pass forever without
 * proving the diff works. The machinery that builds them is {@link SdkFixtures}, shared with
 * {@link SplitPointerTest}; what stays here is the fixture SDK <em>content</em> these cases diff.
 */
class SdkUpgradeServiceTest {

    private static final String PKG = SdkFixtures.PKG;

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
        return SdkFixtures.jarOf(dir, label, classes, resources);
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
        return SdkFixtures.serviceOver(tmp, sources);
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
    // A type the bot only writes
    // -------------------------------------------------------------------------

    /**
     * A bot that never calls the SDK type it depends on. Every line here writes {@code Legacy} and none of
     * them is a call, so a scan that only looked for calls found nothing at all — and an upgrade that reports
     * nothing goes ahead, leaving a project that does not compile against a class the release deleted.
     */
    private static final String HOLDER = """
            package com.mybot;
            import java.util.List;
            public class Subject {
                private Legacy kept;
                public void run(Legacy given) {
                    List<Legacy> all = null;
                    Object o = null;
                    Legacy cast = (Legacy) o;
                }
            }
            """;

    @Test
    void aBotThatOnlyHoldsARemovedTypeIsStillRefused(@TempDir Path tmp) throws IOException {
        Report r = reportFor(tmp, HOLDER);

        Break gone = brk(r, "Legacy").orElseThrow(
                () -> new AssertionError("a type written but never called is still a break: " + r.breaks()));
        assertEquals(BreakKind.TYPE_REMOVED, gone.kind());
        assertFalse(r.canMigrate(), "there is no value to stand in for a type in a declaration");
        // The declaration, the parameter, the type argument, and the cast — which writes the name twice on
        // one line and is one place to look, since the sites are deduplicated.
        assertEquals(List.of(4, 5, 6, 8), gone.sites().stream().map(SdkUpgradeService.CallSite::line).toList(),
                gone.sites().toString());
    }

    @Test
    void aRenamedTypeIsRepairedEvenWhereTheBotOnlyWritesIt(@TempDir Path tmp) throws IOException {
        Map<String, String> before = withPointers(oldSdk());
        before.put("Legacy", """
                package %s;
                @ReplacedBy("com.botmaker.sdk.api.Modern")
                public class Legacy {
                    public static void anything() {}
                }
                """.formatted(PKG));
        Map<String, String> after = withPointers(newSdk());
        after.put("Modern", modern(""));

        Report r = serviceOver(tmp, HOLDER).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");

        Break renamed = brk(r, "Legacy").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.TYPE_RENAMED, renamed.kind(), r.breaks() + " " + r.problems());
        assertTrue(r.canMigrate(), "the rename is file-wide and always covered these places: " + r.problems());
        assertEquals(List.of(4, 5, 6, 8),
                renamed.sites().stream().map(SdkUpgradeService.CallSite::line).toList(),
                "the report has to name every one, since the rewrite touches every one: " + renamed.sites());
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
        return SdkFixtures.withPointers(base);
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

        // Listed — the bot does not compile until the call moves — but with the move as its repair rather
        // than a default value, and unmarked, since the shape did not change.
        Break moved = brk(r, "Mouse.doubleClick").orElseThrow();
        assertEquals("now Mouse.twoClicks", moved.detail());
        assertEquals("becomes Mouse.twoClicks", moved.repair());
        // And the type itself was never in question: the other break on Mouse is still reported. That one
        // gained an input, so it is repaired by filling it in rather than by deleting the call.
        Break wider = brk(r, "Mouse.click").orElseThrow();
        assertEquals(BreakKind.SIGNATURE_CHANGED, wider.kind());
        assertTrue(wider.repair().contains("gains 1 input"), wider.repair());
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

    // -------------------------------------------------------------------------
    // The redirect, and what the jars have to confirm before it is taken
    // -------------------------------------------------------------------------

    /**
     * A bot that <b>uses</b> the value, which is the position where the return type has to be checked. What
     * the variable is declared as does not enter into it: the check is between the two jars' own answers —
     * what the old one said the member gave back, and what the new one says its replacement does.
     */
    private static final String FINDER_BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    Object found = Finder.find("t");
                }
            }
            """;

    private static Report finderReport(Path tmp, Map<String, String> before,
                                       Map<String, String> after) throws IOException {
        return serviceOver(tmp, FINDER_BOT).compare(
                jarOf(tmp, "old", withPointers(before), Map.of()),
                jarOf(tmp, "new", withPointers(after), Map.of()), "1.0.0", "2.0.0");
    }

    /** {@code Finder.find} pointing at whatever the test puts in the new jar. */
    private static Map<String, String> finderPointingAt(String target) {
        return Map.of(
                "Finder", """
                        package %s;
                        public class Finder {
                            @Deprecated
                            @ReplacedBy("%s")
                            public static Hit find(String query) { return null; }
                        }
                        """.formatted(PKG, target),
                "Hit", "package %s;\npublic class Hit {}\n".formatted(PKG));
    }

    @Test
    void aReplacementThatGivesBackASubtypeStillFitsWhereTheOldValueSat(@TempDir Path tmp) throws IOException {
        Map<String, String> after = Map.of(
                "Finder", """
                        package %s;
                        public class Finder {
                            public static Precise locate(String query) { return null; }
                        }
                        """.formatted(PKG),
                "Hit", "package %s;\npublic class Hit {}\n".formatted(PKG),
                "Precise", "package %s;\npublic class Precise extends Hit {}\n".formatted(PKG));
        Report r = finderReport(tmp, finderPointingAt(PKG + ".Finder#locate"), after);

        Break moved = brk(r, "Finder.find").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertTrue(moved.repair().startsWith("becomes Finder.locate"), moved.repair());
        assertFalse(moved.repair().contains("where its result is used"),
                "a Precise is a Hit, so every site takes the redirect: " + moved.repair());
    }

    @Test
    void aReplacementThatGivesBackSomethingElseEntirelyIsOnlyTakenWhereNothingReadsIt(@TempDir Path tmp)
            throws IOException {
        Map<String, String> after = Map.of(
                "Finder", """
                        package %s;
                        public class Finder {
                            public static Session locate(String query) { return null; }
                        }
                        """.formatted(PKG),
                "Hit", "package %s;\npublic class Hit {}\n".formatted(PKG),
                "Session", "package %s;\npublic class Session {}\n".formatted(PKG));
        Report r = finderReport(tmp, finderPointingAt(PKG + ".Finder#locate"), after);

        // The pointer is honoured where the value is discarded and refused where it is read — one member,
        // two answers, decided by the position rather than by trusting the annotation.
        Break moved = brk(r, "Finder.find").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertTrue(moved.repair().contains("where its result is used"), moved.repair());
        assertTrue(moved.repair().contains("null"), "and says what stands in there: " + moved.repair());
    }

    @Test
    void aWideningPrimitiveIsAConversionTheCompilerMakesItselfSoTheRedirectStands(@TempDir Path tmp)
            throws IOException {
        Map<String, String> before = Map.of("Finder", """
                package %s;
                public class Finder {
                    @Deprecated
                    @ReplacedBy("%s.Finder#tally")
                    public static long find(String query) { return 0; }
                }
                """.formatted(PKG, PKG));
        Map<String, String> after = Map.of("Finder", """
                package %s;
                public class Finder {
                    public static int tally(String query) { return 0; }
                }
                """.formatted(PKG));
        Report r = finderReport(tmp, before, after);

        Break moved = brk(r, "Finder.find").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertFalse(moved.repair().contains("where its result is used"),
                "an int goes into a long slot untouched: " + moved.repair());
    }

    @Test
    void aMemberThatMovedToAnotherTypeIsRedirectedThereReceiverAndAll(@TempDir Path tmp) throws IOException {
        Map<String, String> after = Map.of(
                "Finder", "package %s;\npublic class Finder {}\n".formatted(PKG),
                "Hit", "package %s;\npublic class Hit {}\n".formatted(PKG),
                "Vision", """
                        package %s;
                        public class Vision {
                            @Replaces("%s.Finder#find@1.0.0")
                            public static Hit find(String query) { return null; }
                        }
                        """.formatted(PKG, PKG));
        Report r = finderReport(tmp, finderPointingAt(PKG + ".Vision#find"), after);

        Break moved = brk(r, "Finder.find").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals(BreakKind.MEMBER_REMOVED, moved.kind());
        assertEquals("now Vision.find", moved.detail());
        // Same name, same shape — the whole change is which type it is reached through, and that is a
        // complete repair, so no review mark is promised.
        assertEquals("becomes Vision.find", moved.repair());
    }

    @Test
    void aPointerAtANameWithSeveralOverloadsAndNoneOfThisAritysIsAQuestionNotAGuess(@TempDir Path tmp)
            throws IOException {
        Map<String, String> after = Map.of(
                "Finder", """
                        package %s;
                        public class Finder {
                            public static Hit locate(String query, int limit) { return null; }
                            public static Hit locate(String query, int limit, int skip) { return null; }
                        }
                        """.formatted(PKG),
                "Hit", "package %s;\npublic class Hit {}\n".formatted(PKG));
        Report r = finderReport(tmp, finderPointingAt(PKG + ".Finder#locate"), after);

        // Which one the author meant is exactly what the arity was going to say. With none matching and two
        // to choose from, the call defaults and the user is told — the same answer as no pointer at all.
        Break moved = brk(r, "Finder.find").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertTrue(moved.repair().startsWith("replaced with null"), moved.repair());
        assertTrue(moved.isRepairable());
    }

    // -------------------------------------------------------------------------
    // Modernising: the same walk over one jar, with no version change
    // -------------------------------------------------------------------------

    /** A bot on one deprecated call and one live one, so the two lists can be told apart. */
    private static final String TIMER_BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    Wait.time(500);
                    Point p = new Point(1, 2);
                }
            }
            """;

    /**
     * One jar compared with <em>itself</em>. That is the whole of what modernising is: no diff, no target
     * version, nothing removed — only what the SDK's own {@code @ReplacedBy} pointers say about members it
     * has already marked on the way out.
     */
    private static Report moderniseReport(Path tmp, Map<String, String> sdk, boolean through)
            throws IOException {
        Path jar = jarOf(tmp, "same", withPointers(sdk), Map.of());
        return serviceOver(tmp, TIMER_BOT).compare(jar, jar, "1.0.0", "1.0.0", through);
    }

    private static Optional<Deprecation> dep(Report r, String display) {
        return r.deprecated().stream().filter(d -> d.display().equals(display)).findFirst();
    }

    /** The pinned SDK, with {@code Wait.time} deprecated and pointed at {@code pointer}. */
    private static Map<String, String> pinnedSdk(String pointer, String replacement) {
        Map<String, String> sdk = new java.util.HashMap<>(oldSdk());
        sdk.put("Wait", """
                package %s;
                public class Wait {
                    public static void seconds(int s) {}
                    @Deprecated(since = "1.0.0", forRemoval = true)
                    %s
                    public static void time(long ms) {}
                    %s
                }
                """.formatted(PKG, pointer, replacement));
        return sdk;
    }

    @Test
    void aDeprecatedMemberThatSaysWhereToGoIsSomethingModerniseCanMoveOnItsOwn(@TempDir Path tmp)
            throws IOException {
        Report r = moderniseReport(tmp, pinnedSdk("@ReplacedBy(\"" + PKG + ".Wait#pause\")",
                "public static void pause(long ms) {}"), true);

        // Nothing here is a break: the bot compiles today and would go on compiling. That is why the
        // question is canModernise() and not canMigrate(), which asks whether a break may be repaired.
        assertTrue(r.breaks().isEmpty(), "a jar against itself breaks nothing: " + r.breaks());
        assertFalse(r.canMigrate());
        assertTrue(r.canModernise(), "problems=" + r.problems() + " deprecated=" + r.deprecated());

        Deprecation moving = dep(r, "Wait.time").orElseThrow(() -> new AssertionError(r.deprecated().toString()));
        assertEquals("Wait.pause", moving.becomes());
        assertTrue(moving.repair().startsWith("becomes Wait.pause"), moving.repair());
        assertEquals(List.of(moving), r.movable());
    }

    @Test
    void aDeprecationTheSdkSaysNothingAboutIsLeftWhereItIs(@TempDir Path tmp) throws IOException {
        // The pointer is what makes a deprecation actionable. Without one there is no answer to move to,
        // and inventing one is the guess this whole design refuses — so the row is listed with nothing
        // beside it and the button stays off.
        Report r = moderniseReport(tmp, pinnedSdk("", ""), true);

        Deprecation stuck = dep(r, "Wait.time").orElseThrow(() -> new AssertionError(r.deprecated().toString()));
        assertEquals("", stuck.becomes());
        assertFalse(stuck.isMovable());
        assertFalse(r.canModernise(), "nothing to move: " + r.movable());
    }

    @Test
    void aDeprecatedMemberIsOnlyFollowedWhenTheExtraHopWasAskedFor(@TempDir Path tmp) throws IOException {
        // The same jar and the same pointer, read as a plain upgrade would read it. A member that still
        // exists stops the walk, so the row says it is deprecated and nothing more — which is honest, since
        // the call does still compile.
        Report r = moderniseReport(tmp, pinnedSdk("@ReplacedBy(\"" + PKG + ".Wait#pause\")",
                "public static void pause(long ms) {}"), false);

        Deprecation row = dep(r, "Wait.time").orElseThrow(() -> new AssertionError(r.deprecated().toString()));
        assertEquals("", row.becomes());
        assertFalse(r.canModernise());
    }

    @Test
    void aModernisationThatChangesShapeIsMadeAndMarkedRatherThanRefused(@TempDir Path tmp)
            throws IOException {
        Report r = moderniseReport(tmp, pinnedSdk("@ReplacedBy(\"" + PKG + ".Wait#pause\")",
                "public static void pause(long ms, boolean interruptible) {}"), true);

        Deprecation moving = dep(r, "Wait.time").orElseThrow(() -> new AssertionError(r.deprecated().toString()));
        assertTrue(moving.repair().contains("gains 1 input"), moving.repair());
        assertTrue(moving.repair().contains("marked for your review"),
                "a shape that moved is exactly what the Review tab is for: " + moving.repair());
        assertTrue(r.canModernise());
    }

    @Test
    void aDeprecatedClassPointedAtItsSuccessorIsOfferedAsARenameNotAnnouncedAsABreak(@TempDir Path tmp)
            throws IOException {
        // Both spellings are in the one jar — that is what a deprecation window is — so the old name still
        // compiles. Reporting it as a break would be false; it belongs on the list of things to move.
        Map<String, String> sdk = new java.util.HashMap<>(oldSdk());
        sdk.put("Wait", """
                package %s;
                @Deprecated(since = "1.0.0", forRemoval = true)
                @ReplacedBy("%s.Pause")
                public class Wait {
                    public static void seconds(int s) {}
                    public static void time(long ms) {}
                }
                """.formatted(PKG, PKG));
        sdk.put("Pause", """
                package %s;
                public class Pause {
                    public static void seconds(int s) {}
                    public static void time(long ms) {}
                }
                """.formatted(PKG));
        Report r = moderniseReport(tmp, sdk, true);

        assertTrue(r.breaks().isEmpty(), "the old name is still there: " + r.breaks());
        Deprecation moving = dep(r, "Wait.time").orElseThrow(() -> new AssertionError(r.deprecated().toString()));
        assertEquals("Pause", moving.becomes());
        assertTrue(moving.repair().contains("every use of \"Wait\" becomes \"Pause\""), moving.repair());
    }

    // -------------------------------------------------------------------------
    // What the author said about the move: note, behaviourChanged, @Since, @Scaffolding
    // -------------------------------------------------------------------------

    /**
     * {@code Mouse.doubleClick} → {@code Mouse.twoClicks}: the same shape, the same type, so nothing Studio
     * can read off the two jars would ever mark it. Whatever the row says beyond "becomes Mouse.twoClicks"
     * came from the annotations, which is exactly what these tests are about.
     */
    private static Report moveReport(Path tmp, String forward, String backward) throws IOException {
        Map<String, String> before = withPointers(oldSdk());
        before.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y) {}
                    @Deprecated
                    %s
                    public static void doubleClick(int x, int y) {}
                }
                """.formatted(PKG, forward));
        Map<String, String> after = withPointers(newSdk());
        after.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y, long delayMs) {}
                    %s
                    public static void twoClicks(int x, int y) {}
                }
                """.formatted(PKG, backward));
        return serviceOver(tmp, BOT).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");
    }

    @Test
    void theAuthorsOwnSentenceReachesTheUserWordForWord(@TempDir Path tmp) throws IOException {
        Report r = moveReport(tmp,
                "@ReplacedBy(value = \"" + PKG + ".Mouse#twoClicks\", note = \"twoClicks waits between the "
                        + "two presses, which is what most games expect.\")", "");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals("becomes Mouse.twoClicks — twoClicks waits between the two presses, which is what most "
                + "games expect.", moved.repair());
    }

    @Test
    void theSurvivorsSentenceAnswersForABotThatSkippedTheDeprecationRelease(@TempDir Path tmp)
            throws IOException {
        // No forward pointer at all: the deprecated member is simply gone, and the back edge — with its
        // arity, since by now there is no overload left to sit on — is the only record of what happened.
        Report r = moveReport(tmp, "",
                "@Replaces(value = \"" + PKG + ".Mouse#doubleClick(2)@1.5.0\", note = \"Say twoClicks now.\")");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals("becomes Mouse.twoClicks — Say twoClicks now.", moved.repair());
    }

    @Test
    void whenBothJarsSpeakTheOldOneWins(@TempDir Path tmp) throws IOException {
        // The old jar is the author speaking at the moment of the change, on the very element this bot
        // calls. The new jar's copy is the fallback for the bot that was not there to hear it.
        Report r = moveReport(tmp,
                "@ReplacedBy(value = \"" + PKG + ".Mouse#twoClicks\", note = \"Forward.\")",
                "@Replaces(value = \"" + PKG + ".Mouse#doubleClick(2)@1.5.0\", note = \"Backward.\")");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals("becomes Mouse.twoClicks — Forward.", moved.repair());
    }

    @Test
    void aMoveThatChangedNoShapeButChangedBehaviourIsStillMarked(@TempDir Path tmp) throws IOException {
        // The one gap the model cannot see by construction: same name shape, same arity, same return type,
        // and a different thing happening at runtime. Only the author can say so, and saying so is enough.
        Report r = moveReport(tmp,
                "@ReplacedBy(value = \"" + PKG + ".Mouse#twoClicks\", behaviourChanged = true, "
                        + "note = \"twoClicks now waits between the presses.\")", "");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertTrue(moved.repair().contains("marked for your review"), moved.repair());
        assertTrue(moved.repair().endsWith("— twoClicks now waits between the presses."), moved.repair());
    }

    @Test
    void eitherEndAssertingBehaviourChangedIsEnough(@TempDir Path tmp) throws IOException {
        Report r = moveReport(tmp, "@ReplacedBy(\"" + PKG + ".Mouse#twoClicks\")",
                "@Replaces(value = \"" + PKG + ".Mouse#doubleClick(2)@1.5.0\", behaviourChanged = true, "
                        + "note = \"It waits now.\")");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertTrue(moved.repair().contains("marked for your review"), moved.repair());
    }

    @Test
    void theSameMoveWithNothingSaidAboutItIsUnmarkedAndUnannotated(@TempDir Path tmp) throws IOException {
        // The control: every sentence above has to be the annotations' doing, not the diff's.
        Report r = moveReport(tmp, "@ReplacedBy(\"" + PKG + ".Mouse#twoClicks\")", "");

        Break moved = brk(r, "Mouse.doubleClick").orElseThrow(() -> new AssertionError(r.breaks().toString()));
        assertEquals("becomes Mouse.twoClicks", moved.repair());
    }

    @Test
    void additionsAreGroupedByTheReleaseTheyArrivedIn(@TempDir Path tmp) throws IOException {
        Map<String, String> after = withPointers(newSdk());
        after.put("Mouse", """
                package %s;
                public class Mouse {
                    public static void click(int x, int y, long delayMs) {}
                    public static void doubleClick(int x, int y) {}
                    @com.botmaker.sdk.api.meta.Since("2.0.0")
                    public static void dragTo(int x, int y) {}
                }
                """.formatted(PKG));
        after.put("Session", """
                package %s;
                @com.botmaker.sdk.api.meta.Since("1.5.0")
                public class Session {
                    public static void open() {}
                }
                """.formatted(PKG));
        Report r = serviceOver(tmp, BOT).compare(jarOf(tmp, "old", withPointers(oldSdk()), Map.of()),
                jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");

        assertEquals(List.of("2.0.0", "1.5.0"), List.copyOf(r.addedBySince().keySet()),
                "newest era first: " + r.addedBySince());
        assertEquals(List.of("Mouse.dragTo(…)"), r.addedBySince().get("2.0.0"));
        assertEquals(List.of("Session (new class)"), r.addedBySince().get("1.5.0"));
        // And the flat list every other reader already asks for is still the same set.
        assertEquals(2, r.added().size(), r.added().toString());
    }

    @Test
    void additionsFromAJarThatDeclaresNoErasAreOneUnlabelledGroup(@TempDir Path tmp) throws IOException {
        // The standing rule: a jar with none of this reads exactly as it did before the readers existed.
        Report r = reportFor(tmp, BOT);

        assertEquals(List.of(""), List.copyOf(r.addedBySince().keySet()), r.addedBySince().toString());
        assertTrue(r.added().contains("Mouse.dragTo(…)"), r.added().toString());
    }

    @Test
    void aBreakOnSomethingStudioItselfWritesIsSaidUpFront(@TempDir Path tmp) throws IOException {
        // Generated files are rendered from Studio's templates, never migrated, so this upgrade cannot be
        // completed by a rewrite of the user's code alone. That was a refusal thrown mid-apply; it is now
        // also a line in the report, before the user commits to anything.
        Map<String, String> before = withPointers(oldSdk());
        before.put("Wait", """
                package %s;
                public class Wait {
                    @com.botmaker.sdk.api.meta.Scaffolding
                    public static void seconds(int s) {}
                    public static void time(long ms) {}
                }
                """.formatted(PKG));
        Report r = serviceOver(tmp, BOT).compare(jarOf(tmp, "old", before, Map.of()),
                jarOf(tmp, "new", withPointers(newSdk()), Map.of()), "1.0.0", "2.0.0");

        assertEquals(List.of("Wait.seconds"), r.scaffolding(), "problems=" + r.problems());
        // And it changes no verdict: the break is still exactly the break it was.
        assertEquals(BreakKind.MEMBER_REMOVED, brk(r, "Wait.seconds").orElseThrow().kind());
    }

    @Test
    void aBreakOnAnOrdinaryMemberSaysNothingAboutScaffolding(@TempDir Path tmp) throws IOException {
        assertTrue(reportFor(tmp, BOT).scaffolding().isEmpty());
    }

    // -------------------------------------------------------------------------
    // What the release says about itself
    // -------------------------------------------------------------------------

    /** What the SDK's own build copies into every jar it publishes — see {@code botmaker-sdk/pom.xml}. */
    private static final String CHANGELOG = """
            # Changelog

            ## [Unreleased]

            - something not released yet, which no jar may ever show.

            ## [2.0.0] — 2026-08-24

            - `Mouse.dragTo` — drag without holding the button down yourself.
            - **Session** is new.

            ## [1.5.0] — 2026-08-20

            - the waiter stops spinning the CPU.

            ## [1.0.0] — 2026-08-01

            - the release the bot is already on.

            ## Earlier

            See `ROADMAP.md`.
            """;

    @Test
    void theReportCarriesTheReleasesTheBotIsMovingThrough(@TempDir Path tmp) throws IOException {
        Report r = serviceOver(tmp, BOT).compare(
                jarOf(tmp, "old", oldSdk(), Map.of()),
                jarOf(tmp, "new", newSdk(), Map.of(SdkWhatsNew.ENTRY, CHANGELOG)),
                "1.0.0", "2.0.0");

        assertEquals(List.of("2.0.0", "1.5.0"),
                r.highlights().stream().map(SdkUpgradeService.Highlight::version).toList(),
                "the span is (from, to], newest first: " + r.highlights());
        assertEquals("2026-08-24", r.highlights().get(0).date());
        // The emphasis markers go because a Label renders them literally; the bullet, the wording and the
        // author's punctuation all stay, which is the same promise @ReplacedBy.note() makes.
        assertEquals(List.of("- Mouse.dragTo — drag without holding the button down yourself.",
                        "- Session is new."),
                r.highlights().get(0).lines());
    }

    @Test
    void aJarThatCarriesNoChangelogReadsExactlyAsItDidBefore(@TempDir Path tmp) throws IOException {
        // The standing rule. Every SDK up to v1.0.26 is this case, so it is the common one, not the edge.
        assertTrue(reportFor(tmp, BOT).highlights().isEmpty());
    }

    @Test
    void modernisingIsMovingToNothingAndSaysNothing(@TempDir Path tmp) throws IOException {
        Path jar = jarOf(tmp, "same", newSdk(), Map.of(SdkWhatsNew.ENTRY, CHANGELOG));
        Report r = serviceOver(tmp, BOT).compare(jar, jar, "2.0.0", "2.0.0", true);

        assertTrue(r.highlights().isEmpty(),
                "(2.0.0, 2.0.0] is empty: there is no release being moved to: " + r.highlights());
    }

    @Test
    void theReleaseTheBotIsAlreadyOnIsNotNews() {
        assertEquals(List.of("2.0.0"),
                SdkWhatsNew.parse(CHANGELOG, "1.5.0", "2.0.0").stream()
                        .map(SdkUpgradeService.Highlight::version).toList());
    }

    @Test
    void anUnreleasedSectionIsNeverInRange() {
        // It has no version, so it cannot be in a span — which is the whole of the special case it needs.
        assertTrue(SdkWhatsNew.parse(CHANGELOG, "0.0.1", "9.9.9").stream()
                .noneMatch(h -> h.version().contains("Unrelease")));
        assertEquals(List.of("2.0.0", "1.5.0", "1.0.0"),
                SdkWhatsNew.parse(CHANGELOG, "0.0.1", "9.9.9").stream()
                        .map(SdkUpgradeService.Highlight::version).toList());
    }

    @Test
    void aBoundThatIsNotAVersionIsSimplyNotApplied() {
        // A project pinned to a local 0.0.0-SNAPSHOT build is the case that actually happens. Showing every
        // section up to the target is a readable failure; showing none looks like a release that did nothing.
        assertEquals(List.of("2.0.0", "1.5.0", "1.0.0"),
                SdkWhatsNew.parse(CHANGELOG, "0.0.0-SNAPSHOT", "2.0.0").stream()
                        .map(SdkUpgradeService.Highlight::version).toList());
    }

    @Test
    void aSectionOutOfOrderInTheFileIsStillShownNewestFirst() {
        String jumbled = """
                ## [1.5.0] — 2026-08-20

                - older, written first.

                ## [2.0.0] — 2026-08-24

                - newer, written second.
                """;
        assertEquals(List.of("2.0.0", "1.5.0"),
                SdkWhatsNew.parse(jumbled, "1.0.0", "2.0.0").stream()
                        .map(SdkUpgradeService.Highlight::version).toList());
    }
}
