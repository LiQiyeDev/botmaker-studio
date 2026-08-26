package com.botmaker.studio.ui.app.overlay;

import com.botmaker.sdk.api.interaction.Mouse;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record → drain → insert handoff, driven headless. In the HUD each step of it is separated by a real
 * source rewrite and a JavaFX pulse, which is why this path had no coverage at all; here the insert function is
 * a list and {@link RecordedBatchInserter#tick()} stands in for the re-parse, so the whole state machine is
 * exercised in-process.
 *
 * <p>What it is guarding: blocks arriving in the recorded order and <em>one per re-parse</em> (inserting the
 * batch in one go would address blocks the first insert already replaced), and auto-fill staying suppressed for
 * exactly as long as the batch is draining — a dozen recorded clicks each popping an argument editor buries the
 * HUD behind its own popovers.
 */
class RecordedBatchInserterTest {

    private final List<String> inserted = new ArrayList<>();
    /** Stands in for {@code Platform.runLater}: deferred work is held, then released by {@link #pulse()}. */
    private final Deque<Runnable> deferred = new ArrayDeque<>();
    private final RecordedBatchInserter inserter =
            new RecordedBatchInserter(b -> inserted.add(b.id()), deferred::add);

    private static BlockType block(String id) {
        return new BlockType.LibraryCall(id, id, BlockCategory.INPUT, Mouse.class, "click", List.of());
    }

    /** Runs whatever the inserter deferred to the next FX pulse. */
    private void pulse() {
        Runnable r = deferred.poll();
        if (r != null) r.run();
    }

    @Test
    void aBatchIsInsertedOneBlockPerReparseInOrder() {
        inserter.enqueue(List.of(block("a"), block("b"), block("c")));

        // The first block goes in immediately; the rest wait for a re-parse each.
        assertEquals(List.of("a"), inserted);
        assertTrue(inserter.isDraining());

        inserter.tick();                       // the re-parse of "a" lands
        assertEquals(List.of("a"), inserted, "the next block waits for the pulse, not just the re-parse");
        pulse();
        assertEquals(List.of("a", "b"), inserted);

        inserter.tick();
        pulse();
        assertEquals(List.of("a", "b", "c"), inserted);

        // The batch is exhausted, but nothing knows that until the last insert's re-parse comes back.
        assertTrue(inserter.isDraining());
        inserter.tick();
        assertFalse(inserter.isDraining());
        assertTrue(deferred.isEmpty(), "no work is queued past the end of the batch");
    }

    @Test
    void autoFillStaysSuppressedUntilTheLastBlockIsIn() {
        assertFalse(inserter.suppressesAutoFill(), "an idle inserter suppresses nothing");

        inserter.enqueue(List.of(block("a"), block("b")));
        assertTrue(inserter.suppressesAutoFill());

        // Still suppressed while the *last* block's own re-parse is being handled — that handler is where the
        // popover would be opened, and it runs before the tick that ends the drain.
        inserter.tick();
        pulse();
        assertTrue(inserter.suppressesAutoFill());

        inserter.tick();
        assertFalse(inserter.suppressesAutoFill());
    }

    @Test
    void anEmptyBatchNeverStartsADrain() {
        inserter.enqueue(List.of());
        assertTrue(inserted.isEmpty());
        assertFalse(inserter.isDraining());

        inserter.enqueue(null);
        assertTrue(inserted.isEmpty());
        assertFalse(inserter.isDraining());

        // A tick with no batch in flight is a no-op rather than a dequeue of nothing.
        inserter.tick();
        assertTrue(deferred.isEmpty());
        assertFalse(inserter.isDraining());
    }
}
