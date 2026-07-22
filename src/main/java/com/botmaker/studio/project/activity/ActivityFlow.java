package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The visual activity flow: node placements plus the wires between them, as built on the free-form canvas.
 * The flow is a <em>graph</em>: an activity may have one outgoing wire per {@link ActivityDefinition#outcomes()
 * outcome}, several wires may arrive at one node, and a wire may lead back to an earlier activity so the bot
 * repeats. Execution begins at {@link #start()} and ends when it reaches an outcome with no wire leaving it —
 * that unwired port <em>is</em> the stop, which is why there is no terminal card.
 *
 * <p>This is presentational/topological state that lives alongside the activity definitions in
 * {@code activities.json}. It is optional and back-compatible: an empty flow (no wires) means "nothing wired
 * yet", and callers fall back to plain definition order (see {@link #reachable}).
 *
 * <p><b>Why {@link #start()} is stored rather than inferred.</b> It used to be derived — the placed node
 * nothing wires into. Once a cycle is legal that breaks down completely: in a loop every node has an incoming
 * wire, so there is no root to find. Deriving it was already the source of a real bug (a lone unwired card
 * outranked the actual chain and every wired activity was dropped from the generated registry); naming the
 * entry point removes the guess instead of improving it.
 *
 * @param nodes    canvas placements — one per activity dropped on the canvas
 * @param edges    the wires ("from reports this outcome → to runs next")
 * @param start    the activity the run begins at; blank ⇒ fall back to the first placed activity
 * @param maxSteps how many node transitions one run may make before the generated driver gives up (see
 *                 {@link #DEFAULT_MAX_STEPS}); {@code <= 0} ⇒ the default
 * @param stepDelayMs how long the driver pauses between two activities (see {@link #DEFAULT_STEP_DELAY_MS});
 *                    {@code < 0} ⇒ the default, {@code 0} ⇒ no pause
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityFlow(List<FlowNode> nodes, List<FlowEdge> edges, String start, int maxSteps,
                           int stepDelayMs) {

    /**
     * The default budget of node transitions per run. A branching flow may legitimately cycle forever, so the
     * generated driver counts steps and stops when this is exceeded — that is the difference between "this bot
     * farms all night" and "this bot is spinning between two activities and will never stop". The
     * {@code Watchdog} covers being stuck <em>inside</em> an activity; nothing covered spinning between them.
     */
    public static final int DEFAULT_MAX_STEPS = 1000;

    /**
     * The default pause between two activities, in milliseconds. A flow is allowed to loop, so an activity
     * that finishes in milliseconds can hand straight back to itself and leave the user with no gap in which
     * to hit Stop — the bot holds the mouse and the UI never gets a turn. A second between activities is that
     * gap. It is deliberately a floor on the <em>flow</em>, not on anything inside an activity: {@code Wait}
     * calls a user writes are their own business, and {@link #DEFAULT_MAX_STEPS} still bounds the run.
     */
    public static final int DEFAULT_STEP_DELAY_MS = 1000;

    public ActivityFlow {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        if (start == null) start = "";
        if (maxSteps <= 0) maxSteps = DEFAULT_MAX_STEPS;
        // Only a negative value means "unset". 0 is a legitimate "no pause" the user can ask for, so unlike
        // maxSteps it must survive the constructor — see the JSON creator below for how absent stays distinct.
        if (stepDelayMs < 0) stepDelayMs = DEFAULT_STEP_DELAY_MS;
    }

    /**
     * Deserialises {@code activities.json}. {@code stepDelayMs} is boxed for one reason: a file written before
     * the field existed has no such key, and Jackson would bind a missing {@code int} to 0 — silently turning
     * every pre-existing flow into a zero-delay one, which is exactly the runaway this field exists to
     * prevent. Absent ({@code null}) means "take the default"; an explicit {@code 0} means the user asked for
     * no pause, and the two must not be conflated.
     */
    @JsonCreator
    static ActivityFlow fromJson(@JsonProperty("nodes") List<FlowNode> nodes,
                                 @JsonProperty("edges") List<FlowEdge> edges,
                                 @JsonProperty("start") String start,
                                 @JsonProperty("maxSteps") int maxSteps,
                                 @JsonProperty("stepDelayMs") Integer stepDelayMs) {
        return new ActivityFlow(nodes, edges, start, maxSteps,
                stepDelayMs == null ? DEFAULT_STEP_DELAY_MS : stepDelayMs);
    }

    /** A flow with no explicit start or budget — how every pre-branching {@code activities.json} loads. */
    public ActivityFlow(List<FlowNode> nodes, List<FlowEdge> edges) {
        this(nodes, edges, "", DEFAULT_MAX_STEPS, DEFAULT_STEP_DELAY_MS);
    }

    /** Pre-{@code stepDelayMs} arity, kept so existing four-arg construction still reads naturally. */
    public ActivityFlow(List<FlowNode> nodes, List<FlowEdge> edges, String start, int maxSteps) {
        this(nodes, edges, start, maxSteps, DEFAULT_STEP_DELAY_MS);
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

    public ActivityFlow withStart(String newStart) {
        return new ActivityFlow(nodes, edges, newStart, maxSteps, stepDelayMs);
    }

    public ActivityFlow withMaxSteps(int newMaxSteps) {
        return new ActivityFlow(nodes, edges, start, newMaxSteps, stepDelayMs);
    }

    public ActivityFlow withStepDelayMs(int newStepDelayMs) {
        return new ActivityFlow(nodes, edges, start, maxSteps, newStepDelayMs);
    }

    /**
     * The entry point resolved against what is actually placed: {@link #start()} when it names a placed
     * activity, else the first placed one. The fallback is what lets a flow drawn before start nodes existed
     * — and a flow whose start activity was archived or renamed — still generate something that runs.
     */
    public String resolvedStart(List<String> allActivityNames) {
        List<String> placed = placedActivities(allActivityNames);
        if (placed.contains(start)) return start;
        return placed.isEmpty() ? "" : placed.getFirst();
    }

    /**
     * The activities the run can actually reach, breadth-first from {@link #resolvedStart}. Anything left out
     * is an <em>orphan</em>: placed but unreachable, so it never runs (the editor flags it). Wires naming
     * something that no longer exists are ignored.
     *
     * <p>Breadth-first order is <em>presentational only</em> — with branches there is no single run order any
     * more, and the generated driver decides what runs next from the outcome an activity reports. What this
     * list still decides is which activities are instantiated and which are flagged as orphans.
     *
     * <p>When the flow {@link #isEmpty() has no wires} nothing is wired yet, so this returns
     * {@code allActivityNames} unchanged — preserving the pre-flow behaviour (definition order, all run).
     */
    public List<String> reachable(List<String> allActivityNames) {
        if (isEmpty()) return List.copyOf(allActivityNames);
        return reachableFrom(placedActivities(allActivityNames), edges, resolvedStart(allActivityNames));
    }

    /**
     * Breadth-first walk of {@code edges} from {@code start} over {@code placed}. Wires naming anything outside
     * {@code placed} are ignored as stale — which is also how an edge into a pre-2026 {@code @stop} card
     * disappears — and revisiting is skipped, which is what makes a cyclic flow terminate here.
     *
     * <p>Static and shared by the model and the flow editor, so the set of activities the canvas marks as
     * reachable is by construction the set the generator emits. Its predecessor {@code linearize} was shared
     * for the same reason; what changed is that a branching flow yields a reachable <em>set</em> rather than a
     * single ordered chain.
     */
    public static List<String> reachableFrom(List<String> placed, List<FlowEdge> edges, String start) {
        Set<String> known = new HashSet<>(placed);
        if (!known.contains(start)) return List.of();

        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (FlowEdge e : edges) {
            if (!known.contains(e.from()) || !known.contains(e.to())) continue; // stale wire
            successors.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }

        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (!visited.add(node)) continue; // already reached; this is also the cycle guard
            queue.addAll(successors.getOrDefault(node, List.of()));
        }
        return List.copyOf(visited);
    }

    /**
     * The placed activity names, in canvas order, restricted to ones that still exist. Filtering on "still
     * exists" is also what quietly drops a legacy {@code @stop} node: it names no activity.
     */
    private List<String> placedActivities(List<String> allActivityNames) {
        Set<String> known = new HashSet<>(allActivityNames);
        List<String> placed = new ArrayList<>();
        for (FlowNode n : nodes) {
            if (known.contains(n.activity()) && !placed.contains(n.activity())) placed.add(n.activity());
        }
        return placed;
    }
}
