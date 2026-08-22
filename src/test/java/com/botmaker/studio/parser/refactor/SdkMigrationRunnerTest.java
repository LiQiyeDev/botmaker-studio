package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.Fix;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner.Outcome;
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
 * The applier: what the SDK's declared repairs actually do to a bot's source.
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

    private static Outcome run(List<Fix> fixes, ProjectFile... editable) {
        return SdkMigrationRunner.run(fixes, List.of(editable), List.of(), SDK_TYPES, FIELD_OWNERS, null, null);
    }

    /** The new source of {@code file}, or null when the pass left it untouched. */
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

    private static Fix rename(String version, String typeFqn, String member, String to) {
        return new Fix(version, typeFqn, member, -1, SdkMigrationRunner.RENAME_METHOD,
                to, null, -1, List.of(), null, null);
    }

    // -------------------------------------------------------------------------
    // The straightforward repairs
    // -------------------------------------------------------------------------

    @Test
    void aRenamedMethodIsRewrittenAtEveryCallSite() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2);
                        Mouse.click(3, 4);
                    }
                }
                """);
        String source = rewritten(run(List.of(rename("2.0.0", PKG + ".Mouse", "click", "tap")), bot), bot);
        assertFalse(source.contains("Mouse.click"), "no call should still say click:\n" + source);
        assertEquals(2, source.split("Mouse\\.tap", -1).length - 1, "both calls move:\n" + source);
    }

    @Test
    void aRenameScopedToOneArityLeavesTheOtherOverloadAlone() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2);
                        Mouse.click(1, 2, 3);
                    }
                }
                """);
        Fix onlyTheTriple = new Fix("2.0.0", PKG + ".Mouse", "click", 3, SdkMigrationRunner.RENAME_METHOD,
                "clickAfter", null, -1, List.of(), null, null);
        String source = rewritten(run(List.of(onlyTheTriple), bot), bot);
        assertTrue(source.contains("Mouse.click(1, 2);"), "the two-argument call stands:\n" + source);
        assertTrue(source.contains("Mouse.clickAfter(1, 2, 3);"), "the three-argument one moves:\n" + source);
    }

    @Test
    void aDroppedArgumentLeavesTheOthersInOrder() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2, 3);
                    }
                }
                """);
        Fix drop = new Fix("2.0.0", PKG + ".Mouse", "click", -1, SdkMigrationRunner.DROP_ARGUMENT,
                null, null, 1, List.of(), null, null);
        assertTrue(rewritten(run(List.of(drop), bot), bot).contains("Mouse.click(1, 3)"));
    }

    @Test
    void reorderedArgumentsFollowTheGivenOrder() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.click(1, 2, 3);
                    }
                }
                """);
        Fix reorder = new Fix("2.0.0", PKG + ".Mouse", "click", -1, SdkMigrationRunner.REORDER_ARGUMENTS,
                null, null, -1, List.of(2, 0, 1), null, null);
        assertTrue(rewritten(run(List.of(reorder), bot), bot).contains("Mouse.click(3, 1, 2)"));
    }

    @Test
    void anInsertedArgumentLandsAtTheGivenIndexAndBringsItsImport() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Vision.find("target");
                    }
                }
                """);
        Fix insert = new Fix("2.0.0", PKG + ".Vision", "find", -1, SdkMigrationRunner.INSERT_ARGUMENT,
                null, null, 1, List.of(), "Precision.TIGHT", PKG + ".Precision");
        String source = rewritten(run(List.of(insert), bot), bot);
        assertTrue(source.contains("Vision.find(\"target\", Precision.TIGHT)"), source);
        assertTrue(source.contains("import " + PKG + ".Precision;"), "the literal's type is imported:\n" + source);
    }

    @Test
    void aConstantMovesToAnotherTypeAndBringsItsImport() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    Object p = Tolerance.TIGHT;
                }
                """);
        Fix move = new Fix("2.0.0", PKG + ".Tolerance", "TIGHT", -1, SdkMigrationRunner.MOVE_MEMBER,
                null, PKG + ".Precision", -1, List.of(), null, null);
        String source = rewritten(run(List.of(move), bot), bot);
        assertTrue(source.contains("Precision.TIGHT"), source);
        assertTrue(source.contains("import " + PKG + ".Precision;"), source);
    }

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
        Fix renameType = new Fix("2.0.0", PKG + ".Tolerance", "", -1, SdkMigrationRunner.RENAME_TYPE,
                PKG + ".Precision", null, -1, List.of(), null, null);
        String source = rewritten(run(List.of(renameType), bot), bot);
        assertFalse(source.contains("Tolerance"), "the old name survives nowhere:\n" + source);
        assertTrue(source.contains("import " + PKG + ".Precision;"), source);
        // The field, the local's declared type and the cast are all uses no call scan would have recorded.
        assertTrue(source.contains("private Precision held;"), source);
        assertTrue(source.contains("(Precision) t"), source);
    }

    // -------------------------------------------------------------------------
    // Ordered replay
    // -------------------------------------------------------------------------

    @Test
    void twoVersionsRenamingTheSameMethodComposeInOneJump() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.foo(1);
                    }
                }
                """);
        Outcome outcome = run(List.of(
                rename("2.0.0", PKG + ".Mouse", "foo", "bar"),
                rename("3.0.0", PKG + ".Mouse", "bar", "baz")), bot);
        // Not a chain that was followed: the 2.0.0 pass wrote `bar`, and the 3.0.0 pass then found exactly what
        // its own entry names, having re-scanned the source the first pass produced.
        assertTrue(rewritten(outcome, bot).contains("Mouse.baz(1)"));
    }

    @Test
    void aRenameUndoneByALaterVersionIsANoOpRatherThanALoop() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                class Bot {
                    void run() {
                        Mouse.a(1);
                    }
                }
                """);
        // A fixpoint loop — re-running the whole set until nothing changes — never terminates on this pair.
        Outcome outcome = run(List.of(
                rename("2.0.0", PKG + ".Mouse", "a", "b"),
                rename("3.0.0", PKG + ".Mouse", "b", "a")), bot);
        assertNull(textOf(outcome, bot), "a → b → a leaves the file exactly as it was");
    }

    @Test
    void aFileTheMigrationDoesNotTouchIsNotRewrittenToItself() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile other = file("Other", "package com.mybot;\nclass Other { void run() {} }\n");
        Outcome outcome = run(List.of(rename("2.0.0", PKG + ".Mouse", "click", "tap")), bot, other);
        assertEquals(1, outcome.files().size(), "only the file that changed is handed back");
    }

    // -------------------------------------------------------------------------
    // Refusals — nothing, or all of it
    // -------------------------------------------------------------------------

    @Test
    void aConstantUsedAsACaseLabelCannotBeMovedAndRefusesTheWholeMigration() {
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
        Fix move = new Fix("2.0.0", PKG + ".Direction", "UP", -1, SdkMigrationRunner.MOVE_MEMBER,
                null, PKG + ".Heading", -1, List.of(), null, null);
        Outcome outcome = run(List.of(move), bot);
        // The label names its enum nowhere — the type lives on the switch expression — so there is no qualifier
        // to retarget, and inventing one would compile against the wrong class.
        assertTrue(outcome.isRefusal(), "a case label has no type to move");
        assertTrue(outcome.files().isEmpty(), "a refusal writes nothing");
    }

    @Test
    void aFixThisStudioCannotMakeSenseOfRefusesRatherThanSkippingTheEntry() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        Fix nonsense = new Fix("2.0.0", PKG + ".Mouse", "click", -1, SdkMigrationRunner.DROP_ARGUMENT,
                null, null, 9, List.of(), null, null);
        Outcome outcome = run(List.of(nonsense), bot);
        // Skipping it would leave the user with an upgrade that silently did less than it said.
        assertTrue(outcome.isRefusal());
        assertTrue(outcome.files().isEmpty());
    }

    @Test
    void aFileThatDoesNotParseStopsTheUpgradeAndNamesItself() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile broken = file("Broken", "package com.mybot;\nclass Broken { void run( {\n");
        Outcome outcome = run(List.of(rename("2.0.0", PKG + ".Mouse", "click", "tap")), bot, broken);
        assertTrue(outcome.isRefusal());
        assertTrue(outcome.refusal().contains("Broken"), outcome.refusal());
        assertTrue(outcome.files().isEmpty(), "not even the file that would have rewritten cleanly");
    }

    @Test
    void aGeneratedFileUsingTheChangedMemberRefusesTheUpgradeRatherThanRewritingIt() {
        ProjectFile bot = file("Bot", "package com.mybot;\nclass Bot { void run() { Mouse.click(1, 2); } }\n");
        ProjectFile driver = file("FlowDriver",
                "package com.mybot;\nclass FlowDriver { void run() { Mouse.click(0, 0); } }\n");
        Outcome outcome = SdkMigrationRunner.run(List.of(rename("2.0.0", PKG + ".Mouse", "click", "tap")),
                List.of(bot), List.of(driver), SDK_TYPES, FIELD_OWNERS, null, null);
        assertTrue(outcome.isRefusal(), "scaffolding is Studio's to write, not an upgrade's");
        assertTrue(outcome.refusal().contains("FlowDriver"), outcome.refusal());
        assertTrue(outcome.files().isEmpty());
    }

    @Test
    void aStaticallyImportedConstantMovesByRetargetingTheImport() {
        ProjectFile bot = file("Bot", """
                package com.mybot;
                import static %s.Tolerance.TIGHT;
                class Bot {
                    Object p = TIGHT;
                }
                """.formatted(PKG));
        Fix move = new Fix("2.0.0", PKG + ".Tolerance", "TIGHT", -1, SdkMigrationRunner.MOVE_MEMBER,
                null, PKG + ".Precision", -1, List.of(), null, null);
        String source = rewritten(run(List.of(move), bot), bot);
        assertTrue(source.contains("import static " + PKG + ".Precision.TIGHT;"), source);
        assertTrue(source.contains("Object p = TIGHT;"), "the use itself is already right:\n" + source);
    }
}
