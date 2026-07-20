package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules the Activity Flow canvas enforces while wiring. Kept pure and free of JavaFX so the canvas can ask
 * "may I connect these?" and so the rules are unit-testable.
 *
 * <p><b>Almost everything this class used to forbid is now the feature.</b> It was written to keep the flow a
 * single linear chain, so it rejected a fork, a join, a self-wire and anything that would loop. With
 * outcome-routed edges: a fork <em>is</em> branching (one wire per outcome), a join is two branches meeting
 * again, a self-wire is a retry, and a cycle is how a bot repeats — the generated driver's step budget is what
 * bounds it now, not the editor. What is left is the one thing that genuinely cannot be drawn: <b>a second wire
 * on the same {@code (from, outcome)} pair</b>, because one result can't lead to two places.
 *
 * <p>Note there is nothing here about ending the run. An outcome with no wire ends it, so "stop" is the absence
 * of a rule rather than a node with rules of its own.
 *
 * <p>Nodes the run can't reach are reported by {@link #orphans} instead, which is a warning ("won't run"), not
 * a blocked action: while you are still wiring, most cards are legitimately unconnected.
 */
public final class FlowRules {

    private FlowRules() {}

    /**
     * Why {@code from —outcome→ to} may not be wired, or {@code null} when it is allowed. The message is
     * written for the user and shown inline on the canvas.
     */
    public static String rejectionFor(List<FlowEdge> edges, String from, String outcome, String to) {
        String label = outcome == null || outcome.isBlank() ? FlowEdge.NEXT_OUTCOME : outcome;
        for (FlowEdge e : edges) {
            if (e.from().equals(from) && e.outcomeOrNext().equals(label)) {
                return from + " already goes somewhere when it reports " + label
                        + " — remove that wire first, or use a different outcome.";
            }
        }
        return null;
    }

    /**
     * The activities that are placed but unreachable from {@code start} — they won't run. With no wires at all
     * nothing is wired yet, so nothing is an orphan (the generator falls back to plain list order; see
     * {@link ActivityFlow#reachable}).
     */
    public static List<String> orphans(List<String> placed, List<FlowEdge> edges, String start) {
        if (edges.isEmpty()) return List.of();
        Set<String> live = new HashSet<>(reachable(placed, edges, start));
        List<String> out = new ArrayList<>();
        for (String a : placed) {
            if (!live.contains(a)) out.add(a);
        }
        return out;
    }

    /**
     * The activities a run can reach, breadth-first from {@code start} (falling back to the first placed card
     * when {@code start} names nothing placed, exactly as {@link ActivityFlow#resolvedStart} does). Delegates
     * to {@link ActivityFlow#reachableFrom} — the same walk the code generator uses — so what the canvas marks
     * as reachable is by construction what gets generated.
     */
    public static List<String> reachable(List<String> placed, List<FlowEdge> edges, String start) {
        String from = placed.contains(start) ? start : (placed.isEmpty() ? "" : placed.getFirst());
        return ActivityFlow.reachableFrom(placed, edges, from);
    }
}
