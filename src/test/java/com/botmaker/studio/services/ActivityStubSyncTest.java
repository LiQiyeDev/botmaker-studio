package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ActivityDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping a hand-written activity stub in step with the flow editor â€” and carrying a project written against
 * the old {@code void run()} across to {@code Outcome run()}.
 *
 * <p>What every test here is really guarding is that the user's own code survives: the stub file is theirs,
 * and the only reason Studio touches it at all is the two generated pieces inside it.
 */
class ActivityStubSyncTest {

    /** A stub as an older Studio wrote it: no outcome enum, raw superclass, {@code void run()}. */
    private static final String LEGACY_STUB = """
            package com.actbot.activities;

            import com.actbot.Activities;
            import com.botmaker.sdk.api.bot.Activity;

            public class Mining extends Activity {

                @Override
                public boolean isEnabled() {
                    return Activities.Mining;
                }

                @Override
                public void run() {
                    ImageClicker.click(ore);
                }
            }
            """;

    private static ActivityDefinition mining(String... outcomes) {
        return ActivityDefinition.create("Mining", "").withOutcomes(List.of(outcomes));
    }

    @Test
    void aLegacyStubGainsItsOutcomeEnumSuperclassAndReturn() {
        String synced = ActivityStubSync.syncSource(LEGACY_STUB, mining("BAG_FULL"));

        assertTrue(synced.contains("enum Outcome"), synced);
        assertTrue(synced.contains("NEXT"), "the implicit outcome is always generated:\n" + synced);
        assertTrue(synced.contains("BAG_FULL"), synced);
        assertTrue(synced.contains("extends Activity<Mining.Outcome>"), synced);
        assertTrue(synced.contains("public Outcome run()"), synced);
        assertTrue(synced.contains("return Outcome.NEXT;"),
                "a body written for void must actually return something now:\n" + synced);
        assertTrue(synced.contains("ImageClicker.click(ore);"), "the user's work survives:\n" + synced);
    }

    @Test
    void aBareReturnBecomesAReturnOfTheImplicitOutcome() {
        String stub = LEGACY_STUB.replace("ImageClicker.click(ore);", """
                if (!ready) {
                            return;
                        }
                        ImageClicker.click(ore);""");

        String synced = ActivityStubSync.syncSource(stub, mining());

        assertFalse(synced.contains("return;"), "a bare return no longer compiles:\n" + synced);
        assertEquals(2, synced.split("return Outcome.NEXT;", -1).length - 1,
                "the early return and the fall-off-the-end both need one:\n" + synced);
    }

    @Test
    void aBodyThatAlreadyEndsInAReturnGetsNoSecondOne() {
        String stub = LEGACY_STUB.replace("ImageClicker.click(ore);", "return;");

        String synced = ActivityStubSync.syncSource(stub, mining());

        assertEquals(1, synced.split("return Outcome.NEXT;", -1).length - 1,
                "an unreachable trailing return would be noise:\n" + synced);
    }

    @Test
    void addingAnOutcomeUpdatesTheEnumWithoutTouchingTheBody() {
        String migrated = ActivityStubSync.syncSource(LEGACY_STUB, mining("BAG_FULL"));

        String synced = ActivityStubSync.syncSource(migrated, mining("BAG_FULL", "NO_ORE"));

        assertTrue(synced.contains("NO_ORE"), synced);
        assertTrue(synced.contains("ImageClicker.click(ore);"), synced);
        assertEquals(1, synced.split("enum Outcome", -1).length - 1, "one enum, not two:\n" + synced);
    }

    @Test
    void removingAnOutcomeRemovesItsConstant() {
        String withTwo = ActivityStubSync.syncSource(LEGACY_STUB, mining("BAG_FULL", "NO_ORE"));

        String synced = ActivityStubSync.syncSource(withTwo, mining("BAG_FULL"));

        assertFalse(synced.contains("NO_ORE"),
                "the canvas no longer offers that port, so the constant must go:\n" + synced);
        assertTrue(synced.contains("BAG_FULL"), synced);
    }

    @Test
    void anAlreadyCurrentStubIsNotRewrittenAtAll() {
        // Every save calls this; rewriting an unchanged file would churn the user's editor and their VCS.
        String synced = ActivityStubSync.syncSource(LEGACY_STUB, mining("BAG_FULL"));

        assertEquals(synced, ActivityStubSync.syncSource(synced, mining("BAG_FULL")));
    }

    @Test
    void aFileItCannotUnderstandIsLeftExactlyAsItIs() {
        // Better to let the compiler point at a mangled file than to mangle it further.
        String notJava = "this is not a class at all {{{";
        assertEquals(notJava, ActivityStubSync.syncSource(notJava, mining()));
    }

    @Test
    void aStubWrittenWhenTheImplicitOutcomeWasCalledDefaultStillCompiles() {
        // syncOutcomeEnum replaces the constants as a set, so DEFAULT leaves the enum — and a body still
        // saying "return Outcome.DEFAULT;" would then name a constant that doesn't exist.
        String stub = ActivityStubSync.syncSource(LEGACY_STUB, mining("BAG_FULL"))
                .replace("NEXT, BAG_FULL", "DEFAULT, BAG_FULL")
                .replace("return Outcome.NEXT;", "return Outcome.DEFAULT;");

        String synced = ActivityStubSync.syncSource(stub, mining("BAG_FULL"));

        assertFalse(synced.contains("DEFAULT"), "nothing may still refer to the old spelling:\n" + synced);
        assertTrue(synced.contains("return Outcome.NEXT;"), synced);
    }

    @Test
    void anActivityThatReallyDeclaresDefaultKeepsIt() {
        // Only a *legacy* DEFAULT is rewritten. If the user named an outcome DEFAULT it is theirs, and
        // renaming its references would silently re-route their flow.
        String stub = ActivityStubSync.syncSource(LEGACY_STUB, mining("DEFAULT"))
                .replace("return Outcome.NEXT;", "return Outcome.DEFAULT;");

        String synced = ActivityStubSync.syncSource(stub, mining("DEFAULT"));

        assertTrue(synced.contains("return Outcome.DEFAULT;"), synced);
    }

    @Test
    void aBodyWithNoReturnAtAllGainsTheTerminalOne() {
        // Not just the void migration: an already-migrated stub whose user deleted the last return has to get
        // it back, because the editor's contract is that run() always ends by reporting something.
        String stub = ActivityStubSync.syncSource(LEGACY_STUB, mining())
                .replace("        return Outcome.NEXT;\n", "");

        String synced = ActivityStubSync.syncSource(stub, mining());

        assertTrue(synced.contains("return Outcome.NEXT;"), synced);
    }

    @Test
    void aReturnInsideALambdaIsNotTheActivitysReturn() {
        String stub = LEGACY_STUB.replace("ImageClicker.click(ore);",
                "ImageFinder.whileExists(ore, () -> { if (done) return; click(); });");

        String synced = ActivityStubSync.syncSource(stub, mining());

        assertTrue(synced.contains("if (done) return;"),
                "the lambda's bare return is void and must stay untouched:\n" + synced);
        assertEquals(1, synced.split("return Outcome.NEXT;", -1).length - 1,
                "only run() itself gains one:\n" + synced);
    }
}

