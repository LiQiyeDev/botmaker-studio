package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The declared range of a number variable — all three optional, all three stored as text so a
 * {@link com.botmaker.studio.palette.BotType#DURATION} bound can be written the way a duration is
 * ({@code "30s"}) rather than as the millisecond count nobody means.
 *
 * <p>They are advice to the widget and a clamp when the value is normalised, never a validation that can
 * fail: a value outside the range is pulled to the nearest bound, because the alternative is a project that
 * refuses to save because of a limit somebody tightened after the fact.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bounds(String min, String max, String step) {

    /** No range declared — the state every number variable starts in. */
    public static final Bounds NONE = new Bounds(null, null, null);

    public Bounds {
        min = blankToNull(min);
        max = blankToNull(max);
        step = blankToNull(step);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return min == null && max == null && step == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
