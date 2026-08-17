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
 * <p><b>{@link #params()} is the legacy half.</b> A project made from 2026-08 on
 * ({@link com.botmaker.studio.project.settings.SettingsModel#JAVA}) keeps every value in one project-wide
 * list instead — a knob two activities both need is one setting they both read, rather than a copy each —
 * and files it under a tag named after this activity, so it still reads as belonging here. Such a project
 * leaves this list empty and its fields live on {@code Settings} ({@code Settings.<Name>} for the enable
 * flag). The rest of this record — the flag, the outcomes, go-home, the popup check — is per-activity in
 * both models, because it genuinely is about this activity rather than about a value it reads.
 *
 * <p>{@link #archived()} retires an activity without destroying anything: it leaves the canvas, the generated
 * registry <em>and</em> the generated {@code Activities} fields, so nothing about it is compiled or run any
 * more. The definition survives here — that is what makes it restorable — and the hand-written
 * {@code activities/<Name>.java} survives too, moved to {@code ProjectConfig.archivedActivitiesDir()} where it
 * is out of the compiler's way. The two move together on purpose: the stub refers to
 * {@code Activities.<Name>}, so a stub left in the source tree without its field is exactly the broken build
 * that once made removal impossible.
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
 * <p>{@link #popupCheck()} is the same shape for the SDK's {@code PopupGuard}: on by default (popups are the
 * reason it exists), and turned off for an activity that works through a popup-shaped screen <em>itself</em> —
 * where a guard dismissing it mid-activity is the bug, not the fix. The driver emits one
 * {@code PopupGuard.enabled(…)} per activity rather than only for the off ones, because the flag is
 * process-global: an activity that didn't set it would inherit whatever the previous one left behind.
 *
 * @param name        activity name / generated class name (a valid Java identifier)
 * @param enabled     the default value of the enable flag
 * @param description optional human-readable note (may be empty)
 * @param params      the activity's config variables ("how to do it")
 * @param archived    retired: its file is kept aside and restorable, but nothing is generated for it and it
 *                    neither appears on the canvas nor runs
 * @param outcomes    the named results this activity can report, excluding the implicit NEXT
 * @param goHome      run {@code GoHome.run()} before this activity; null (absent) ⇒ true
 * @param popupCheck  let the popup guard dismiss popups during this activity; null (absent) ⇒ true
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                                 boolean archived, List<String> outcomes, Boolean goHome, Boolean popupCheck) {

    public ActivityDefinition {
        if (description == null) description = "";
        params = params == null ? List.of() : List.copyOf(params);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        if (goHome == null) goHome = Boolean.TRUE;
        if (popupCheck == null) popupCheck = Boolean.TRUE;
    }

    /** Convenience for an activity the popup guard runs during; a pre-popupCheck file loads this way. */
    public ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                              boolean archived, List<String> outcomes, Boolean goHome) {
        this(name, enabled, description, params, archived, outcomes, goHome, Boolean.TRUE);
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
        return new ActivityDefinition(name, newEnabled, description, params, archived, outcomes, goHome, popupCheck);
    }

    public ActivityDefinition withDescription(String newDescription) {
        return new ActivityDefinition(name, enabled, newDescription, params, archived, outcomes, goHome, popupCheck);
    }

    public ActivityDefinition withParams(List<ActivityVariable> newParams) {
        return new ActivityDefinition(name, enabled, description, newParams, archived, outcomes, goHome, popupCheck);
    }

    public ActivityDefinition withArchived(boolean newArchived) {
        return new ActivityDefinition(name, enabled, description, params, newArchived, outcomes, goHome, popupCheck);
    }

    public ActivityDefinition withOutcomes(List<String> newOutcomes) {
        return new ActivityDefinition(name, enabled, description, params, archived, newOutcomes, goHome, popupCheck);
    }

    public ActivityDefinition withGoHome(boolean newGoHome) {
        return new ActivityDefinition(name, enabled, description, params, archived, outcomes, newGoHome, popupCheck);
    }

    public ActivityDefinition withPopupCheck(boolean newPopupCheck) {
        return new ActivityDefinition(name, enabled, description, params, archived, outcomes, goHome, newPopupCheck);
    }
}
