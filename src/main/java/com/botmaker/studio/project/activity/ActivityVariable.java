package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One editor-defined "activity": a named, typed value the bot uses. The editor (bot maker)
 * defines {@link #name()}, {@link #type()}, {@link #visibility()} and an optional {@link #description()};
 * whoever runs the bot fills in {@link #value()} — if the editor chose to offer it.
 *
 * <p>{@link #options()} carries the choices for the two types that have any ({@link ActivityType#CHOICE} and
 * {@link ActivityType#MULTI_CHOICE}) and is empty for every other type. They live on the variable rather than
 * in a free-form blob so the set is closed where it is used: the value widget can only offer what is declared,
 * and a value that is no longer one of the options is visible as such instead of silently persisting.
 *
 * @param name        the variable name (must be a valid Java identifier; becomes a field on the
 *                    generated {@code Activities} class)
 * @param type        the curated activity type
 * @param value       the user-supplied value as a JSON node (defaults from {@link ActivityType#defaultValue()})
 * @param description an optional human-readable note explaining what the activity is for (may be empty)
 * @param visibility  whether the bot's user is offered this at all; absent ⇒ {@link ParamVisibility#EDITOR_ONLY}
 * @param options     the declared choices, for {@link ActivityType#hasOptions() option-bearing} types only
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityVariable(String name, ActivityType type, JsonNode value, String description,
                               ParamVisibility visibility, List<String> options) {

    public ActivityVariable {
        if (description == null) description = "";
        if (visibility == null) visibility = ParamVisibility.EDITOR_ONLY;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Back-compat constructor for callers that don't set a visibility or options. */
    public ActivityVariable(String name, ActivityType type, JsonNode value, String description) {
        this(name, type, value, description, ParamVisibility.EDITOR_ONLY, List.of());
    }

    /** Back-compat constructor for callers that don't carry a description (older {@code activities.json}). */
    public ActivityVariable(String name, ActivityType type, JsonNode value) {
        this(name, type, value, "");
    }

    /** A fresh activity with the type's default value and no description. */
    public static ActivityVariable create(String name, ActivityType type) {
        return new ActivityVariable(name, type, type.defaultValue(), "");
    }

    /** A fresh activity with the type's default value and the given description. */
    public static ActivityVariable create(String name, ActivityType type, String description) {
        return new ActivityVariable(name, type, type.defaultValue(), description);
    }

    /** True when the bot's user is offered this parameter in the Runner window. */
    public boolean isPublic() {
        return visibility == ParamVisibility.PUBLIC;
    }

    /** This activity with its value replaced (everything else kept). */
    public ActivityVariable withValue(JsonNode newValue) {
        return new ActivityVariable(name, type, newValue, description, visibility, options);
    }

    /** This activity with its description replaced (everything else kept). */
    public ActivityVariable withDescription(String newDescription) {
        return new ActivityVariable(name, type, value, newDescription, visibility, options);
    }

    /** This activity with its name replaced (everything else kept). */
    public ActivityVariable withName(String newName) {
        return new ActivityVariable(newName, type, value, description, visibility, options);
    }

    /** This activity with its visibility replaced (everything else kept). */
    public ActivityVariable withVisibility(ParamVisibility newVisibility) {
        return new ActivityVariable(name, type, value, description, newVisibility, options);
    }

    /**
     * This activity retyped, its value reset to the new type's default and its options dropped unless the new
     * type has any. Retyping deliberately does not try to carry the old value across: a date is not a number
     * and pretending otherwise writes something the generated loader would silently discard anyway.
     */
    public ActivityVariable withType(ActivityType newType) {
        return new ActivityVariable(name, newType, newType.defaultValue(), description, visibility,
                newType.hasOptions() ? options : List.of());
    }

    /**
     * This activity with its declared choices replaced, its value pruned to what is still on offer.
     *
     * <p>Pruning is the point: an option the editor has just deleted must stop being a stored value, or the
     * bot runs on a setting that no longer appears anywhere in the UI that set it.
     */
    public ActivityVariable withOptions(List<String> newOptions) {
        List<String> declared = newOptions == null ? List.of() : List.copyOf(newOptions);
        return new ActivityVariable(name, type, type.pruneValue(value, declared), description, visibility,
                declared);
    }
}
