package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.palette.MatchesJoin;
import com.botmaker.studio.parser.handlers.MatchesSwitchHandler.Guard;

import java.util.ArrayList;
import java.util.List;

/**
 * Every edit a {@code Matches} branch's condition can undergo, as a pure transform of the
 * {@linkplain Guard guard tree} — no JDT, no source text, so the rules that decide what a gesture <em>means</em>
 * are testable without an AST.
 *
 * <p>Each method takes the branch's whole tree and returns a whole new one (or {@code null} when the gesture is
 * refused), which {@link MatchesSwitchHandler#setGuard} then writes in one rewrite. That shape is the point:
 * the three defects this replaced were all a per-gesture rewrite deciding on its own where a bracket belonged
 * or which operator it owned — flipping one gap flipped its siblings, the {@code ＋} attached to whatever node
 * was nearest, and a hand-written grouping came back flattened. With the tree as the unit of edit, a container
 * owns exactly its own operands and its own word, and brackets are a consequence of the containers rather than
 * a guess about them.
 *
 * <p><b>Nodes are compared by identity, not by value.</b> {@link Guard} is a record hierarchy, so two branches
 * testing the same template are {@code equals} — the tree the block renders from is the tree it edits, and the
 * node the user clicked is the one object meant, not any twin of it.
 */
public final class GuardTree {

    private GuardTree() {}

    // =================================================================================
    // BUILDING NEW NODES
    // =================================================================================

    /** A fresh {@code any of <path>} leaf — what every "add a condition" is seeded with. */
    public static Guard check(String path) {
        return new Guard.Check(MatchesCheck.ANY, List.of(path), null, null);
    }

    /** A fresh container over {@code operands}. */
    public static Guard.Container container(MatchesJoin join, List<Guard> operands) {
        return new Guard.Container(join, List.copyOf(operands), null);
    }

    // =================================================================================
    // EDITS
    // =================================================================================

    /** {@code root} with {@code addition} appended to {@code container}'s operands. */
    public static Guard add(Guard root, Guard.Container container, Guard addition) {
        if (root == null || container == null || addition == null) return null;
        List<Guard> operands = new ArrayList<>(container.operands());
        operands.add(addition);
        return replace(root, container, container(container.join(), operands));
    }

    /**
     * {@code root} with {@code target} wrapped in a new container holding it and {@code addition} — how a
     * condition that is a branch's whole guard grows a second one, since there is no container to add to yet.
     */
    public static Guard group(Guard root, Guard target, MatchesJoin join, Guard addition) {
        if (root == null || target == null || addition == null) return null;
        return replace(root, target, container(join, List.of(target, addition)));
    }

    /**
     * {@code root} without {@code target}, collapsing its container to the survivor when one is left.
     *
     * <p>Refused ({@code null}) when {@code target} is the whole guard or sits directly under a {@code not}: an
     * unguarded {@code case Matches m} is unconditional and would dominate every case after it.
     */
    public static Guard remove(Guard root, Guard target) {
        Guard.Container owner = ownerOf(root, target);
        if (owner == null) return null;

        List<Guard> survivors = new ArrayList<>();
        for (Guard operand : owner.operands()) {
            if (operand != target) survivors.add(operand);
        }
        if (survivors.size() == owner.operands().size() || survivors.isEmpty()) return null;
        Guard replacement = survivors.size() == 1
                ? survivors.getFirst()
                : container(owner.join(), survivors);
        return replace(root, owner, replacement);
    }

    /** {@code root} with one container's word flipped. Null when it already reads that way — no edit, no undo. */
    public static Guard setJoin(Guard root, Guard.Container container, MatchesJoin join) {
        if (root == null || container == null || join == null || container.join() == join) return null;
        return replace(root, container, container(join, container.operands()));
    }

    /**
     * {@code root} with {@code target} negated, or its negation dropped when it already has one — the
     * {@code not} control is a toggle, so {@code !!} is never built.
     */
    public static Guard negate(Guard root, Guard target) {
        if (root == null || target == null) return null;
        Guard flipped = target instanceof Guard.Not not ? not.operand() : new Guard.Not(target, null);
        return replace(root, target, flipped);
    }

    /**
     * {@code root} with {@code moved} taken out of the container it is in and appended to {@code target}.
     *
     * <p>Done as one rebuild rather than a remove followed by an add: the removal can collapse a container that
     * {@code target} sits inside, so by the time it finished there would be no {@code target} object left to
     * add to. Refused when {@code target} is inside {@code moved} (a container cannot become its own operand)
     * or when {@code moved} is already there.
     */
    public static Guard move(Guard root, Guard moved, Guard.Container target) {
        if (root == null || moved == null || target == null) return null;
        if (moved == target || contains(moved, target)) return null;
        Guard.Container owner = ownerOf(root, moved);
        if (owner == null || owner == target) return null;
        return rebuild(root, moved, owner, target);
    }

    // =================================================================================
    // NAVIGATION
    // =================================================================================

    /** The container {@code target} is an operand of, or null when it is the whole guard or under a {@code not}. */
    public static Guard.Container ownerOf(Guard root, Guard target) {
        if (root == null || target == null) return null;
        return switch (root) {
            case Guard.Not not -> ownerOf(not.operand(), target);
            case Guard.Container container -> {
                for (Guard operand : container.operands()) {
                    if (operand == target) yield container;
                }
                for (Guard operand : container.operands()) {
                    Guard.Container found = ownerOf(operand, target);
                    if (found != null) yield found;
                }
                yield null;
            }
            case Guard.Check ignored -> null;
            case Guard.Other ignored -> null;
        };
    }

    /** Whether {@code node} is {@code root} or sits somewhere below it. */
    public static boolean contains(Guard root, Guard node) {
        if (root == node) return true;
        return switch (root) {
            case Guard.Not not -> contains(not.operand(), node);
            case Guard.Container container -> container.operands().stream().anyMatch(g -> contains(g, node));
            case Guard.Check ignored -> false;
            case Guard.Other ignored -> false;
        };
    }

    // =================================================================================
    // THE ONE RECURSION
    // =================================================================================

    /**
     * {@code root} with {@code target} swapped for {@code replacement}. Subtrees that did not change come back
     * as the same objects, so a caller can keep editing against the nodes it already holds.
     */
    private static Guard replace(Guard root, Guard target, Guard replacement) {
        if (root == target) return replacement;
        return switch (root) {
            case Guard.Not not -> {
                Guard inner = replace(not.operand(), target, replacement);
                yield inner == not.operand() ? not : new Guard.Not(inner, null);
            }
            case Guard.Container container -> {
                List<Guard> operands = new ArrayList<>();
                boolean changed = false;
                for (Guard operand : container.operands()) {
                    Guard mapped = replace(operand, target, replacement);
                    changed |= mapped != operand;
                    operands.add(mapped);
                }
                yield changed ? container(container.join(), operands) : container;
            }
            case Guard.Check ignored -> root;
            case Guard.Other ignored -> root;
        };
    }

    /** {@link #move}'s single pass: drop {@code moved} where it was, append it where it is going. */
    private static Guard rebuild(Guard node, Guard moved, Guard.Container owner, Guard.Container target) {
        return switch (node) {
            case Guard.Not not -> new Guard.Not(rebuild(not.operand(), moved, owner, target), null);
            case Guard.Container container -> {
                List<Guard> operands = new ArrayList<>();
                for (Guard operand : container.operands()) {
                    if (container == owner && operand == moved) continue;
                    operands.add(rebuild(operand, moved, owner, target));
                }
                if (container == target) operands.add(moved);
                // The container the operand left may be down to one: it is that one condition now, not a
                // container of it. `target` always grew, so it is never the one that collapses.
                yield operands.size() == 1 ? operands.getFirst() : container(container.join(), operands);
            }
            case Guard.Check ignored -> node;
            case Guard.Other ignored -> node;
        };
    }
}
