package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A named on/off selection of activities — a quick way to say "run these, skip the rest" without touching
 * the wiring or order. Applying a preset flips each activity's {@link ActivityDefinition#enabled()} flag:
 * an activity is enabled iff its name is in {@link #enabledActivities()}.
 *
 * <p>Two built-ins are always offered ({@link #everything()} and {@link #nothing()}); the user can save the
 * current selection as more, which persist in {@code activities.json} alongside the flow.
 *
 * @param name              the preset's display name
 * @param enabledActivities the activity names this preset turns on (all others are turned off)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityPreset(String name, List<String> enabledActivities) {

    /** Built-in preset name: every activity on. */
    public static final String EVERYTHING = "Everything";
    /** Built-in preset name: every activity off. */
    public static final String NOTHING = "Nothing";

    public ActivityPreset {
        if (name == null) name = "";
        enabledActivities = enabledActivities == null ? List.of() : List.copyOf(enabledActivities);
    }

    /** True when this preset turns {@code activityName} on. */
    public boolean enables(String activityName) {
        return enabledActivities.contains(activityName);
    }

    /** The built-in "everything on" preset over the given activity names. */
    public static ActivityPreset everything(List<String> allActivityNames) {
        return new ActivityPreset(EVERYTHING, List.copyOf(allActivityNames));
    }

    /** The built-in "everything off" preset. */
    public static ActivityPreset nothing() {
        return new ActivityPreset(NOTHING, List.of());
    }

    /** A preset capturing exactly the currently-enabled activities of {@code config}. */
    public static ActivityPreset fromCurrent(String name, ActivitiesConfig config) {
        Set<String> on = new LinkedHashSet<>();
        for (ActivityDefinition a : config.activities()) {
            if (a.enabled()) on.add(a.name());
        }
        return new ActivityPreset(name, List.copyOf(on));
    }
}
