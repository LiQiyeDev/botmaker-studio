package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.BooleanNode;

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
 * @param name        activity name / generated class name (a valid Java identifier)
 * @param enabled     the default value of the enable flag
 * @param description optional human-readable note (may be empty)
 * @param params      the activity's config variables ("how to do it")
 * @param archived    retired: keeps its file and fields, but doesn't appear on the canvas or run
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params,
                                 boolean archived) {

    public ActivityDefinition {
        if (description == null) description = "";
        params = params == null ? List.of() : List.copyOf(params);
    }

    /** Convenience for the common live activity; an {@code activities.json} without the field loads this way. */
    public ActivityDefinition(String name, boolean enabled, String description, List<ActivityVariable> params) {
        this(name, enabled, description, params, false);
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

    public ActivityDefinition withEnabled(boolean newEnabled) {
        return new ActivityDefinition(name, newEnabled, description, params, archived);
    }

    public ActivityDefinition withDescription(String newDescription) {
        return new ActivityDefinition(name, enabled, newDescription, params, archived);
    }

    public ActivityDefinition withParams(List<ActivityVariable> newParams) {
        return new ActivityDefinition(name, enabled, description, newParams, archived);
    }

    public ActivityDefinition withArchived(boolean newArchived) {
        return new ActivityDefinition(name, enabled, description, params, newArchived);
    }
}
