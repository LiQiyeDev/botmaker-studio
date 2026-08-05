package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditorFixture;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writes behind the {@code Matches} switch, asserted on the <b>emitted source text</b> rather than on the
 * rewrite having been requested.
 *
 * <p>That distinction is the lesson this module already paid for once: the lambda variant switch reached its
 * handler correctly and still produced nothing, because JDT's {@code ASTRewrite} mishandled the property
 * pairing it used. A guarded arrow label is exactly that kind of shape — a {@code GuardedPattern} wrapping a
 * {@code TypePattern} on a rule-form {@code SwitchCase} — so every operation here is checked by reading the
 * source back.
 */
class MatchesSwitchHandlerTest {

    private static final String SOURCE = """
            package test;
            public class Subject {
                void run() {
                    ImageFinder.whileFindAny(POPUPS, found -> {
                        switch (found) {
                            case Matches m when m.hasAny(new ImageTemplate("popups/mail.png")) -> {
                                ImageClicker.click(m.best());
                            }
                            default -> {
                            }
                        }
                    });
                }
            }
            """;

    private static final String TWO_BRANCHES = """
            package test;
            public class Subject {
                void run() {
                    ImageFinder.whileFindAny(POPUPS, found -> {
                        switch (found) {
                            case Matches m when m.hasAny(new ImageTemplate("popups/mail.png")) -> {
                                ImageClicker.click(m.best());
                            }
                            case Matches m when m.hasAll(new ImageTemplate("popups/chest.png")) -> {
                                ImageClicker.click(m.best());
                            }
                            default -> {
                            }
                        }
                    });
                }
            }
            """;

    // ---- Finding the nodes ----

    private static SwitchStatement switchIn(EditorFixture fixture) {
        List<SwitchStatement> found = new ArrayList<>();
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override public boolean visit(SwitchStatement node) { found.add(node); return true; }
        });
        assertFalse(found.isEmpty(), "fixture should contain a switch");
        return found.getFirst();
    }

    /** The switch's non-default case labels, in source order. */
    private static List<SwitchCase> casesIn(EditorFixture fixture) {
        List<SwitchCase> cases = new ArrayList<>();
        for (Object o : switchIn(fixture).statements()) {
            if (o instanceof SwitchCase sc && !sc.isDefault()) cases.add(sc);
        }
        return cases;
    }

    /** Source with every space removed — the assertions are about structure, not JDT's indentation. */
    private static String dense(String code) {
        return code.replaceAll("\\s+", "");
    }

    // ---- Reading ----

    @Test
    void aGuardIsReadAsItsModeAndItsTemplates() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        MatchesSwitchHandler.Guard guard = MatchesSwitchHandler.guardOf(casesIn(fixture).getFirst()).orElse(null);

        assertNotNull(guard, "the seeded case should read as a guard");
        assertAll(
                () -> assertFalse(guard.all(), "hasAny is the any-of mode"),
                () -> assertEquals(List.of("popups/mail.png"), guard.paths()));
    }

    /**
     * The switch is claimed on the shape of its labels, not on binding resolution — Studio does not compile
     * against the SDK, so {@code Matches} routinely has no binding at edit time and a type-based test would
     * simply never fire.
     */
    @Test
    void onlyAGuardedSwitchIsClaimed() {
        EditorFixture guarded = new EditorFixture(SOURCE);
        assertTrue(MatchesSwitchHandler.isMatchesSwitch(switchIn(guarded)));

        EditorFixture ordinary = new EditorFixture("""
                package test;
                public class Subject {
                    void run() {
                        String s = "a";
                        switch (s) {
                            case "a":
                                break;
                        }
                    }
                }
                """);
        assertFalse(MatchesSwitchHandler.isMatchesSwitch(switchIn(ordinary)),
                "an ordinary colon-form switch must stay with SwitchBlock");
    }

    /**
     * A guard calling something else, or holding a template reference the chip row can't show, is source this
     * block would misrepresent — so it is not claimed, and the ordinary rendering keeps it intact.
     */
    @Test
    void anUnrecognisedGuardIsNotClaimed() {
        EditorFixture other = new EditorFixture(SOURCE.replace("m.hasAny(new ImageTemplate(\"popups/mail.png\"))",
                "m.isEmpty()"));
        assertFalse(MatchesSwitchHandler.isMatchesSwitch(switchIn(other)),
                "a guard that isn't hasAny/hasAll is not this block's shape");

        EditorFixture reference = new EditorFixture(SOURCE.replace("new ImageTemplate(\"popups/mail.png\")",
                "MAIL"));
        assertFalse(MatchesSwitchHandler.isMatchesSwitch(switchIn(reference)),
                "a template held in a constant has no path to show, so the chip row must not own it");
    }

    // ---- Writing ----

    @Test
    void growingABranchAddsASecondTemplateToItsGuard() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.setMatchesCaseTemplates(casesIn(fixture).getFirst(),
                List.of("popups/mail.png", "popups/gift.png"));

        assertNotNull(fixture.lastCode, "the edit should have produced new source");
        assertTrue(dense(fixture.lastCode).contains(
                        "caseMatchesmwhenm.hasAny(newImageTemplate(\"popups/mail.png\"),newImageTemplate(\"popups/gift.png\"))->"),
                () -> "expected both templates in the guard: " + fixture.lastCode);
    }

    @Test
    void togglingTheModeRewritesOnlyTheMethodName() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.setMatchesCaseMode(casesIn(fixture).getFirst(), true);

        assertNotNull(fixture.lastCode);
        assertAll(
                () -> assertTrue(dense(fixture.lastCode).contains(
                        "whenm.hasAll(newImageTemplate(\"popups/mail.png\"))"), fixture.lastCode),
                () -> assertFalse(fixture.lastCode.contains("hasAny"), "the old mode must be gone"),
                () -> assertTrue(fixture.lastCode.contains("ImageClicker.click(m.best())"),
                        "toggling a mode must not disturb the branch's body"));
    }

    /** Toggling to the mode it already has is a no-op — no rewrite, so no undo entry and no dirty file. */
    @Test
    void togglingToTheSameModeChangesNothing() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.setMatchesCaseMode(casesIn(fixture).getFirst(), false);

        assertNull(fixture.lastCode, "an unchanged mode should not publish an edit");
    }

    /**
     * The new branch lands <em>before</em> {@code default}. After it the case would be unreachable — a
     * compile error, and one whose message points at the wrong thing.
     */
    @Test
    void aNewBranchIsInsertedBeforeTheDefaultRule() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.addMatchesCase(switchIn(fixture), "popups/gift.png");

        assertNotNull(fixture.lastCode);
        String dense = dense(fixture.lastCode);
        int added = dense.indexOf("hasAny(newImageTemplate(\"popups/gift.png\"))");
        int defaultRule = dense.indexOf("default->");
        assertAll(
                () -> assertTrue(added > 0, () -> "the new branch is missing: " + fixture.lastCode),
                () -> assertTrue(defaultRule > added, "a branch after `default` can never run"),
                () -> assertTrue(dense.contains("hasAny(newImageTemplate(\"popups/mail.png\"))"),
                        "the existing branch must survive"));
    }

    @Test
    void addingABranchWithNoTemplateIsRefused() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.addMatchesCase(switchIn(fixture), null);

        assertNull(fixture.lastCode, "an empty guard would not compile, so there is nothing to insert");
    }

    /** Removing a branch takes its body with it; leaving the body behind would move it into the next case. */
    @Test
    void removingABranchRemovesItsBodyToo() {
        EditorFixture fixture = new EditorFixture(TWO_BRANCHES);
        assertEquals(2, casesIn(fixture).size(), "the fixture must actually have two branches");

        fixture.editor.removeMatchesCase(casesIn(fixture).getFirst());

        assertNotNull(fixture.lastCode);
        String dense = dense(fixture.lastCode);
        assertAll(
                () -> assertFalse(dense.contains("mail.png"), () -> "the branch is gone: " + fixture.lastCode),
                // One click survives, not two: leaving the body behind would silently move it into the branch
                // that follows, which is the failure mode worth naming.
                () -> assertEquals(1, dense.split("ImageClicker\\.click", -1).length - 1,
                        () -> "the removed branch's body must go with it: " + fixture.lastCode),
                () -> assertTrue(dense.contains("chest.png"), "the other branch must survive"));
    }

    /** The {@code default} rule is what makes the switch exhaustive, so it is not a removable case. */
    @Test
    void theDefaultRuleIsNotRemovable() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        SwitchCase defaultCase = MatchesSwitchHandler.defaultCaseOf(switchIn(fixture));
        assertNotNull(defaultCase, "the fixture has a default rule");

        fixture.editor.removeMatchesCase(defaultCase);

        assertNull(fixture.lastCode, "removing default would make the switch non-exhaustive");
    }

    // ---- Round trip ----

    /**
     * The property that matters across the whole feature: source the handler wrote is source it reads back
     * identically. A switch that renders correctly and re-serialises differently corrupts the file on the
     * next unrelated edit.
     */
    @Test
    void anEditedSwitchStillReadsBackAsTheSameBranches() {
        EditorFixture first = new EditorFixture(SOURCE);
        first.editor.setMatchesCaseTemplates(casesIn(first).getFirst(),
                List.of("popups/mail.png", "popups/gift.png"));

        // Each edit is driven by the fixture that owns the tree it targets — a rewrite validates its nodes
        // belong to its own AST, which is also why reopening is the honest way to chain two edits.
        EditorFixture second = new EditorFixture(first.lastCode);
        second.editor.setMatchesCaseMode(casesIn(second).getFirst(), true);

        EditorFixture reopened = new EditorFixture(second.lastCode);
        MatchesSwitchHandler.Guard guard = MatchesSwitchHandler.guardOf(casesIn(reopened).getFirst()).orElseThrow();

        assertAll(
                () -> assertTrue(MatchesSwitchHandler.isMatchesSwitch(switchIn(reopened))),
                () -> assertEquals(List.of("popups/mail.png", "popups/gift.png"), guard.paths()));
    }
}
