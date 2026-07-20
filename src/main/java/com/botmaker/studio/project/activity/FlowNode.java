package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The canvas placement of one activity in the {@link ActivityFlow} editor: which activity, and where its
 * node card sits on the free-form canvas. Purely presentational — it restores the visual layout when the
 * flow dialog reopens; run order comes from {@link ActivityFlow#order} (the wiring), not from positions.
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
