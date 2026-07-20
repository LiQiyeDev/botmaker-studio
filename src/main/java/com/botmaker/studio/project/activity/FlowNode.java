package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One card on the {@link ActivityFlow} canvas: what it is, and where it sits. Position is purely
 * presentational — it restores the visual layout when the flow dialog reopens; what actually runs comes from
 * the wiring ({@link ActivityFlow#reachable}) and the start node, never from positions.
 *
 * <p>A {@link FlowNodeKind#STOP} node has no activity of its own: {@link #activity()} is its <em>id</em>
 * ({@link ActivityFlow#STOP_ID}), which is what edges name when they end the run. Keeping one field for both
 * means edges stay a plain pair of node ids and every walk treats the terminal like any other target.
 *
 * @param activity the {@link ActivityDefinition#name()} this node represents, or the node id for a STOP card
 * @param x        canvas x of the node's top-left, in unscaled canvas coordinates
 * @param y        canvas y of the node's top-left, in unscaled canvas coordinates
 * @param kind     what this card is; absent in an older {@code activities.json} ⇒ {@link FlowNodeKind#ACTIVITY}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowNode(String activity, double x, double y, FlowNodeKind kind) {

    public FlowNode {
        if (activity == null) activity = "";
        if (kind == null) kind = FlowNodeKind.ACTIVITY;
    }

    /** An activity card — the common case, and how every pre-STOP {@code activities.json} loads. */
    public FlowNode(String activity, double x, double y) {
        this(activity, x, y, FlowNodeKind.ACTIVITY);
    }

    /** The terminal card at {@code (x, y)}; reaching it ends the bot. */
    public static FlowNode stop(double x, double y) {
        return new FlowNode(ActivityFlow.STOP_ID, x, y, FlowNodeKind.STOP);
    }

    /** True when this is the terminal card rather than an activity. */
    public boolean isStop() {
        return kind == FlowNodeKind.STOP;
    }

    public FlowNode withPosition(double newX, double newY) {
        return new FlowNode(activity, newX, newY, kind);
    }
}
