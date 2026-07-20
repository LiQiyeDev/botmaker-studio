package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One wire in the {@link ActivityFlow}: "when {@code from} finishes reporting {@code outcome}, {@code to} runs
 * next". An activity may have one wire per outcome, so the flow branches; several wires may arrive at the same
 * node, and a wire may lead back to an earlier activity to loop.
 *
 * <p>{@link #outcome()} names one of the source activity's {@link ActivityDefinition#outcomes()} constants, or
 * is blank for the implicit {@link #NEXT_OUTCOME} — the wire an activity follows when it has nothing
 * special to report. Blank-means-next is what lets a pre-outcome {@code activities.json} (a bare
 * {@code from}/{@code to} pair) load as exactly the flow it used to be.
 *
 * <p>The pair that must be unique is {@code (from, outcome)}: one outcome cannot lead to two places. Enforced
 * by {@code ui.app.flow.FlowRules}.
 *
 * @param from    source activity name (the {@link FlowNode#activity()} the wire leaves)
 * @param to      target activity name — the one that runs next
 * @param outcome the source outcome this wire is for; blank ⇒ {@link #NEXT_OUTCOME}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowEdge(String from, String to, String outcome) {

    /**
     * The outcome every activity has whether it declares any or not — "nothing special to report, carry on".
     * It is generated as the first constant of each activity's {@code Outcome} enum and is what a generated
     * stub returns, so a flow drawn without ever thinking about outcomes behaves exactly like the old linear
     * one.
     *
     * <p>Stored as a blank string on the edge, never as the literal name: the constant was called
     * {@code DEFAULT} before it was called {@code NEXT}, and blank-means-implicit meant that rename cost no
     * migration at all. Keep it that way if it is ever renamed again.
     */
    public static final String NEXT_OUTCOME = "NEXT";

    public FlowEdge {
        if (from == null) from = "";
        if (to == null) to = "";
        if (outcome == null) outcome = "";
    }

    /** The implicit-outcome wire — how every edge behaved before outcomes existed. */
    public FlowEdge(String from, String to) {
        this(from, to, "");
    }

    /** The outcome constant this wire routes, resolving blank to {@link #NEXT_OUTCOME}. */
    public String outcomeOrNext() {
        return outcome.isBlank() ? NEXT_OUTCOME : outcome;
    }

    /** True when this is the plain "finished, carry on" wire rather than one for a named outcome. */
    public boolean isNext() {
        return outcome.isBlank() || NEXT_OUTCOME.equals(outcome);
    }

    /** The same wire re-pointed at {@code newFrom}/{@code newTo} — used when a node is renamed. */
    public FlowEdge rewired(String newFrom, String newTo) {
        return new FlowEdge(newFrom, newTo, outcome);
    }

    /** The same wire carrying a different outcome — used when an outcome constant is renamed. */
    public FlowEdge withOutcome(String newOutcome) {
        return new FlowEdge(from, to, newOutcome);
    }
}
