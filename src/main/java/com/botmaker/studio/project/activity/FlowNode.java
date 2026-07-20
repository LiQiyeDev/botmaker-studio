package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One card on the {@link ActivityFlow} canvas: which activity it is, and where it sits. Position is purely
 * presentational — it restores the visual layout when the flow dialog reopens; what actually runs comes from
 * the wiring ({@link ActivityFlow#reachable}) and the start node, never from positions.
 *
 * <p>There is no terminal card. A run ends at an outcome with no wire leaving it, which is the same thing a
 * Stop card used to say and one fewer concept to draw. An older {@code activities.json} may still contain the
 * old {@code @stop} node and edges into it; both are ignored on load, because the node names no activity and
 * every walk drops a wire whose endpoint isn't a placed activity.
 *
 * @param activity the {@link ActivityDefinition#name()} this node represents
 * @param x        canvas x of the node's top-left, in unscaled canvas coordinates
 * @param y        canvas y of the node's top-left, in unscaled canvas coordinates
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowNode(String activity, double x, double y) {

    public FlowNode {
        if (activity == null) activity = "";
    }

    public FlowNode withPosition(double newX, double newY) {
        return new FlowNode(activity, newX, newY);
    }
}
