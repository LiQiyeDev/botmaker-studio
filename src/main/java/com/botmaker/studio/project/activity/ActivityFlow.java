package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The visual activity flow: node placements plus the wires between them, as built on the free-form canvas.
 * In v1 the flow is a single <em>linear chain</em> (each activity has at most one incoming and one outgoing
 * {@link FlowEdge}; no branches, cycles, or fan-out — enforced by the editor). The chain's <em>root</em>
 * (the placed node with no incoming wire) is where the bot starts; following the wires gives the run order.
 *
 * <p>This is presentational/topological state that lives alongside the activity definitions in
 * {@code activities.json}. It is optional and back-compatible: an empty flow (no wires) means "no chosen
 * chain yet", and callers fall back to the plain definition order (see {@link #order}).
 *
 * @param nodes canvas placements, one per activity that has been dropped on the canvas
 * @param edges the wires ("from finishes → to runs next")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityFlow(List<FlowNode> nodes, List<FlowEdge> edges) {

    public ActivityFlow {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static ActivityFlow empty() {
        return new ActivityFlow(List.of(), List.of());
    }

    /** True when nothing has been wired yet — callers should fall back to plain definition order. */
    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** The saved canvas placement for {@code activity}, if it has been dropped on the canvas. */
    public Optional<FlowNode> node(String activity) {
        return nodes.stream().filter(n -> n.activity().equals(activity)).findFirst();
    }

    /**
     * The run order for {@code allActivityNames}, linearized from the wiring: start at the chain's root (a
     * placed node with no incoming wire) and follow each single outgoing wire. Activities not reachable from
     * the root — <em>orphans</em> — are excluded (they won't run; the editor flags them). Stale wires
     * pointing at names no longer in {@code allActivityNames} are ignored.
     *
     * <p>When the flow {@link #isEmpty() has no wires} the chain is undefined, so this returns
     * {@code allActivityNames} unchanged — preserving the pre-flow behaviour (definition order, all run).
     */
    public List<String> order(List<String> allActivityNames) {
        if (isEmpty()) return List.copyOf(allActivityNames);

        Set<String> known = new HashSet<>(allActivityNames);
        Map<String, String> next = new HashMap<>();
        Set<String> hasIncoming = new HashSet<>();
        for (FlowEdge e : edges) {
            if (!known.contains(e.from()) || !known.contains(e.to())) continue; // ignore stale wires
            next.put(e.from(), e.to());
            hasIncoming.add(e.to());
        }

        // Placed activities, in insertion order, restricted to ones that still exist.
        Set<String> placed = new LinkedHashSet<>();
        for (FlowNode n : nodes) {
            if (known.contains(n.activity())) placed.add(n.activity());
        }
        // Root of the chain: the first placed node nothing wires into.
        String root = placed.stream().filter(a -> !hasIncoming.contains(a)).findFirst().orElse(null);

        List<String> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cur = root; cur != null && known.contains(cur) && seen.add(cur); cur = next.get(cur)) {
            ordered.add(cur);
        }
        return ordered;
    }
}
