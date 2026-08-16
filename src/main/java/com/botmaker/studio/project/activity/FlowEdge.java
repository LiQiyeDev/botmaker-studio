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

    /**
     * How an outcome is written for the user where space is tight — a port chip, a tooltip. The implicit
     * outcome reads "then" (what the wire means), a declared one is its own name.
     *
     * <p>Here rather than in each view for the usual reason: three places had hand-spelled it and they had
     * drifted into three different forms of the same outcome — {@code "then"} on a flow port,
     * {@code "then  (NEXT)"} in the new-activity dialog, and the bare constant {@code NEXT} in the return
     * block's picker, so the same thing read differently depending on which editor you were in.
     */
    public static String outcomeLabel(String outcome) {
        return outcome == null || outcome.isBlank() || NEXT_OUTCOME.equals(outcome) ? "then" : outcome;
    }

    /**
     * {@link #outcomeLabel} plus the generated constant, for a menu entry or a form row — anywhere the user is
     * choosing an outcome and the name that will appear in their Java is worth showing. A declared outcome is
     * already its own constant, so only the implicit one gains the suffix.
     */
    public static String outcomeLabelWithConstant(String outcome) {
        String label = outcomeLabel(outcome);
        return "then".equals(label) ? label + " (" + NEXT_OUTCOME + ")" : label;
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
