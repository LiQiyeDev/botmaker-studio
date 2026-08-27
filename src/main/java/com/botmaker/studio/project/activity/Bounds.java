package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The declared range of a number variable — <b>both ends optional and independent</b>, both stored as text so
 * a {@code DURATION} bound can be written the way a duration is
 * ({@code "30s"}) rather than as the millisecond count nobody means.
 *
 * <p>Independent is the point: "at most 10" is a sentence a person says, and it used to be unsayable here
 * because the widget only appeared once <em>both</em> ends were filled in. A missing end is the type's own
 * limit, not a reason to fall back to an unguided text field.
 *
 * <p>They are advice to the widget and a clamp when the value is normalised, never a validation that can
 * fail: a value outside the range is pulled to the nearest bound, because the alternative is a project that
 * refuses to save because of a limit somebody tightened after the fact.
 *
 * <p>There was a third field, {@code step}. It is gone: for a whole number the step is 1 and saying so adds
 * nothing, and for a decimal it was actively wrong — a step of 0.1 makes 0.05 unreachable by the arrows and
 * turns the editor into a coarser instrument than the type it is editing. Old projects that stored one still
 * open; the value is ignored ({@link JsonIgnoreProperties}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bounds(String min, String max) {

    /** No range declared — the state every number variable starts in. */
    public static final Bounds NONE = new Bounds(null, null);

    public Bounds {
        min = blankToNull(min);
        max = blankToNull(max);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return min == null && max == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
