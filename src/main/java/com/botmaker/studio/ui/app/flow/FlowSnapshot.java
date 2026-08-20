package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.FlowEdge;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything an undo puts back: which cards are on the canvas, where each sits, which are switched on, how
 * they are wired, and where the run starts.
 *
 * <p>It holds the {@link ActivityDraft}s themselves rather than copies of them, so restoring a deleted card
 * brings back the object the side panel and the card's toggles are bound to. What it copies is the part that
 * changes — position, the enable flag, the wiring — so two snapshots taken either side of a mutation differ
 * exactly where the mutation did. That is what lets {@link com.botmaker.studio.state.SnapshotHistory} drop a
 * no-op step by comparing them.
 *
 * <p>Names, descriptions and outcomes are <em>not</em> here: those are edited as text, and a rename rewrites
 * every wire that mentioned it, which no earlier snapshot could be restored across. {@code SnapshotHistory.clear()}
 * is what covers that case, rather than a wider snapshot that would still get it wrong.
 */
public record FlowSnapshot(List<CardState> cards, List<FlowEdge> edges, String start) {

    /** One card, as far as undo is concerned. */
    public record CardState(ActivityDraft draft, double x, double y, boolean enabled) {}

    public FlowSnapshot {
        cards = List.copyOf(cards);
        edges = List.copyOf(edges);
    }

    /** This snapshot with {@code draft}'s enable flag set to {@code enabled} — see
     *  {@link com.botmaker.studio.state.SnapshotHistory#commit}. */
    public FlowSnapshot withEnabled(ActivityDraft draft, boolean enabled) {
        List<CardState> changed = new ArrayList<>(cards.size());
        for (CardState card : cards) {
            changed.add(card.draft() == draft
                    ? new CardState(card.draft(), card.x(), card.y(), enabled)
                    : card);
        }
        return new FlowSnapshot(changed, edges, start);
    }

    /** This snapshot with a different starting activity. */
    public FlowSnapshot withStart(String newStart) {
        return new FlowSnapshot(cards, edges, newStart);
    }

    /** The drafts, in canvas order. */
    public List<ActivityDraft> drafts() {
        List<ActivityDraft> drafts = new ArrayList<>(cards.size());
        for (CardState card : cards) drafts.add(card.draft());
        return drafts;
    }
}
