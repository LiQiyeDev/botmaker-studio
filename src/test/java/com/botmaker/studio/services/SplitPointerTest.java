package com.botmaker.studio.services;

import com.botmaker.studio.config.BotMakerDirs;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.services.SdkUpgradeService.CallSite;
import com.botmaker.studio.services.SdkUpgradeService.Choice;
import com.botmaker.studio.services.SdkUpgradeService.Report;
import com.botmaker.studio.services.SdkUpgradeService.Site;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One old member becoming <b>two</b>, and the question that puts to each call of it.
 *
 * <p>Which candidate a call meant is a property of <em>that call</em> — {@code scroll(3)} and
 * {@code scroll(-3)} want different answers — so this is the only place in the whole upgrade where the user
 * decides rather than reads. The two halves are tested through the two seams the service exposes for exactly
 * this reason: {@link SdkUpgradeService#compare} for what the dialog is shown, and
 * {@link SdkUpgradeService#migrate} for what is actually written, neither of which needs a pom, a resolver or
 * a network round trip.
 *
 * <p>The fixture is {@code Mouse.scroll(int)} — the case that revealed the gap in the pointer model. Nothing
 * in the SDK deprecates it; it is here because it is the clearest example of a member whose replacement is
 * two members and whose call sites disagree about which.
 */
class SplitPointerTest {

    private static final String PKG = SdkFixtures.PKG;

    /** What the author writes on the old member: two candidates, in preference order, each with its sentence. */
    private static final String SPLIT_POINTER = """
            @Deprecated
            @ReplacedBy(value = {"com.botmaker.sdk.api.Mouse#scrollUp",
                                 "com.botmaker.sdk.api.Mouse#scrollDown"},
                        whens = {"when notches is positive", "when notches is negative"})
            """;

    /** The back edge each survivor carries: the old spelling, and the last release it existed in. */
    private static final String CLAIMS_SCROLL = "@Replaces(\"com.botmaker.sdk.api.Mouse#scroll@1.0.0\")";

    /** Three calls of one split member, two of which plainly meant different candidates. */
    private static final String SCROLLING_BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    Mouse.scroll(3);
                    Mouse.scroll(-3);
                    Mouse.scroll(1);
                }
            }
            """;

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(BotMakerDirs.getCacheDir().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    // -------------------------------------------------------------------------
    // The fixture SDKs
    // -------------------------------------------------------------------------

    /** 1.0.0: one {@code Mouse.scroll(int)}, carrying whatever the case under test puts on it. */
    private static Map<String, String> oldMouse(String annotation) {
        Map<String, String> m = new HashMap<>(SdkFixtures.withPointers(Map.of()));
        m.put("Mouse", """
                package %s;
                public class Mouse {
                    %s
                    public static void scroll(int notches) {}
                }
                """.formatted(PKG, annotation));
        return m;
    }

    /** 2.0.0: {@code scroll} is <b>gone</b> and two members stand where it did. */
    private static Map<String, String> newMouse(String claim) {
        Map<String, String> m = new HashMap<>(SdkFixtures.withPointers(Map.of()));
        m.put("Mouse", """
                package %s;
                public class Mouse {
                    %s
                    public static void scrollUp(int notches) {}
                    %s
                    public static void scrollDown(int notches) {}
                }
                """.formatted(PKG, claim, claim));
        return m;
    }

    private static Report reportOver(Path tmp, Map<String, String> before, Map<String, String> after,
                                     String bot) throws IOException {
        return SdkFixtures.serviceOver(tmp, bot).compare(
                SdkFixtures.jarOf(tmp, "old", before, Map.of()),
                SdkFixtures.jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0");
    }

    /** The rewrite the same jars produce, given the picks a dialog would have collected. */
    private static String rewriteOver(Path tmp, Map<String, String> before, Map<String, String> after,
                                      String bot, Map<CallSite, Integer> picks) throws IOException {
        SdkUpgradeService service = SdkFixtures.serviceOver(tmp, bot);
        SdkMigrationRunner.Outcome outcome = service.migrate(
                SdkFixtures.jarOf(tmp, "old", before, Map.of()),
                SdkFixtures.jarOf(tmp, "new", after, Map.of()), "1.0.0", "2.0.0", false, true, picks);
        assertNotNull(outcome, "the upgrade had nothing to repair, so there is no rewrite to read");
        assertFalse(outcome.isRefusal(), () -> "the upgrade refused: " + outcome.refusal());
        return outcome.files().stream()
                .filter(f -> f.file().getPath().toString().endsWith("Subject.java"))
                .map(CallMigrator.Rewritten::newSource)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the bot's own file was not rewritten"));
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    // -------------------------------------------------------------------------
    // What the dialog is shown
    // -------------------------------------------------------------------------

    @Test
    void aSplitIsOneChoiceCarryingEveryCallOfTheMember(@TempDir Path tmp) throws IOException {
        Report r = reportOver(tmp, oldMouse(SPLIT_POINTER), newMouse(CLAIMS_SCROLL), SCROLLING_BOT);

        assertEquals(1, r.splits().size(), "one member split, so one question: " + r.splits());
        Choice choice = r.splits().getFirst();
        assertEquals("Mouse.scroll", choice.display());
        assertEquals(2, choice.candidates().size());
        assertEquals(3, choice.sites().size(), "every call of it is a site of its own");

        assertTrue(choice.candidates().getFirst().display().contains("scrollUp"),
                "the author's first candidate is the preferred one: " + choice.candidates());
        assertTrue(choice.candidates().getFirst().display().contains("when notches is positive"),
                "the whens() sentence is what makes the menu answerable: " + choice.candidates());

        // A call standing as a statement discards its result, so both candidates fit at all three.
        choice.sites().forEach(site ->
                assertEquals(2, site.candidates().size(), "at " + site.site() + ": " + site.candidates()));
    }

    @Test
    void aSplitLeavesTheOtherVerdictsAlone(@TempDir Path tmp) throws IOException {
        Report r = reportOver(tmp, oldMouse(SPLIT_POINTER), newMouse(CLAIMS_SCROLL), SCROLLING_BOT);

        // The member is still gone, and is still reported as gone. `splits` is the question that goes with
        // that finding, not a third kind of finding — which is what keeps canMigrate() meaning what it did.
        assertTrue(r.breaks().stream().anyMatch(b -> b.display().equals("Mouse.scroll")),
                "the break itself is still listed: " + r.breaks());
        assertTrue(r.canMigrate(), "a split is repairable, so the upgrade is still offered");
    }

    @Test
    void aSiteWhoseValueIsUsedOffersOnlyTheCandidatesThatFitThere(@TempDir Path tmp) throws IOException {
        Report r = reportOver(tmp, oldTextSplit(), newTextSplit("int"), TEXT_BOT);

        Choice choice = r.splits().getFirst();
        assertEquals(2, choice.candidates().size(), "both candidates exist on the member");

        // Both sites read `Text.read()` — which is the point of carrying the line as well as the text.
        Site statement = siteAtLine(choice, 4);
        Site used = siteAtLine(choice, 5);
        assertEquals(2, statement.candidates().size(), "a statement discards the value, so both fit");
        assertEquals(1, used.candidates().size(),
                "an int does not sit where a String sat: " + used.candidates());
        assertTrue(used.candidates().getFirst().display().contains("line"));
    }

    @Test
    void aSiteWhereNothingFitsIsTheDefaultItWouldHaveGotAnyway(@TempDir Path tmp) throws IOException {
        // Both candidates return something that cannot stand where the String did.
        Report r = reportOver(tmp, oldTextSplit(), newTextSplit("boolean"), STORED_TEXT_BOT);

        Site only = r.splits().getFirst().sites().getFirst();
        assertTrue(only.candidates().isEmpty(),
                "no candidate fits, which is not a new outcome: " + only.candidates());

        String rewritten = rewriteOver(tmp, oldTextSplit(), newTextSplit("boolean"), STORED_TEXT_BOT,
                Map.of());
        assertTrue(rewritten.contains("String s = \"\";"),
                "the default value of the type it used to return stands in:\n" + rewritten);
        assertTrue(rewritten.contains("@NeedsReview"), "and the site is marked:\n" + rewritten);
    }

    @Test
    void aDoubleClaimWithNoForwardPointerIsStillASplit(@TempDir Path tmp) throws IOException {
        // The old member carries nothing at all: it was deleted, and the split is readable only backwards.
        // This is the case the back-edge decision exists for — without it the two claims are an error.
        Report r = reportOver(tmp, oldMouse(""), newMouse(CLAIMS_SCROLL), SCROLLING_BOT);

        assertEquals(1, r.splits().size(), "read off @Replaces alone: " + r.splits());
        Choice choice = r.splits().getFirst();
        assertEquals(2, choice.candidates().size());
        assertTrue(choice.candidates().stream().allMatch(c -> c.when().isBlank()),
                "a survivor knows what it replaced, not why one call meant it: " + choice.candidates());
        assertTrue(r.problems().isEmpty(), "and it is not reported as an ambiguity: " + r.problems());
    }

    @Test
    void anOrdinarySingleTargetPointerIsNotASplit(@TempDir Path tmp) throws IOException {
        Map<String, String> before = oldMouse("""
                @Deprecated
                @ReplacedBy("com.botmaker.sdk.api.Mouse#wheel")
                """);
        Map<String, String> after = new HashMap<>(SdkFixtures.withPointers(Map.of()));
        after.put("Mouse", """
                package %s;
                public class Mouse {
                    @Replaces("com.botmaker.sdk.api.Mouse#scroll@1.0.0")
                    public static void wheel(int notches) {}
                }
                """.formatted(PKG));

        Report r = reportOver(tmp, before, after, SCROLLING_BOT);
        assertTrue(r.splits().isEmpty(), "one target is one answer, and nobody is asked: " + r.splits());

        String rewritten = rewriteOver(tmp, before, after, SCROLLING_BOT, Map.of());
        assertEquals(3, occurrences(rewritten, "Mouse.wheel("), rewritten);
        assertFalse(rewritten.contains("Mouse.scroll("), rewritten);
    }

    // -------------------------------------------------------------------------
    // What is actually written
    // -------------------------------------------------------------------------

    @Test
    void withNoChoicesEveryCallTakesThePreferredCandidate(@TempDir Path tmp) throws IOException {
        // Modernise, the tests and every headless path pass nothing, and must migrate as they always have.
        String rewritten = rewriteOver(tmp, oldMouse(SPLIT_POINTER), newMouse(CLAIMS_SCROLL),
                SCROLLING_BOT, Map.of());

        assertEquals(3, occurrences(rewritten, "Mouse.scrollUp("), rewritten);
        assertFalse(rewritten.contains("scrollDown"), rewritten);
        assertFalse(rewritten.contains("Mouse.scroll("), rewritten);
    }

    @Test
    void eachSiteIsRewrittenToWhatWasChosenThere(@TempDir Path tmp) throws IOException {
        Map<String, String> before = oldMouse(SPLIT_POINTER);
        Map<String, String> after = newMouse(CLAIMS_SCROLL);
        Report r = reportOver(tmp, before, after, SCROLLING_BOT);

        // The site key is positional and the report and the rewrite parse the sources twice, so this also
        // asserts the one thing nothing else would notice: that the key survives the second parse.
        Map<CallSite, Integer> picks = new HashMap<>();
        picks.put(siteWhoseTextContains(r.splits().getFirst(), "-3").site(), 1);

        String rewritten = rewriteOver(tmp, before, after, SCROLLING_BOT, picks);
        assertTrue(rewritten.contains("Mouse.scrollUp(3);"), rewritten);
        assertTrue(rewritten.contains("Mouse.scrollDown(-3);"), rewritten);
        assertTrue(rewritten.contains("Mouse.scrollUp(1);"), rewritten);
    }

    @Test
    void aPickThatMatchesNoSiteLeavesThatSiteOnItsDefault(@TempDir Path tmp) throws IOException {
        Map<String, String> before = oldMouse(SPLIT_POINTER);
        Map<String, String> after = newMouse(CLAIMS_SCROLL);

        // A key from a file that is not in this project: the correct degradation is the upgrade the user
        // would have got by not choosing, and it is asserted rather than assumed.
        Map<CallSite, Integer> picks = Map.of(new CallSite("Nowhere.java", 4, "Mouse.scroll(-3)", 77), 1);

        String rewritten = rewriteOver(tmp, before, after, SCROLLING_BOT, picks);
        assertEquals(3, occurrences(rewritten, "Mouse.scrollUp("), rewritten);
        assertFalse(rewritten.contains("scrollDown"), rewritten);
    }

    // -------------------------------------------------------------------------
    // A split whose candidates return different things
    // -------------------------------------------------------------------------

    /** {@code Text.read()} returns a String and splits into one that still does and one that does not. */
    private static Map<String, String> oldTextSplit() {
        Map<String, String> m = new HashMap<>(SdkFixtures.withPointers(Map.of()));
        m.put("Text", """
                package %s;
                public class Text {
                    @Deprecated
                    @ReplacedBy(value = {"com.botmaker.sdk.api.Text#line", "com.botmaker.sdk.api.Text#count"},
                                whens = {"when you want the text", "when you want how many"})
                    public static String read() { return ""; }
                }
                """.formatted(PKG));
        return m;
    }

    /** The survivors. {@code lineReturns} is what the first one gives back — String for a candidate that fits. */
    private static Map<String, String> newTextSplit(String countReturns) {
        Map<String, String> m = new HashMap<>(SdkFixtures.withPointers(Map.of()));
        String lineReturns = "boolean".equals(countReturns) ? "boolean" : "String";
        m.put("Text", """
                package %s;
                public class Text {
                    @Replaces("com.botmaker.sdk.api.Text#read@1.0.0")
                    public static %s line() { return %s; }
                    @Replaces("com.botmaker.sdk.api.Text#read@1.0.0")
                    public static %s count() { return %s; }
                }
                """.formatted(PKG, lineReturns, zeroOf(lineReturns), countReturns, zeroOf(countReturns)));
        return m;
    }

    private static String zeroOf(String type) {
        return switch (type) {
            case "int" -> "0";
            case "boolean" -> "false";
            default -> "\"\"";
        };
    }

    private static final String TEXT_BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    Text.read();
                    String s = Text.read();
                    System.out.println(s);
                }
            }
            """;

    private static final String STORED_TEXT_BOT = """
            package com.mybot;
            public class Subject {
                public void run() {
                    String s = Text.read();
                    System.out.println(s);
                }
            }
            """;

    private static Site siteWhoseTextContains(Choice choice, String fragment) {
        List<Site> matching = choice.sites().stream()
                .filter(s -> s.site().text().contains(fragment))
                .toList();
        assertEquals(1, matching.size(),
                "expected exactly one site whose text holds \"" + fragment + "\": " + choice.sites());
        return matching.getFirst();
    }

    private static Site siteAtLine(Choice choice, int line) {
        List<Site> matching = choice.sites().stream().filter(s -> s.site().line() == line).toList();
        assertEquals(1, matching.size(),
                "expected exactly one site on line " + line + ": " + choice.sites());
        return matching.getFirst();
    }
}
