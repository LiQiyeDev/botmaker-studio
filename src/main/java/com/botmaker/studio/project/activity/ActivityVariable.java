package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One editor-defined global "activity": a named, typed value the bot uses. The editor (bot maker)
 * defines {@link #name()}, {@link #type()} and an optional {@link #description()}; the user (bot
 * operator) fills in {@link #value()}.
 *
 * @param name        the variable name (must be a valid Java identifier; becomes a field on the
 *                    generated {@code Activities} class)
 * @param type        the curated activity type
 * @param value       the user-supplied value as a JSON node (defaults from {@link ActivityType#defaultValue()})
 * @param description an optional human-readable note explaining what the activity is for (may be empty)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityVariable(String name, ActivityType type, JsonNode value, String description) {

    public ActivityVariable {
        if (description == null) description = "";
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

    /** This activity with its value replaced (keeps name, type and description). */
    public ActivityVariable withValue(JsonNode newValue) {
        return new ActivityVariable(name, type, newValue, description);
    }

    /** This activity with its description replaced (keeps name, type and value). */
    public ActivityVariable withDescription(String newDescription) {
        return new ActivityVariable(name, type, value, newDescription);
    }
}
