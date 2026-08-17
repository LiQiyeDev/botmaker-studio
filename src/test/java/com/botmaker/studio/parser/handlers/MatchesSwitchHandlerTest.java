package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.palette.MatchesJoin;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    /** The first branch's guard. */
    private static MatchesSwitchHandler.Guard guardIn(EditorFixture fixture) {
        return MatchesSwitchHandler.guardOf(casesIn(fixture).getFirst()).orElseThrow();
    }

    /** The first branch's guard as the single check it is — the fast path most branches are. */
    private static MatchesSwitchHandler.Guard.Check checkIn(EditorFixture fixture) {
        MatchesSwitchHandler.Guard guard = guardIn(fixture);
        assertInstanceOf(MatchesSwitchHandler.Guard.Check.class, guard, "expected a single-check guard");
        return (MatchesSwitchHandler.Guard.Check) guard;
    }

    /** {@code SOURCE} with the seeded guard replaced by {@code guard}. */
    private static EditorFixture withGuard(String guard) {
        return new EditorFixture(SOURCE.replace("m.hasAny(new ImageTemplate(\"popups/mail.png\"))", guard));
    }

    // ---- Reading ----

    @Test
    void aGuardIsReadAsItsModeAndItsTemplates() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        MatchesSwitchHandler.Guard.Check check = checkIn(fixture);

        assertAll(
                () -> assertEquals(MatchesCheck.ANY, check.check(), "hasAny is the any-of mode"),
                () -> assertEquals(List.of("popups/mail.png"), check.paths()));
    }

    /** The point of the tree: a branch that asks for two things at once is read as both of them. */
    @Test
    void aComposedGuardIsReadAsItsOperands() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"popups/mail.png\")) "
                + "&& !m.hasAll(new ImageTemplate(\"popups/ad.png\"))");

        MatchesSwitchHandler.Guard guard = guardIn(fixture);

        assertInstanceOf(MatchesSwitchHandler.Guard.Container.class, guard);
        MatchesSwitchHandler.Guard.Container junction = (MatchesSwitchHandler.Guard.Container) guard;
        assertAll(
                () -> assertEquals(MatchesJoin.AND, junction.join()),
                () -> assertEquals(2, junction.operands().size()),
                () -> assertInstanceOf(MatchesSwitchHandler.Guard.Check.class, junction.operands().get(0)),
                () -> assertInstanceOf(MatchesSwitchHandler.Guard.Not.class, junction.operands().get(1)));
    }

    /**
     * A chain is one container, not nested ones: JDT models {@code A && B && C} as one expression with an
     * extended operand, and the block draws it as three rows under one word rather than stepping right twice.
     */
    @Test
    void aChainOfTheSameJoinIsOneContainer() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\")) && m.hasAny(new ImageTemplate(\"c.png\"))");

        MatchesSwitchHandler.Guard guard = guardIn(fixture);

        assertInstanceOf(MatchesSwitchHandler.Guard.Container.class, guard);
        assertEquals(3, ((MatchesSwitchHandler.Guard.Container) guard).operands().size());
    }

    /** Brackets are structure, not noise: a bracket in the source is a container on screen. */
    @Test
    void aBracketedGroupIsReadAsANestedContainer() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& (m.hasAny(new ImageTemplate(\"b.png\")) || m.hasAny(new ImageTemplate(\"c.png\")))");

        MatchesSwitchHandler.Guard.Container outer =
                (MatchesSwitchHandler.Guard.Container) guardIn(fixture);

        assertAll(
                () -> assertEquals(MatchesJoin.AND, outer.join()),
                () -> assertEquals(2, outer.operands().size()),
                () -> assertEquals(MatchesJoin.OR,
                        ((MatchesSwitchHandler.Guard.Container) outer.operands().get(1)).join()));
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
     * A guard the chip row can't describe — another method, or a template held in a constant — is still this
     * block's switch: the case label is what identifies it, and the guard renders as an expression slot. The
     * alternative was falling back to the colon-form rendering, which reads an arrow label as an expression and
     * shows the branch as nonsense.
     */
    @Test
    void aGuardTheChipRowCannotDescribeIsStillClaimed() {
        EditorFixture other = withGuard("m.isEmpty()");
        assertAll(
                () -> assertTrue(MatchesSwitchHandler.isMatchesSwitch(switchIn(other))),
                () -> assertInstanceOf(MatchesSwitchHandler.Guard.Other.class, guardIn(other),
                        "a guard that isn't hasAny/hasAll is not a check"));

        EditorFixture reference = new EditorFixture(SOURCE.replace("new ImageTemplate(\"popups/mail.png\")",
                "MAIL"));
        assertInstanceOf(MatchesSwitchHandler.Guard.Other.class, guardIn(reference),
                "a template held in a constant has no path to show, so the chip row must not own it");
    }

    /** The label is what claims the switch, so a pattern over some other type is somebody else's. */
    @Test
    void aGuardedSwitchOverAnotherTypeIsNotClaimed() {
        EditorFixture strings = new EditorFixture("""
                package test;
                public class Subject {
                    void run() {
                        Object o = "a";
                        switch (o) {
                            case String s when s.isEmpty() -> {
                            }
                            default -> {
                            }
                        }
                    }
                }
                """);
        assertFalse(MatchesSwitchHandler.isMatchesSwitch(switchIn(strings)),
                "only a `case Matches m` label is this block's shape");
    }

    // ---- Writing ----

    @Test
    void growingABranchAddsASecondTemplateToItsGuard() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.setMatchesCheckTemplates(checkIn(fixture).call(),
                List.of("popups/mail.png", "popups/gift.png"));

        assertNotNull(fixture.lastCode, "the edit should have produced new source");
        assertTrue(dense(fixture.lastCode).contains(
                        "caseMatchesmwhenm.hasAny(newImageTemplate(\"popups/mail.png\"),newImageTemplate(\"popups/gift.png\"))->"),
                () -> "expected both templates in the guard: " + fixture.lastCode);
    }

    @Test
    void togglingTheModeRewritesOnlyTheMethodName() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        fixture.editor.setMatchesCheckMode(checkIn(fixture).call(), MatchesCheck.ALL);

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

        fixture.editor.setMatchesCheckMode(checkIn(fixture).call(), MatchesCheck.ANY);

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

    // ---- Composing ----
    //
    // Every gesture is now one write: GuardTree transforms the branch's tree, setMatchesGuard writes it back.
    // These assert what reaches the file — GuardTreeTest asserts the transforms themselves.

    /** The branch's guard, as the container it is. */
    private static MatchesSwitchHandler.Guard.Container containerIn(EditorFixture fixture) {
        MatchesSwitchHandler.Guard guard = guardIn(fixture);
        assertInstanceOf(MatchesSwitchHandler.Guard.Container.class, guard, "expected a container guard");
        return (MatchesSwitchHandler.Guard.Container) guard;
    }

    @Test
    void groupingACheckMakesTheBranchAContainerOfTwo() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        MatchesSwitchHandler.Guard guard = guardIn(fixture);

        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.group(guard, guard, MatchesJoin.AND, GuardTree.check("popups/gift.png")));


        assertNotNull(fixture.lastCode);
        assertTrue(dense(fixture.lastCode).contains(
                        "m.hasAny(newImageTemplate(\"popups/mail.png\"))&&m.hasAny(newImageTemplate(\"popups/gift.png\"))"),
                () -> "expected both checks in the guard: " + fixture.lastCode);
    }

    /** A container holds its conditions flat; adding a third is a third row, not a nested group. */
    @Test
    void addingToAContainerStaysFlat() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\"))");

        MatchesSwitchHandler.Guard.Container container = containerIn(fixture);
        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.add(container, container, GuardTree.check("c.png")));

        EditorFixture reopened = new EditorFixture(fixture.lastCode);
        assertAll(
                () -> assertFalse(fixture.lastCode.contains("("
                        + "m.hasAny(new ImageTemplate(\"a.png\"))"), "no bracket should have appeared"),
                () -> assertEquals(3, containerIn(reopened).operands().size()));
    }

    /**
     * A container nested in another is bracketed — always, not only when the operators differ. The bracket is
     * what the outer container's rows are drawn from, so one that isn't written is a group that vanishes on
     * the next open.
     */
    @Test
    void aNestedContainerIsBracketed() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\"))");

        MatchesSwitchHandler.Guard.Container container = containerIn(fixture);
        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.add(container, container,
                        GuardTree.container(MatchesJoin.AND,
                                List.of(GuardTree.check("c.png"), GuardTree.check("d.png")))));

        assertNotNull(fixture.lastCode);
        assertTrue(dense(fixture.lastCode).contains(
                        "&&(m.hasAny(newImageTemplate(\"c.png\"))&&m.hasAny(newImageTemplate(\"d.png\")))"),
                () -> "the nested container must keep its bracket: " + fixture.lastCode);
    }

    /** The defect the containers exist for: a container's word is its own, and flipping it reaches nothing else. */
    @Test
    void flippingOneContainerLeavesItsSiblingsAlone() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& (m.hasAny(new ImageTemplate(\"b.png\")) || m.hasAny(new ImageTemplate(\"c.png\")))");
        MatchesSwitchHandler.Guard.Container outer = containerIn(fixture);
        MatchesSwitchHandler.Guard.Container inner =
                (MatchesSwitchHandler.Guard.Container) outer.operands().get(1);

        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.setJoin(outer, inner, MatchesJoin.AND));

        EditorFixture reopened = new EditorFixture(fixture.lastCode);
        MatchesSwitchHandler.Guard.Container reread = containerIn(reopened);
        assertAll(
                () -> assertEquals(MatchesJoin.AND, reread.join(), "the outer container was not the target"),
                () -> assertEquals(MatchesJoin.AND,
                        ((MatchesSwitchHandler.Guard.Container) reread.operands().get(1)).join()),
                () -> assertFalse(fixture.lastCode.contains("||"), () -> fixture.lastCode));
    }

    /** {@code not} is a toggle in the UI, so it must be one in the source: never {@code !!}. */
    @Test
    void negatingIsAToggle() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        MatchesSwitchHandler.Guard guard = guardIn(fixture);
        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(), GuardTree.negate(guard, guard));
        assertTrue(dense(fixture.lastCode).contains("when!m.hasAny("), () -> fixture.lastCode);

        EditorFixture negated = new EditorFixture(fixture.lastCode);
        MatchesSwitchHandler.Guard reread = guardIn(negated);
        negated.editor.setMatchesGuard(casesIn(negated).getFirst(), GuardTree.negate(reread, reread));
        assertAll(
                () -> assertFalse(negated.lastCode.contains("!"), () -> "the not is gone: " + negated.lastCode),
                () -> assertTrue(negated.lastCode.contains("hasAny"), "the check itself survives"));
    }

    /** Negating a container brackets it — {@code !a && b} negates only {@code a}. */
    @Test
    void negatingAContainerBracketsIt() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\"))");

        MatchesSwitchHandler.Guard guard = guardIn(fixture);
        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(), GuardTree.negate(guard, guard));

        assertTrue(dense(fixture.lastCode).contains("when!(m.hasAny("), () -> fixture.lastCode);
    }

    @Test
    void removingAConditionCollapsesTheContainerToWhatIsLeft() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\"))");
        MatchesSwitchHandler.Guard.Container container = containerIn(fixture);

        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.remove(container, container.operands().getFirst()));

        assertNotNull(fixture.lastCode);
        assertAll(
                () -> assertFalse(fixture.lastCode.contains("a.png"), () -> fixture.lastCode),
                () -> assertFalse(fixture.lastCode.contains("&&"), "one condition left is not a container"),
                () -> assertTrue(fixture.lastCode.contains("b.png")));
    }

    /** A three-condition container loses one and stays a container. */
    @Test
    void removingFromALongerContainerKeepsTheRest() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& m.hasAny(new ImageTemplate(\"b.png\")) && m.hasAny(new ImageTemplate(\"c.png\"))");
        MatchesSwitchHandler.Guard.Container container = containerIn(fixture);

        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.remove(container, container.operands().get(1)));

        EditorFixture reopened = new EditorFixture(fixture.lastCode);
        assertAll(
                () -> assertFalse(fixture.lastCode.contains("b.png"), () -> fixture.lastCode),
                () -> assertEquals(2, containerIn(reopened).operands().size()));
    }

    /** Dragging a condition into another container: it leaves one group and joins the other, in one write. */
    @Test
    void movingAConditionBetweenContainersRewritesBoth() {
        EditorFixture fixture = withGuard("m.hasAny(new ImageTemplate(\"a.png\")) "
                + "&& (m.hasAny(new ImageTemplate(\"b.png\")) || m.hasAny(new ImageTemplate(\"c.png\")))");
        MatchesSwitchHandler.Guard.Container outer = containerIn(fixture);
        MatchesSwitchHandler.Guard.Container inner =
                (MatchesSwitchHandler.Guard.Container) outer.operands().get(1);

        fixture.editor.setMatchesGuard(casesIn(fixture).getFirst(),
                GuardTree.move(outer, outer.operands().getFirst(), inner));

        EditorFixture reopened = new EditorFixture(fixture.lastCode);
        MatchesSwitchHandler.Guard.Container reread = containerIn(reopened);
        assertAll(
                // The outer container had two conditions and gave one away, so it is the survivor now.
                () -> assertEquals(MatchesJoin.OR, reread.join(), () -> fixture.lastCode),
                () -> assertEquals(3, reread.operands().size()),
                () -> assertFalse(fixture.lastCode.contains("&&"), "nothing is left to `and`"));
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
        first.editor.setMatchesCheckTemplates(checkIn(first).call(),
                List.of("popups/mail.png", "popups/gift.png"));

        // Each edit is driven by the fixture that owns the tree it targets — a rewrite validates its nodes
        // belong to its own AST, which is also why reopening is the honest way to chain two edits.
        EditorFixture second = new EditorFixture(first.lastCode);
        second.editor.setMatchesCheckMode(checkIn(second).call(), MatchesCheck.ALL);

        EditorFixture reopened = new EditorFixture(second.lastCode);

        assertAll(
                () -> assertTrue(MatchesSwitchHandler.isMatchesSwitch(switchIn(reopened))),
                () -> assertEquals(List.of("popups/mail.png", "popups/gift.png"), checkIn(reopened).paths()));
    }

    /**
     * The same property for a composed guard, built the way the UI builds one: group, group again, negate the
     * new condition, then read the whole tree back. This is the shape the phase exists for —
     * {@code (A and B) or not C} — and the one where a lost bracket would change what the bot does rather than
     * how it looks.
     */
    @Test
    void aGuardComposedThroughTheUiReadsBackAsTheTreeItWrote() {
        EditorFixture first = new EditorFixture(SOURCE);
        MatchesSwitchHandler.Guard firstGuard = guardIn(first);
        first.editor.setMatchesGuard(casesIn(first).getFirst(), GuardTree.group(
                firstGuard, firstGuard, MatchesJoin.AND, GuardTree.check("popups/gift.png")));

        EditorFixture second = new EditorFixture(first.lastCode);
        MatchesSwitchHandler.Guard secondGuard = guardIn(second);
        second.editor.setMatchesGuard(casesIn(second).getFirst(), GuardTree.group(
                secondGuard, secondGuard, MatchesJoin.OR, GuardTree.check("popups/ad.png")));

        EditorFixture third = new EditorFixture(second.lastCode);
        MatchesSwitchHandler.Guard.Container top = containerIn(third);
        third.editor.setMatchesGuard(casesIn(third).getFirst(),
                GuardTree.negate(top, top.operands().get(1)));

        EditorFixture reopened = new EditorFixture(third.lastCode);
        MatchesSwitchHandler.Guard.Container reread = containerIn(reopened);

        assertAll(
                () -> assertTrue(MatchesSwitchHandler.isMatchesSwitch(switchIn(reopened))),
                () -> assertEquals(MatchesJoin.OR, reread.join(), "the outer container is the any-of"),
                () -> assertEquals(2, reread.operands().size()),
                () -> assertEquals(MatchesJoin.AND,
                        ((MatchesSwitchHandler.Guard.Container) reread.operands().get(0)).join(),
                        "the bracketed group kept its own word"),
                () -> assertInstanceOf(MatchesSwitchHandler.Guard.Not.class, reread.operands().get(1)));
    }
}
