package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.palette.MatchesJoin;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler.Guard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each gesture on a branch's condition <em>means</em>, with no AST in sight — {@link GuardTree} is pure,
 * which is the reason these rules are testable at all.
 *
 * <p>The three assertions worth naming here are the three defects that prompted the containers: a container's
 * word is its own (flipping one leaves its siblings alone), a container cannot be emptied (an unguarded
 * {@code case Matches m} dominates every case after it), and a condition dragged out of a two-condition
 * container leaves that container as the single condition it now is, rather than as a group of one.
 */
class GuardTreeTest {

    private static Guard check(String path) {
        return GuardTree.check(path);
    }

    /** {@code all of [a, any of [b, c]]} — the smallest tree with something to confuse. */
    private static Guard.Container nested(Guard a, Guard b, Guard c) {
        return GuardTree.container(MatchesJoin.AND, List.of(a, GuardTree.container(MatchesJoin.OR, List.of(b, c))));
    }

    @Test
    void flippingOneContainersWordLeavesItsSiblingsAlone() {
        Guard a = check("a.png");
        Guard.Container root = nested(a, check("b.png"), check("c.png"));
        Guard.Container inner = (Guard.Container) root.operands().get(1);

        Guard.Container flipped = (Guard.Container) GuardTree.setJoin(root, inner, MatchesJoin.AND);

        assertAll(
                () -> assertEquals(MatchesJoin.AND, flipped.join(), "the outer word was not the target"),
                () -> assertEquals(MatchesJoin.AND, ((Guard.Container) flipped.operands().get(1)).join()),
                // The untouched sibling comes back as the same object, so a caller can keep editing against it.
                () -> assertSame(a, flipped.operands().get(0)));
    }

    /** Flipping to the word it already has is not an edit — null, so no rewrite and no undo entry. */
    @Test
    void flippingToTheWordItAlreadyHasIsRefused() {
        Guard.Container root = GuardTree.container(MatchesJoin.AND, List.of(check("a.png"), check("b.png")));

        assertNull(GuardTree.setJoin(root, root, MatchesJoin.AND));
    }

    @Test
    void addingAppendsToThatContainerOnly() {
        Guard.Container root = nested(check("a.png"), check("b.png"), check("c.png"));
        Guard.Container inner = (Guard.Container) root.operands().get(1);

        Guard.Container grown = (Guard.Container) GuardTree.add(root, inner, check("d.png"));

        assertAll(
                () -> assertEquals(2, grown.operands().size(), "the outer container did not grow"),
                () -> assertEquals(3, ((Guard.Container) grown.operands().get(1)).operands().size()));
    }

    /** A condition that is the whole guard has no container to join, so growing it makes one. */
    @Test
    void groupingWrapsAConditionInANewContainer() {
        Guard only = check("a.png");

        Guard grouped = GuardTree.group(only, only, MatchesJoin.OR, check("b.png"));

        assertAll(
                () -> assertEquals(MatchesJoin.OR, ((Guard.Container) grouped).join()),
                () -> assertSame(only, ((Guard.Container) grouped).operands().get(0)));
    }

    /** Down to one, a container is not a container — it is the condition it holds. */
    @Test
    void removingTheSecondToLastConditionCollapsesTheContainer() {
        Guard a = check("a.png");
        Guard b = check("b.png");
        Guard.Container root = GuardTree.container(MatchesJoin.AND, List.of(a, b));

        assertSame(b, GuardTree.remove(root, a));
    }

    /** The invariant the whole feature is built around: a branch can never end up with no condition. */
    @Test
    void removingTheWholeGuardIsRefused() {
        Guard only = check("a.png");
        Guard.Container root = GuardTree.container(MatchesJoin.AND, List.of(only, check("b.png")));

        assertAll(
                () -> assertNull(GuardTree.remove(only, only), "there is no container to remove it from"),
                () -> assertNull(GuardTree.remove(root, check("elsewhere.png")),
                        "a condition from another tree is not in this one"));
    }

    @Test
    void negatingIsAToggleRatherThanAWrap() {
        Guard a = check("a.png");
        Guard.Container root = GuardTree.container(MatchesJoin.AND, List.of(a, check("b.png")));

        Guard.Container negated = (Guard.Container) GuardTree.negate(root, a);
        Guard.Not not = (Guard.Not) negated.operands().get(0);
        Guard.Container back = (Guard.Container) GuardTree.negate(negated, not);

        assertAll(
                () -> assertSame(a, not.operand()),
                () -> assertSame(a, back.operands().get(0), "the second click drops the not, it doesn't add one"));
    }

    /**
     * A drag between containers. The container the condition left is down to one and collapses — which is why
     * this is one rebuild and not a remove followed by an add: after the collapse there would be no target
     * object left to add to.
     */
    @Test
    void movingBetweenContainersEmptiesOneAndFillsTheOther() {
        Guard a = check("a.png");
        Guard.Container root = nested(a, check("b.png"), check("c.png"));
        Guard.Container inner = (Guard.Container) root.operands().get(1);

        Guard moved = GuardTree.move(root, a, inner);

        Guard.Container survivor = (Guard.Container) moved;
        assertAll(
                () -> assertEquals(MatchesJoin.OR, survivor.join(), "the inner container is what is left"),
                () -> assertEquals(3, survivor.operands().size()),
                () -> assertSame(a, survivor.operands().get(2), "the moved condition lands last"));
    }

    @Test
    void aContainerCannotBeMovedIntoItself() {
        Guard.Container root = nested(check("a.png"), check("b.png"), check("c.png"));
        Guard.Container inner = (Guard.Container) root.operands().get(1);

        assertAll(
                () -> assertNull(GuardTree.move(root, inner, inner)),
                () -> assertNull(GuardTree.move(root, root, inner), "the root contains its own target"),
                () -> assertNull(GuardTree.move(root, root.operands().get(0), root),
                        "it is already there"));
    }

    @Test
    void ownershipAndContainmentAnswerWhereANodeSits() {
        Guard a = check("a.png");
        Guard b = check("b.png");
        Guard.Container root = nested(a, b, check("c.png"));
        Guard.Container inner = (Guard.Container) root.operands().get(1);

        assertAll(
                () -> assertSame(root, GuardTree.ownerOf(root, a)),
                () -> assertSame(inner, GuardTree.ownerOf(root, b)),
                () -> assertNull(GuardTree.ownerOf(root, root), "the root is nobody's operand"),
                () -> assertTrue(GuardTree.contains(root, b)),
                () -> assertNotSame(root, inner));
    }
}
