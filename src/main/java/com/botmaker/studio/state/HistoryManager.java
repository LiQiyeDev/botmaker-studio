package com.botmaker.studio.state;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The editor's undo/redo, over <em>the files a change wrote</em>.
 *
 * <p>It used to be a stack of one string: the active file's text, before each edit. That was true of every
 * edit until signature migration arrived, and false the moment one change rewrote a declaration in one file
 * and its calls in three others — ↶ put back {@code Bot.java} and left the other three calling a function that
 * no longer answers to that name. A single string cannot describe that change, so it cannot take it back.
 *
 * <p>A step is therefore a pair of {@code path → text} maps: what those files said before, and what they say
 * after. <b>Only the files the change actually wrote</b> are in it, so an ordinary one-file edit costs exactly
 * what it did before — one string in, one string out — and nothing walks the project to take a snapshot of
 * files nobody touched.
 *
 * <p>The stack itself is {@link SnapshotHistory}, shared with the flow canvas: same argument for snapshots over
 * per-mutation inverses, and it is the second reason that class no longer lives beside the canvas. This one is
 * built with restore alone — by the time a write is announced, what the file said before it exists only in the
 * announcement, so there is nothing left to capture and both halves are handed to
 * {@link SnapshotHistory#record}.
 */
public class HistoryManager {

    private final SnapshotHistory<Map<Path, String>> history;

    /**
     * @param restore puts one step's files back — writes each path's text and refreshes whatever is showing it
     */
    public HistoryManager(Consumer<Map<Path, String>> restore) {
        this.history = new SnapshotHistory<>(restore::accept);
    }

    /**
     * Records one edit: the files it wrote, as they were and as they are.
     *
     * <p>Both maps are expected to name the same files. A step whose two halves are equal is dropped by the
     * history itself — which is what the old duplicate-guard here was for, when several events could fire for
     * one write.
     */
    public void record(String label, Map<Path, String> before, Map<Path, String> after) {
        if (before.isEmpty()) return;
        history.record(label == null || label.isBlank() ? "the last change" : label,
                Map.copyOf(before), Map.copyOf(after));
    }

    public boolean canUndo() { return history.canUndo(); }

    public boolean canRedo() { return history.canRedo(); }

    /** What ↶ would take back, for the menu item to name; blank when there is nothing to take back. */
    public String undoLabel() { return history.undoLabel(); }

    public String redoLabel() { return history.redoLabel(); }

    public void undo() { history.undo(); }

    public void redo() { history.redo(); }

    public void clear() { history.clear(); }
}
