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

    /**
     * The second outcome every activity has without declaring it — "this activity is switched off, go here
     * instead". A disabled activity is not skipped <em>out of</em> the flow: the flow still passes through it
     * and takes this wire, and an unwired {@code DISABLED} ends the run.
     *
     * <p>It is <b>not</b> a constant of the generated {@code Outcome} enum, which is why
     * {@link ActivityDefinition#allOutcomes()} does not list it and
     * {@link ActivityDefinition#flowPorts()} does. An activity can never <em>report</em> being disabled — it
     * did not run. It reaches the generated code as {@code FlowGraph.node}'s {@code whenDisabled} argument.
     *
     * <p>Stored under its own name and not blank: blank already means {@link #NEXT_OUTCOME}, and a project
     * drawn before this outcome existed has no {@code DISABLED} wire at all — which is the whole of the
     * behaviour change, and is deliberate.
     */
    public static final String DISABLED_OUTCOME = "DISABLED";

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

    /** True when this is the "switched off, go here instead" wire. */
    public boolean isDisabled() {
        return DISABLED_OUTCOME.equals(outcome);
    }

    /**
     * How an outcome is written for the user — a port chip, a tooltip, a picker entry. It is the constant
     * itself, always: {@link #NEXT_OUTCOME} for the implicit one, its own name for a declared one.
     *
     * <p>The implicit outcome used to read {@code "then"} here and {@code "then (NEXT)"} in the forms, while
     * the return block's picker showed the bare constant — one outcome with three spellings, so the user had
     * to work out that the word on the wire and the constant in their Java were the same thing. There is one
     * name now, and it is the one that appears in the generated source.
     */
    public static String outcomeLabel(String outcome) {
        return outcome == null || outcome.isBlank() ? NEXT_OUTCOME : outcome;
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
