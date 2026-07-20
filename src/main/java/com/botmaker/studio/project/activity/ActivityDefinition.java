package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.BooleanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One <em>activity</em> a bot can do — a game task like "Resources" or "Alchemy". Two-tier: the activity
 * carries its own <em>enable flag</em> ({@link #enabled()} — the "whether to do it") plus its own
 * {@link #params() config params} (the {@link ActivityVariable}s that tune "how to do it"). BotMaker
 * Studio generates one {@code Activity} subclass file + one registry entry per activity.
 *
 * <p>{@link #name()} must be a valid Java identifier: it becomes the generated subclass name
 * ({@code activities/<Name>.java}) <em>and</em> the enable-flag field on the generated {@code Activities}
 * class ({@code Activities.<Name>}). Each param {@code p} becomes {@code Activities.<Name>_<p>}.
 *
 * <p>{@link #archived()} retires an activity without destroying anything: it leaves the canvas and the
 * generated registry (so it no longer runs), but its {@code activities/<Name>.java} file and its
 * {@code Activities} fields are still generated. That combination is the point — the editor never deletes a
 * file, and the hand-written stub that survives still refers to {@code Activities.<Name>}, so dropping the
 * definition outright would stop the project compiling.
 *
 * <p>{@link #outcomes()} are the activity's <em>results</em> — what it can report having happened
 * ({@code BAG_FULL}, {@code NO_ORE}) — which the flow canvas maps to a next node each. They are generated as
 * a nested {@code Outcome} enum on the activity's class, always led by the implicit
 * {@link FlowEdge#NEXT_OUTCOME}, which is not stored here: every activity has it, so storing it would only
 * create a way for it to go missing.
 *
 * <p>{@link #goHome()} asks the generated driver to call the project's {@code GoHome.run()} immediately before
 * this activity. Most activities start from the game's home screen, so it defaults to on — which is why it is
 * a boxed {@code Boolean}: a primitive would read a missing JSON property as {@code false}, silently turning
 * the default off for every project written before the field existed.
 *
 * @param name        activity name / generated class name (a valid Java identifier)
 * @param enabled     the default value of the enable flag
 * @param description optional human-readable note (may be empty)
 * @param params      the activity's config variables ("how to do it")
 * @param archived    retired: keeps its file and fields, but doesn't appear on the canvas or run
 * @param outcomes    the named results this activity can report, excluding the implicit NEXT
 * @param goHome      run {@code GoHome.run()} before this activity; null (absent) ⇒ true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                                 boolean archived, List<String> outcomes, Boolean goHome) {

    public ActivityDefinition {
        if (description == null) description = "";
        params = params == null ? List.of() : List.copyOf(params);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        if (goHome == null) goHome = Boolean.TRUE;
    }

    /** Convenience for an activity that goes home first; a pre-goHome file loads this way. */
    public ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                              boolean archived, List<String> outcomes) {
        this(name, enabled, description, params, archived, outcomes, Boolean.TRUE);
    }

    /** Convenience for an activity with only the implicit outcome; a pre-outcomes file loads this way. */
    public ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                              boolean archived) {
        this(name, enabled, description, params, archived, List.of());
    }

    /** Convenience for the common live activity; an {@code activities.json} without the field loads this way. */
    public ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params) {
        this(name, enabled, description, params, false, List.of());
    }

    /** A fresh activity with the given name/description, disabled, no params. */
    public static ActivityDefinition create(String name, String description) {
        return new ActivityDefinition(name, false, description, List.of());
    }

    /** The synthetic {@link ActivityVariable} for this activity's enable flag ({@code Activities.<Name>}). */
    public ActivityVariable enabledVariable() {
        return new ActivityVariable(name, ActivityType.BOOL, BooleanNode.valueOf(enabled), description);
    }

    /** The generated field name for one of this activity's params: {@code <Name>_<param>}. */
    public String paramFieldName(ActivityVariable param) {
        return name + "_" + param.name();
    }

    /**
     * Every constant of this activity's generated {@code Outcome} enum, in generated order: the implicit
     * {@link FlowEdge#NEXT_OUTCOME} first, then the declared {@link #outcomes()}. This is the single
     * source of both the enum body and the card's output ports, so the two can't drift.
     */
    public List<String> allOutcomes() {
        List<String> all = new ArrayList<>(outcomes.size() + 1);
        all.add(FlowEdge.NEXT_OUTCOME);
        for (String o : outcomes) {
            if (!all.contains(o)) all.add(o); // a stored NEXT would otherwise duplicate the implicit one
        }
        return all;
    }

    public ActivityDefinition withEnabled(boolean newEnabled) {
        return new ActivityDefinition(name, newEnabled, description, params, archived, outcomes, goHome);
    }

    public ActivityDefinition withDescription(String newDescription) {
        return new ActivityDefinition(name, enabled, newDescription, params, archived, outcomes, goHome);
    }

    public ActivityDefinition withParams(List<ActivityVariable> newParams) {
        return new ActivityDefinition(name, enabled, description, newParams, archived, outcomes, goHome);
    }

    public ActivityDefinition withArchived(boolean newArchived) {
        return new ActivityDefinition(name, enabled, description, params, newArchived, outcomes, goHome);
    }

    public ActivityDefinition withOutcomes(List<String> newOutcomes) {
        return new ActivityDefinition(name, enabled, description, params, archived, newOutcomes, goHome);
    }

    public ActivityDefinition withGoHome(boolean newGoHome) {
        return new ActivityDefinition(name, enabled, description, params, archived, outcomes, newGoHome);
    }
}
