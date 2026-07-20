package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a {@link FlowNode} on the Activity Flow canvas <em>is</em>. Most nodes are an activity; {@link #STOP}
 * is the terminal card that ends the run when the flow reaches it.
 *
 * <p>An enum rather than a reserved activity name (the obvious alternative was a magic {@code "@stop"}
 * string): the terminal is asked about in the rules, the canvas, the generator and the reachability walk, and
 * a string would put the same literal comparison in all four with nothing keeping them honest. It is also the
 * repo-wide rule — type a closed set instead of passing a bare {@code String}.
 *
 * <p>{@link #id()} is the persisted wire value and must stay stable; {@link #fromId} is total (unknown →
 * {@link #ACTIVITY}) so an {@code activities.json} written by a newer Studio still loads.
 */
public enum FlowNodeKind {

    /** An activity card: runs its activity, then routes on the outcome it reported. */
    ACTIVITY("activity"),

    /** The terminal card: reaching it ends the bot cleanly (a generated {@code Bot.stop()}). */
    STOP("stop");

    private final String id;

    FlowNodeKind(String id) {
        this.id = id;
    }

    /** The stable value persisted in {@code activities.json}. */
    @JsonValue
    public String id() {
        return id;
    }

    /** Parses a persisted {@link #id()}; never throws — anything unrecognised reads as {@link #ACTIVITY}. */
    @JsonCreator
    public static FlowNodeKind fromId(String id) {
        for (FlowNodeKind kind : values()) {
            if (kind.id.equals(id)) return kind;
        }
        return ACTIVITY;
    }
}
