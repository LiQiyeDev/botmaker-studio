package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Who a parameter is for: everyone who runs the bot, or only the person who is building it.
 *
 * <p>Every {@link ActivityVariable} is a knob, but they are not all the same kind of knob. "How many ore
 * before going home" is a setting the bot's user should be handed; "retry delay after a failed swipe" is a
 * number the editor tuned once and does not want reopened. Both are generated identically into
 * {@code Activities}, so the difference cannot be read off the field — it has to be declared, and this is
 * where it is declared.
 *
 * <p><b>Two different defaults, and both are deliberate.</b> A <em>new</em> variable is {@link #PUBLIC} —
 * {@link ActivityVariable}'s compact constructor fills an absent value with it, because a variable exists to
 * be configured and the tick box that says so is ticked. An <em>unrecognised</em> id, from a newer Studio,
 * reads as {@link #EDITOR_ONLY} ({@link #fromId}): "I don't know what this says" must not publish something
 * to the bot's user. The doc here used to claim the first case was EDITOR_ONLY too, which it has not been
 * since the tagged-variable model landed.
 */
public enum ParamVisibility {

    /** Offered to the bot's user in the Runner window. The editor is saying "this is yours to set". */
    PUBLIC("public", "Anyone running the bot"),

    /** Hidden from the user; only the editor sees it. Also how an unrecognised id reads — see above. */
    EDITOR_ONLY("editor", "Only me, while building");

    private final String id;
    private final String displayName;

    ParamVisibility(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The stable value written to {@code activities.json}. Do not change: it is persisted. */
    @JsonValue
    public String id() {
        return id;
    }

    /** How this reads in the parameters dialog's picker. */
    public String displayName() {
        return displayName;
    }

    /**
     * The visibility {@code id} names, or {@link #EDITOR_ONLY} for anything unrecognised — including
     * {@code null}. Total by design: a value from a newer Studio must not stop the whole
     * {@code activities.json} from loading, and the safe reading of "I don't know what this is" is "don't
     * show it to the user".
     */
    @JsonCreator
    public static ParamVisibility fromId(String id) {
        if (id == null) return EDITOR_ONLY;
        for (ParamVisibility v : values()) {
            if (v.id.equalsIgnoreCase(id) || v.name().equalsIgnoreCase(id)) return v;
        }
        return EDITOR_ONLY;
    }
}
