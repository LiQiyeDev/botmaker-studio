package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.palette.BlockType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * The drain queue for a finished recording: a batch of translated blocks inserted <em>one per re-parse</em>
 * rather than all at once.
 *
 * <p>It has to be one at a time. An insert goes through {@code CodeEditor}, which rewrites the source and
 * republishes a whole new block tree — every block object the batch's second insert would have addressed is
 * already dead by the time the first one lands. So the queue inserts one block, waits for the coordinator to
 * re-home the cursor onto it from the republished tree, and only then offers the next: {@link #tick()} is that
 * signal, called from the coordinator's blocks-updated handler.
 *
 * <p>The insert function and the "next pulse" scheduler are both injected, which is the point — with a fake
 * insert and a run-it-now scheduler the whole state machine runs headless, with no FX toolkit and no re-parse
 * at all. Owning it here was the sanctioned unblocker for the record→drain→insert path having no test coverage
 * (docs/refactor/14-studio-ui.md, MISSING 2); it is exercised by {@code RecordedBatchInserterTest}.
 */
final class RecordedBatchInserter {

    private final Deque<BlockType> queue = new ArrayDeque<>();
    /** Where a block goes; in the HUD, insert-below-the-cursor. */
    private final Consumer<BlockType> insert;
    /** Runs its argument on the next FX pulse ({@code Platform::runLater}); {@code Runnable::run} in a test. */
    private final Consumer<Runnable> defer;
    private boolean draining;

    RecordedBatchInserter(Consumer<BlockType> insert, Consumer<Runnable> defer) {
        this.insert = insert;
        this.defer = defer;
    }

    /** Takes a translated recording and inserts its first block immediately. An empty batch is a no-op. */
    void enqueue(List<BlockType> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        queue.addAll(blocks);
        draining = true;
        insertNext();
    }

    /** Called once a re-parse has landed: offers the next block of the batch, or ends the drain. */
    void tick() {
        if (!draining) return;
        if (queue.isEmpty()) {
            draining = false;
            return;
        }
        defer.accept(this::insertNext);
    }

    /** Whether a batch is still being inserted — the coordinator keeps calling {@link #tick()} while it is. */
    boolean isDraining() {
        return draining;
    }

    /**
     * Whether a freshly inserted call should skip its argument popover. Recorded calls already carry concrete
     * coordinates, so popping an editor for each of a dozen of them would bury the HUD. It is the same
     * condition as {@link #isDraining()} and deliberately a second name: they answer different questions and
     * only happen to coincide.
     */
    boolean suppressesAutoFill() {
        return draining;
    }

    private void insertNext() {
        BlockType next = queue.poll();
        if (next == null) {
            draining = false;
            return;
        }
        insert.accept(next);
    }
}
