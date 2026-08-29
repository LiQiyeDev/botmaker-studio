package com.botmaker.studio.project.seed;

import com.botmaker.plugin.api.catalog.ScaffoldCatalog;
import com.botmaker.plugin.api.catalog.ScaffoldPlan;
import com.botmaker.plugin.api.scaffold.Seeding;
import com.botmaker.sdk.plugin.SdkPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host half of the seed surface: a plugin's real class, written as a file in somebody's project.
 *
 * <p>It runs against the <b>SDK's own seeds</b> rather than fixtures, and that is the point of the test
 * rather than a convenience. The contract carries no parser, so the marks say only that a name may be
 * replaced and an enum's constants may be replaced; where those sit in the text is this class's problem, and
 * the only honest subject is source a plugin actually ships.
 *
 * <p>What is asserted is what a written file must satisfy to be usable: it names the right package, the right
 * type, the right constants, and carries no annotation from a module a bot does not depend on. Not its
 * formatting — a seed's javadoc is written for whoever opens it, and a writer with opinions about prose would
 * be the templating language this surface exists instead of.
 */
class SeedWriterTest {

    private static final String PIN = "1.2.0";

    /** The plan for one project, straight from the plugin — no fixture in the middle. */
    private static ScaffoldPlan plan(Map<String, List<Seeding>> seedings) {
        ScaffoldCatalog catalog = new SdkPlugin().scaffold(PIN);
        assertEquals(List.of(), catalog.problems(), "precondition: the SDK's seeds are well formed");
        return ScaffoldPlan.of(catalog, "com.mybot", seedings);
    }

    private static ScaffoldPlan.PlannedFile activity(String name, List<String> outcomes) {
        return plan(Map.of("src/main/java/{package}/activities/{name}.java",
                List.of(new Seeding("k", name, Map.of("outcomes", outcomes))))).files().get(0);
    }

    private static ScaffoldPlan.PlannedFile goHome() {
        return plan(Map.of("src/main/java/{package}/GoHome.java",
                List.of(new Seeding("k", "GoHome")))).files().get(0);
    }

    // ---- the package ------------------------------------------------------------------------------------

    @Test
    void thePackageComesFromThePathTheFileLandsAt() {
        // Derived, never passed: a package declaration that disagrees with its directory is a file that does
        // not compile, and an activity lands one package deeper than the seeds beside the entry point.
        assertEquals("com.mybot.activities",
                SeedWriter.packageOf("src/main/java/com/mybot/activities/Mining.java"));
        assertEquals("com.mybot", SeedWriter.packageOf("src/main/java/com/mybot/GoHome.java"));
        assertEquals("", SeedWriter.packageOf("pom.xml"));
    }

    @Test
    void theWrittenFileDeclaresThatPackage() {
        assertTrue(SeedWriter.render(activity("Mining", List.of("NEXT"))).contains("package com.mybot.activities;"));
        assertTrue(SeedWriter.render(goHome()).contains("package com.mybot;"));
    }

    // ---- the name ---------------------------------------------------------------------------------------

    @Test
    void everyMentionOfTheSeedsOwnNameIsRewritten() {
        String source = SeedWriter.render(activity("Mining", List.of("NEXT")));

        assertFalse(source.contains("ActivityTemplate"), source);
        assertTrue(source.contains("public class Mining extends Activity<Mining.Outcome>"), source);
    }

    @Test
    void aSeedThatIsNotRenamedKeepsItsOwnName() {
        String source = SeedWriter.render(goHome());

        assertTrue(source.contains("public class GoHome extends Activity<GoHome.Outcome>"), source);
        assertTrue(source.contains("public static final GoHome INSTANCE = new GoHome();"), source);
    }

    // ---- the constants ----------------------------------------------------------------------------------

    @Test
    void theEnumGetsTheConstantsTheSeedingSupplied() {
        String source = SeedWriter.render(activity("Mining", List.of("NEXT", "BAG_FULL", "NO_ORE")));

        assertTrue(source.contains("NEXT"), source);
        assertTrue(source.contains("BAG_FULL"), source);
        assertTrue(source.contains("NO_ORE"), source);
    }

    @Test
    void aHoleNothingSuppliedKeepsTheSeedsOwnConstants() {
        // Not the same as supplying an empty list: saying nothing about a hole is what lets the seed compile
        // on its own, which is the entire argument for a seed being real source.
        ScaffoldPlan.PlannedFile file = plan(Map.of(
                "src/main/java/{package}/activities/{name}.java",
                List.of(new Seeding("k", "Mining")))).files().get(0);

        assertTrue(SeedWriter.render(file).contains("NEXT"));
    }

    // ---- the marks --------------------------------------------------------------------------------------

    @Test
    void noMarkAndNoMarkImportSurvives() {
        // The marks live in botmaker-studio-api, which a bot does not depend on. One left behind — or one
        // import left behind — is a file that does not compile in the user's project.
        for (String source : List.of(SeedWriter.render(activity("Mining", List.of("NEXT"))),
                SeedWriter.render(goHome()))) {
            assertFalse(source.contains("@Scaffold"), source);
            assertFalse(source.contains("@ClassName"), source);
            assertFalse(source.contains("@EnumValues"), source);
            assertFalse(source.contains("@Editable"), source);
            assertFalse(source.contains("com.botmaker.plugin.api.scaffold"), source);
        }
    }

    @Test
    void everyOtherAnnotationAndEveryCommentSurvives() {
        String source = SeedWriter.render(activity("Mining", List.of("NEXT")));

        assertTrue(source.contains("@Override"), "@Override is the plugin's statement about its own code");
        assertTrue(source.contains("TODO"), "the seed's prompt to the user is the reason they open the file");
        assertTrue(source.contains("SEED —"), "and so is the sentence saying it is theirs now");
    }

    @Test
    void theSdkImportsTheWrittenFileNeedsAreStillThere() {
        String source = SeedWriter.render(activity("Mining", List.of("NEXT")));

        assertTrue(source.contains("import com.botmaker.sdk.api.bot.Activity;"), source);
        assertTrue(source.contains("import com.botmaker.sdk.api.config.Wire;"), source);
        assertTrue(source.contains("Wire.enabled(name())"),
                "the tick is read at run time and needs no substitution — name() is the class's own");
    }

    // ---- refusing ---------------------------------------------------------------------------------------

    @Test
    void nullRatherThanAHalfWrittenFile() {
        assertNull(SeedWriter.render(null));
    }

    @Test
    void aPlanWithNothingInItWritesNothing() {
        assertEquals(List.of(), plan(Map.of()).files());
    }

    @Test
    void theWholeSetForOneProjectIsRenderable() {
        ScaffoldPlan whole = plan(Map.of(
                "src/main/java/{package}/GoHome.java", List.of(new Seeding("g", "GoHome")),
                "src/main/java/{package}/Popups.java", List.of(new Seeding("p", "Popups")),
                "src/main/java/{package}/activities/{name}.java",
                List.of(new Seeding("a1", "Mining", Map.of("outcomes", List.of("NEXT", "BAG_FULL"))),
                        new Seeding("a2", "Fishing", Map.of("outcomes", List.of("NEXT"))))));

        assertEquals(List.of(), whole.problems());
        assertEquals(4, whole.files().size());
        for (ScaffoldPlan.PlannedFile file : whole.files()) {
            assertNotNull(SeedWriter.render(file), file.path() + " did not render");
        }
    }
}
