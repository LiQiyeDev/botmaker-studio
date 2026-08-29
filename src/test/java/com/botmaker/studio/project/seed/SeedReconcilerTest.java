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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the seed surface that runs forever: a file the user now owns, kept in step with the model
 * without its body being touched.
 *
 * <p>Every case here starts from what {@link SeedWriter} actually produced and then edits it the way a user
 * would, because the thing being asserted is a property of the pair — <em>write once, maintain the marks</em>
 * — and a fixture standing in for the written file would be asserting the reconciler against itself.
 */
class SeedReconcilerTest {

    private static final String PIN = "1.2.0";
    private static final String ACTIVITY_PATH = "src/main/java/{package}/activities/{name}.java";

    private static ScaffoldPlan.PlannedFile activity(String name, List<String> outcomes) {
        ScaffoldCatalog catalog = new SdkPlugin().scaffold(PIN);
        return ScaffoldPlan.of(catalog, "com.mybot",
                        Map.of(ACTIVITY_PATH, List.of(new Seeding("k", name, Map.of("outcomes", outcomes)))))
                .files().get(0);
    }

    /** The file as it lands, then as a user has made it theirs. */
    private static String userEdited(String written) {
        return written.replace("return Outcome.NEXT;",
                "Debug.log(\"mining\");\n        if (bagIsFull()) return Outcome.BAG_FULL;\n"
                        + "        return Outcome.NEXT;");
    }

    // ---- the hole ---------------------------------------------------------------------------------------

    @Test
    void aNewConstantAppearsAndTheBodyIsUntouched() {
        String written = SeedWriter.render(activity("Mining", List.of("NEXT")));
        String edited = userEdited(written);

        String synced = SeedReconciler.reconcile(edited, activity("Mining", List.of("NEXT", "BAG_FULL")));

        assertTrue(synced.contains("BAG_FULL"), synced);
        assertTrue(synced.contains("Debug.log(\"mining\")"), "the user's body is the whole point of the file");
        assertTrue(synced.contains("if (bagIsFull())"), synced);
    }

    @Test
    void aRemovedConstantGoes() {
        String written = SeedWriter.render(activity("Mining", List.of("NEXT", "NO_ORE")));

        String synced = SeedReconciler.reconcile(written, activity("Mining", List.of("NEXT")));

        assertFalse(synced.contains("NO_ORE"), synced);
    }

    @Test
    void constantsKeepTheOrderTheySupplied() {
        String written = SeedWriter.render(activity("Mining", List.of("NEXT")));

        String synced = SeedReconciler.reconcile(written, activity("Mining", List.of("BAG_FULL", "NEXT")));

        // On the declaration, not on the file: the seed's own javadoc names NEXT well before the enum does.
        assertTrue(synced.contains("enum Outcome { BAG_FULL, NEXT }"), synced);
    }

    @Test
    void anEnumTheUserDeletedComesBack() {
        // Not a courtesy: the class's own type parameter names it, so a file without it does not compile and
        // the user cannot have meant to be left there.
        String written = SeedWriter.render(activity("Mining", List.of("NEXT")));
        String gutted = written.replaceFirst("(?s)public enum Outcome \\{.*?}", "");

        String synced = SeedReconciler.reconcile(gutted, activity("Mining", List.of("NEXT")));

        assertTrue(synced.contains("enum Outcome"), synced);
        assertTrue(synced.contains("NEXT"), synced);
    }

    // ---- doing nothing ----------------------------------------------------------------------------------

    @Test
    void aFileThatAlreadyAgreesIsNotRewritten() {
        String written = SeedWriter.render(activity("Mining", List.of("NEXT", "BAG_FULL")));

        assertSame(written, SeedReconciler.reconcile(written, activity("Mining", List.of("NEXT", "BAG_FULL"))));
    }

    @Test
    void reconcilingTwiceChangesNothingTheSecondTime() {
        String edited = userEdited(SeedWriter.render(activity("Mining", List.of("NEXT"))));
        ScaffoldPlan.PlannedFile grown = activity("Mining", List.of("NEXT", "BAG_FULL"));

        String once = SeedReconciler.reconcile(edited, grown);
        assertEquals(once, SeedReconciler.reconcile(once, grown));
    }

    // ---- refusing ---------------------------------------------------------------------------------------

    @Test
    void aFileThatDoesNotParseIsLeftForTheCompiler() {
        String broken = "package com.mybot.activities; public class Mining { oops";

        assertSame(broken, SeedReconciler.reconcile(broken, activity("Mining", List.of("NEXT"))));
    }

    @Test
    void aFileWhoseTypeIsSomebodyElsesIsLeftAlone() {
        // JDT recovers aggressively, so "it parsed" is not evidence that this is the file the plan describes.
        String other = SeedWriter.render(activity("Fishing", List.of("NEXT")));

        assertSame(other, SeedReconciler.reconcile(other, activity("Mining", List.of("NEXT", "BAG_FULL"))));
    }

    @Test
    void aHoleNothingSuppliedLeavesTheFilesOwnConstants() {
        String written = SeedWriter.render(activity("Mining", List.of("NEXT", "BAG_FULL")));
        ScaffoldPlan.PlannedFile silent = ScaffoldPlan.of(new SdkPlugin().scaffold(PIN), "com.mybot",
                Map.of(ACTIVITY_PATH, List.of(new Seeding("k", "Mining")))).files().get(0);

        assertSame(written, SeedReconciler.reconcile(written, silent));
    }

    @Test
    void nullsAnswerThemselves() {
        assertEquals(null, SeedReconciler.reconcile(null, activity("Mining", List.of("NEXT"))));
        assertEquals("x", SeedReconciler.reconcile("x", null));
    }
}
