package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One <em>activity</em> a bot can do — a game task like "Resources" or "Alchemy". It carries its own
 * <em>enable flag</em> ({@link #enabled()} — the "whether to do it"), its outcomes, and the two guards that
 * frame a run. BotMaker Studio generates one {@code Activity} subclass file + one registry entry per activity.
 *
 * <p>{@link #name()} must be a valid Java identifier: it becomes the generated subclass name
 * ({@code activities/<Name>.java}) <em>and</em> the enable-flag field on the generated {@code Activities}
 * class ({@code Activities.<Name>}).
 *
 * <p><b>An activity owns no values.</b> The knobs that tune "how to do it" are
 * {@link ActivitiesConfig#variables() project variables}, filed under a tag named after this activity so
 * they still read as belonging here — but readable from anywhere, so a delay two activities both wait for is
 * one variable rather than a copy each. What is left on this record is what genuinely <em>is</em> about this
 * activity rather than about a value it reads.
 *
 * <p>There is no <em>archived</em> state. It existed to retire an activity without destroying anything — the
 * definition stayed here while {@code activities/<Name>.java} was moved out of the source tree — and it never
 * held together: the stub, the enable-flag field, the registry entry and the flow edges had to leave and come
 * back as one, and any one of them out of step is a project that does not compile. Retiring an activity is
 * {@link #enabled() disabling} it (it stays on the canvas, generates as before, and does not run) or deleting
 * it outright.
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
 * <p><b>{@link #id()} is what a rename is a rename of</b> (2026-08-29). The name is what the user types and
 * what the class is called, so it is exactly the thing a rename changes — which means nothing tracking
 * "which file is this activity's" across a rename may key on it. A host reconciling seed files that matched
 * on the name would see one activity vanish and another appear, orphan the stub the user wrote their
 * {@code run()} body into, and hand them an empty one. The id never changes and is never shown.
 *
 * <p>It is carried here, on Studio's own record, for a blunt reason: <b>Studio writes this file</b>. A field
 * this record did not know about would be dropped on the first save, so the SDK's
 * {@code ActivityModel.id} would exist exactly until the user touched anything. That is the standing hazard
 * of two record sets over one file, and it is why the two are being collapsed.
 *
 * <p><b>Absent means the name, and there is no migration.</b> That default is what every project written
 * before the field behaved as: stable, so a rename in an old project does exactly what it always did rather
 * than anything worse, and never churning the way a fresh random default would — which would make every open
 * look like a rename. An activity created from 2026-08-29 on gets a real id, so the projects that gain the
 * better behaviour are the ones being worked on, and nobody's stored file is rewritten to add a field they
 * cannot see.
 *
 * @param name        activity name / generated class name (a valid Java identifier)
 * @param enabled     the default value of the enable flag
 * @param description optional human-readable note (may be empty)
 * @param outcomes    the named results this activity can report, excluding the implicit NEXT
 * @param goHome      run {@code GoHome.run()} before this activity; null (absent) ⇒ true
 * @param popupCheck  let the popup guard dismiss popups during this activity; null (absent) ⇒ true
 * @param id          the stable identity a rename does not change; blank (absent) ⇒ {@code name}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDefinition(String name, boolean enabled, String description,
                                 List<String> outcomes, Boolean goHome, Boolean popupCheck, String id) {

    public ActivityDefinition {
        if (description == null) description = "";
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        if (goHome == null) goHome = Boolean.TRUE;
        if (popupCheck == null) popupCheck = Boolean.TRUE;
        if (id == null || id.isBlank()) id = name == null ? "" : name;
    }

    /** The pre-id shape: the name stands in as the id, exactly as it does for a file that has none. */
    public ActivityDefinition(String name, boolean enabled, String description,
                              List<String> outcomes, Boolean goHome, Boolean popupCheck) {
        this(name, enabled, description, outcomes, goHome, popupCheck, null);
    }

    /** A fresh activity with the given name/description, disabled, and an id of its own from the start. */
    public static ActivityDefinition create(String name, String description) {
        return new ActivityDefinition(name, false, description, List.of(), Boolean.TRUE, Boolean.TRUE,
                com.botmaker.plugin.api.authoring.ActivityModel.newId());
    }

    /**
     * The synthetic {@link ActivityVariable} for this activity's enable flag ({@code Activities.<Name>}).
     *
     * <p>Tagged with the activity's own name, so it is listed with that activity's variables, and
     * {@link ParamVisibility#EDITOR_ONLY} because the Runner already offers every activity its own switch —
     * a second one under a tag heading would be the same flag twice.
     */
    public ActivityVariable enabledVariable() {
        return new ActivityVariable(name, ValueWire.one("YES_NO"),
                List.of(Boolean.toString(enabled)), description, name, ParamVisibility.EDITOR_ONLY,
                List.of(), Bounds.NONE, com.botmaker.plugin.api.ParameterGroup.DEFAULT_ID);
    }

    /**
     * Every constant of this activity's generated {@code Outcome} enum, in generated order: the implicit
     * {@link FlowEdge#NEXT_OUTCOME} first, then the declared {@link #outcomes()}. This is the source of the
     * enum body, and — through {@link #flowPorts()} — of the card's output ports, so the two can't drift.
     *
     * <p>{@link FlowEdge#DISABLED_OUTCOME} is filtered out here for the same reason a stored {@code NEXT} is:
     * both are outcomes every activity has already, so a file naming one must not emit it twice. Only
     * {@code NEXT} is re-added, because only {@code NEXT} is an enum constant.
     */
    public List<String> allOutcomes() {
        List<String> all = new ArrayList<>(outcomes.size() + 1);
        all.add(FlowEdge.NEXT_OUTCOME);
        for (String o : outcomes) {
            if (FlowEdge.DISABLED_OUTCOME.equals(o)) continue; // a port, never an Outcome constant
            if (!all.contains(o)) all.add(o); // a stored NEXT would otherwise duplicate the implicit one
        }
        return all;
    }

    /**
     * Every port the flow can be wired from: {@link #allOutcomes()}, then {@link FlowEdge#DISABLED_OUTCOME}
     * last.
     *
     * <p>Last rather than first because it is the exceptional one — every other port is a way the activity
     * <em>finished</em>, and this is the one for it never having run.
     */
    public List<String> flowPorts() {
        List<String> ports = new ArrayList<>(allOutcomes());
        ports.add(FlowEdge.DISABLED_OUTCOME);
        return ports;
    }

    public ActivityDefinition withEnabled(boolean newEnabled) {
        return new ActivityDefinition(name, newEnabled, description, outcomes, goHome, popupCheck, id);
    }

    public ActivityDefinition withDescription(String newDescription) {
        return new ActivityDefinition(name, enabled, newDescription, outcomes, goHome, popupCheck, id);
    }

    public ActivityDefinition withOutcomes(List<String> newOutcomes) {
        return new ActivityDefinition(name, enabled, description, newOutcomes, goHome, popupCheck, id);
    }

    public ActivityDefinition withGoHome(boolean newGoHome) {
        return new ActivityDefinition(name, enabled, description, outcomes, newGoHome, popupCheck, id);
    }

    public ActivityDefinition withPopupCheck(boolean newPopupCheck) {
        return new ActivityDefinition(name, enabled, description, outcomes, goHome, newPopupCheck, id);
    }

    /**
     * The same activity under a new name, keeping its id — which is the whole point of there being one.
     *
     * <p>Every other copy above carries the id through as bookkeeping; here carrying it <em>is</em> the
     * behaviour. Rename through this and a host sees one activity that changed its name; rebuild the record
     * from its parts instead and it sees one deleted and one created, over the user's own work.
     */
    public ActivityDefinition withName(String newName) {
        return new ActivityDefinition(newName, enabled, description, outcomes, goHome, popupCheck, id);
    }

}
