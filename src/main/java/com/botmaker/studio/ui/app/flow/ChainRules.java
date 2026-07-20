package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules that keep an {@link com.botmaker.studio.project.activity.ActivityFlow} a single linear chain —
 * every activity has at most one incoming and one outgoing wire, and the wiring never loops. Kept pure and
 * free of JavaFX so the canvas can ask "may I connect these?" and so the rules are unit-testable.
 *
 * <p>Note this deliberately does <em>not</em> reject a second root: while wiring, every not-yet-connected
 * node is a root. Nodes left outside the chain are reported by {@link #orphans} instead, which is a warning
 * ("won't run"), not a blocked action.
 */
public final class ChainRules {

    private ChainRules() {}

    /**
     * Why {@code from → to} may not be wired, or {@code null} when it is allowed. The message is written for
     * the user and shown inline on the canvas.
     */
    public static String rejectionFor(List<FlowEdge> edges, String from, String to) {
        if (from.equals(to)) return "An activity can't run after itself.";
        for (FlowEdge e : edges) {
            if (e.from().equals(from)) return from + " already has a next activity — remove that wire first.";
            if (e.to().equals(to)) return to + " already runs after something else — remove that wire first.";
        }
        if (reaches(edges, to, from)) return "That would make a loop, and the flow runs top to bottom once.";
        return null;
    }

    /** True when following the wires forward from {@code start} eventually arrives at {@code target}. */
    private static boolean reaches(List<FlowEdge> edges, String start, String target) {
        Set<String> seen = new HashSet<>();
        String cur = start;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(target)) return true;
            cur = nextOf(edges, cur);
        }
        return false;
    }

    private static String nextOf(List<FlowEdge> edges, String from) {
        for (FlowEdge e : edges) {
            if (e.from().equals(from)) return e.to();
        }
        return null;
    }

    /**
     * The activities that are placed but not reachable from the chain root, in the given order — they won't
     * run. With no wires at all there is no chain yet, so nothing is an orphan (the generator falls back to
     * plain list order; see {@link com.botmaker.studio.project.activity.ActivityFlow#order}).
     */
    public static List<String> orphans(List<String> placed, List<FlowEdge> edges) {
        if (edges.isEmpty()) return List.of();
        Set<String> inChain = new HashSet<>(chain(placed, edges));
        List<String> out = new ArrayList<>();
        for (String a : placed) {
            if (!inChain.contains(a)) out.add(a);
        }
        return out;
    }

    /**
     * The chain in run order. Delegates to {@link ActivityFlow#linearize} — the same walk the generator uses
     * — so the order previewed on the canvas is exactly the order emitted into {@code ActivityRegistry.ALL}.
     */
    public static List<String> chain(List<String> placed, List<FlowEdge> edges) {
        return ActivityFlow.linearize(placed, edges);
    }
}
