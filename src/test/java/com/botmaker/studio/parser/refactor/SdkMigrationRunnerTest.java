package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.MemberRename;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.Outcome;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.Removal;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.Repairs;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.TypeRename;
import com.botmaker.studio.project.ProjectFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The applier: what an SDK upgrade actually does to a bot's source.
 *
 * <p>The model under test is "make it compile, then have the user review it". A renamed type is renamed
 * file-wide; a member that is simply gone becomes a default value where its result was used, and a deleted
 * statement where it wasn't. Nothing is ever pointed at a different member — the tests that used to cover
 * that are gone with the fix engine, and their absence is the point.
 *
 * <p>Every test that expects a rewrite asserts the result <b>parses</b>, on top of whatever text it looks for.
 * That is not ceremony — source no compiler accepts is the one failure mode this whole feature exists to
 * prevent, and it is invisible to an assertion that only greps for a new name.
 *
 * <p>The refusals get as much room as the successes, deliberately. A migration that half-lands is worse than
 * one that never starts, so "this returned a reason and wrote nothing" is a result worth pinning down.
 */
class SdkMigrationRunnerTest {

    private static final String PKG = "com.botmaker.sdk.api";

    private static final Set<String> SDK_TYPES =
            Set.of("Mouse", "Key", "Tolerance", "Precision", "Vision", "Direction");

    private static final Map<String, List<String>> FIELD_OWNERS = Map.of(
            "ENTER", List.of("Key"),
            "TIGHT", List.of("Tolerance"),
            "UP", List.of("Direction"));

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private static ProjectFile file(String name, String source) {
        return new ProjectFile(Path.of(name + ".java"), source);
    }

    private static Outcome run(Repairs repairs, ProjectFile... editable) {
        return SdkMigrationRunner.run(repairs, List.of(editable), List.of(), SDK_TYPES, FIELD_OWNERS,
                null, null);
    }

    private static Repairs removals(Removal... removals) {
        return new Repairs(List.of(), List.of(), List.of(removals));
    }

    private static Repairs renames(TypeRename... types) {
        return new Repairs(List.of(types), List.of(), List.of());
    }

    /** The new source of {@code file}, or null when the run left it untouched. */
    private static String textOf(Outcome outcome, ProjectFile file) {
        assertNull(outcome.refusal(), "the migration was refused: " + outcome.refusal());
        return outcome.files().stream()
                .filter(rewritten -> rewritten.file() == file)
                .map(CallMigrator.Rewritten::newSource)
                .findFirst().orElse(null);
    }

    private static String rewritten(Outcome outcome, ProjectFile file) {
        String source = textOf(outcome, file);
        assertNotNull(source, "expected this file to be rewritten");
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(source)),
                "the rewritten source must parse:\n" + source);
        return source;
    }

    // -------------------------------------------------------------------------
    // A member that is gone
    // -------------------------------------------------------------------------

    @Test
    void aRemovedMethodWhoseResultIsUsedBecomesTheDefaultOfItsOldReturnType() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        boolean hit = Vision.find("target");
                        int n = Vision.count("target");
                    }
                }
                """);
        String source = rewritten(run(removals(
                new Removal("Vision", "find", 1, "boolean"),
                new Removal("Vision", "count", 1, "int")), bot), bot);

        assertTrue(source.contains("boolean hit = false;"), source);
        assertTrue(source.contains("int n = 0;"), source);
        assertFalse(source.contains("Vision."), "no call to the removed member survives:\n" + source);
    }

    @Test
    void aRemovedConstantBecomesADefaultToo() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    Object held = Key.ENTER;
                }
                """);
        String source = rewritten(run(removals(
                new Removal("Key", "ENTER", SdkReferences.FIELD_READ, "Key")), bot), bot);
        assertTrue(source.contains("Object held = null;"), source);
    }

    @Test
    void aRemovedMemberCalledForItsEffectHasItsWholeStatementDeleted() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2);
                        Mouse.move(3, 4);
                    }
                }
                """);
        // Replacing the expression would leave `0;` for a value-returning member and nothing writable at all
        // for a void one, so a statement call goes whole either way.
        String source = rewritten(run(removals(new Removal("Mouse", "click", 2, "void")), bot), bot);
        assertFalse(source.contains("Mouse.click"), source);
        assertTrue(source.contains("Mouse.move(3, 4);"), "the surviving call is untouched:\n" + source);
    }

    @Test
    void anOverloadThatSurvivesIsLeftAloneBecauseRemovalsMatchOnArity() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2);
                        Mouse.click(1, 2, 3);
                    }
                }
                """);
        String source = rewritten(run(removals(new Removal("Mouse", "click", 3, "void")), bot), bot);
        assertTrue(source.contains("Mouse.click(1, 2);"), "the two-argument call stands:\n" + source);
        assertFalse(source.contains("Mouse.click(1, 2, 3)"), "the three-argument one goes:\n" + source);
    }

    // -------------------------------------------------------------------------
    // A type that was paired under a new name
    // -------------------------------------------------------------------------

    @Test
    void aRenamedTypeTakesEveryUseWithItIncludingOnesNoCallScanFinds() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                import %s.Tolerance;
                class Bot {
                    private Tolerance held;
                    void run() {
                        Tolerance t = Tolerance.of(3);
                        held = (Tolerance) t;
                    }
                }
                """.formatted(PKG));
        String source = rewritten(
                run(renames(new TypeRename(PKG + ".Tolerance", PKG + ".Precision")), bot), bot);

        assertFalse(source.contains("Tolerance"), "the old name survives nowhere:\n" + source);
        assertTrue(source.contains("import " + PKG + ".Precision;"), source);
        // The field, the local's declared type and the cast are all uses no call scan would have recorded.
        assertTrue(source.contains("private Precision held;"), source);
        assertTrue(source.contains("(Precision) t"), source);
    }

    /**
     * The reason each file is swept twice, members before types. Both edits land on the same call, and
     * {@code ASTRewrite} cannot replace a node and retarget something inside it in one pass.
     */
    @Test
    void aTypeThatWasRenamedAndAMemberThatWentAreBothRepairedInOneFile() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                import %s.Tolerance;
                class Bot {
                    void run() {
                        Tolerance kept = Tolerance.of(3);
                        boolean gone = Tolerance.matches(kept);
                    }
                }
                """.formatted(PKG));
        Repairs repairs = new Repairs(
                List.of(new TypeRename(PKG + ".Tolerance", PKG + ".Precision")),
                List.of(),
                List.of(new Removal("Tolerance", "matches", 1, "boolean")));
        String source = rewritten(run(repairs, bot), bot);

        assertTrue(source.contains("Precision kept = Precision.of(3);"), source);
        assertTrue(source.contains("boolean gone = false;"), source);
        assertFalse(source.contains("Tolerance"), source);
    }

    @Test
    void aMemberRenameIsAppliedAtEveryCallSiteOfThatType() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2);
                        Mouse.click(3, 4);
                    }
                }
                """);
        Repairs repairs = new Repairs(List.of(), List.of(new MemberRename("Mouse", "click", "tap")),
                List.of());
        String source = rewritten(run(repairs, bot), bot);
        assertFalse(source.contains("Mouse.click"), source);
        assertEquals(2, source.split("Mouse\\.tap", -1).length - 1, "both calls move:\n" + source);
    }

    @Test
    void aFileTheMigrationDoesNotTouchIsNotRewrittenToItself() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile other = file("Other", "package com.mybot;\nclass Other { void run() {} }\n");
        Outcome outcome = run(removals(new Removal("Mouse", "click", 2, "void")), bot, other);
        assertEquals(1, outcome.files().size(), "only the file that changed is handed back");
    }

    @Test
    void aRepairThatMatchesNothingInTheProjectChangesNoFile() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        Outcome outcome = run(removals(new Removal("Vision", "find", 1, "boolean")), bot);
        assertFalse(outcome.isRefusal());
        assertTrue(outcome.files().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Refusals — nothing, or all of it
    // -------------------------------------------------------------------------

    @Test
    void aRemovedVoidMemberWithNoStatementToDeleteRefusesTheWholeMigration() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    Runnable r = () -> Mouse.click(1, 2);
                }
                """);
        Outcome outcome = run(removals(new Removal("Mouse", "click", 2, "void")), bot);
        // There is no value to write and no statement to remove; writing something that looks right and
        // isn't is the one outcome worse than saying so.
        assertTrue(outcome.isRefusal(), "a void call in a lambda body has nothing to stand in for it");
        assertTrue(outcome.files().isEmpty(), "a refusal writes nothing");
    }

    @Test
    void anAmbiguousCaseLabelStopsTheUpgradeRatherThanGuessingAnEnum() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run(Object d) {
                        switch (d) {
                            case UP -> System.out.println("up");
                            default -> { }
                        }
                    }
                }
                """);
        Map<String, List<String>> ambiguous = Map.of("UP", List.of("Direction", "Heading"));
        Outcome outcome = SdkMigrationRunner.run(removals(new Removal("Direction", "UP", -1, "Direction")),
                List.of(bot), List.of(), SDK_TYPES, ambiguous, null, null);
        assertTrue(outcome.isRefusal(), "which enum the label names cannot be told from the source");
        assertTrue(outcome.files().isEmpty());
    }

    @Test
    void aFileThatDoesNotParseStopsTheUpgradeAndNamesItself() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile broken = file("Broken", "package com.mybot;\nclass Broken { void run( {\n");
        Outcome outcome = run(removals(new Removal("Mouse", "click", 2, "void")), bot, broken);
        assertTrue(outcome.isRefusal());
        assertTrue(outcome.refusal().contains("Broken"), outcome.refusal());
        assertTrue(outcome.files().isEmpty(), "not even the file that would have rewritten cleanly");
    }

    @Test
    void aGeneratedFileUsingTheChangedMemberRefusesTheUpgradeRatherThanRewritingIt() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile driver = file("FlowDriver",
                "package com.mybot;\nclass FlowDriver { void run() { Mouse.click(0, 0); } }\n");
        Outcome outcome = SdkMigrationRunner.run(removals(new Removal("Mouse", "click", 2, "void")),
                List.of(bot), List.of(driver), SDK_TYPES, FIELD_OWNERS, null, null);
        assertTrue(outcome.isRefusal(), "scaffolding is Studio's to write, not an upgrade's");
        assertTrue(outcome.refusal().contains("FlowDriver"), outcome.refusal());
        assertTrue(outcome.files().isEmpty());
    }

    @Test
    void aGeneratedFileUsingARenamedTypeRefusesTooEvenThoughItWouldRewriteCleanly() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { Object t = Tolerance.TIGHT; }\n");
        ProjectFile templates = file("Templates",
                "package com.mybot;\nclass Templates { Object t = Tolerance.TIGHT; }\n");
        Outcome outcome = SdkMigrationRunner.run(
                renames(new TypeRename(PKG + ".Tolerance", PKG + ".Precision")),
                List.of(bot), List.of(templates), SDK_TYPES, FIELD_OWNERS, null, null);
        assertTrue(outcome.isRefusal(), outcome.refusal());
        assertTrue(outcome.refusal().contains("Templates"), outcome.refusal());
    }
}
