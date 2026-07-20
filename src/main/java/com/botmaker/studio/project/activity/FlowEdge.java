package com.botmaker.studio.project.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One wire in the {@link ActivityFlow}: "when {@code from} finishes, {@code to} runs next". In v1 the flow
 * is a single linear chain, so every activity has at most one outgoing and one incoming edge; this is
 * enforced by the canvas editor, and {@link ActivityFlow#order} relies on it to linearize the chain.
 *
 * @param from source activity name (the {@link FlowNode#activity()} the wire leaves)
 * @param to   target activity name (the {@link FlowNode#activity()} the wire enters)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowEdge(String from, String to) {

    public FlowEdge {
        if (from == null) from = "";
        if (to == null) to = "";
    }
}
