package com.botmaker.studio.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The undo/redo stack behind the flow canvas and the code editor. No JavaFX scene is involved — the history
 * only reads and writes a snapshot through two lambdas, which is exactly why it was written that way: what
 * makes autosave safe is testable without a toolkit.
 *
 * <p>The snapshot here is a {@code String} standing in for a whole canvas, or for a set of files. Everything
 * asserted below is about the stack, not about what either of those is.
 */
public class SnapshotHistoryTest {

    /** A history over one mutable cell, the smallest thing with a state to restore. */
    private static final class Cell {
        private String value = "start";
        private final SnapshotHistory<String> history = new SnapshotHistory<>(() -> value, v -> value = v);
    }

    @Test
    void undoPutsBackWhatWasThereAndRedoBringsItForward() {
        Cell cell = new Cell();
        cell.history.mutate("first", () -> cell.value = "a");
        cell.history.mutate("second", () -> cell.value = "b");

        cell.history.undo();
        assertEquals("a", cell.value);
        cell.history.undo();
        assertEquals("start", cell.value);
        assertFalse(cell.history.canUndoProperty().get(), "nothing left to undo at the baseline");

        cell.history.redo();
        cell.history.redo();
        assertEquals("b", cell.value);
        assertFalse(cell.history.canRedoProperty().get());
    }

    @Test
    void aChangeThatChangedNothingIsNotAStep() {
        // The reason a card drag that ends where it began leaves the arrows grey: the snapshots are equal, so
        // there is no step. Without this, ↶ would have to be pressed once per aborted drag before it did
        // anything the user could see.
        Cell cell = new Cell();
        cell.history.mutate("a drag that went nowhere", () -> cell.value = "start");
        assertFalse(cell.history.canUndoProperty().get());
    }

    @Test
    void aMutationInsideAMutationIsOneStep() {
        // Switching off the start activity also moves the start, and that inner change must not become a
        // second ↶ — undoing the switch has to bring the start back with it.
        Cell cell = new Cell();
        cell.history.mutate("outer", () -> {
            cell.value = "a";
            cell.history.mutate("inner", () -> cell.value = "ab");
        });
        assertEquals("ab", cell.value);
        cell.history.undo();
        assertEquals("start", cell.value, "one undo takes back both halves");
        assertFalse(cell.history.canUndoProperty().get());
    }

    @Test
    void aNewStepDropsTheRedoBranch() {
        Cell cell = new Cell();
        cell.history.mutate("first", () -> cell.value = "a");
        cell.history.undo();
        assertTrue(cell.history.canRedoProperty().get());

        cell.history.mutate("a different first", () -> cell.value = "z");
        assertFalse(cell.history.canRedoProperty().get(), "the abandoned future is not offered back");
        cell.history.redo();
        assertEquals("z", cell.value, "redo with nothing to redo does nothing");
    }

    @Test
    void commitRecordsAChangeThatAlreadyHappened() {
        // The card-drag and tick paths: the change is made by something else (a bound property, a drag
        // handler), and the history is told afterwards, with the state from before.
        Cell cell = new Cell();
        String before = cell.history.mark();
        cell.value = "dragged";
        cell.history.commit("move the card", before);

        cell.history.undo();
        assertEquals("start", cell.value);
    }

    @Test
    void everyRecordedStepReportsItselfExactlyOnce() {
        // What autosave counts on: one write per step, and one per undo and redo — not one per inner mutation.
        Cell cell = new Cell();
        List<String> saves = new ArrayList<>();
        cell.history.setOnChanged(() -> saves.add(cell.value));

        cell.history.mutate("outer", () -> {
            cell.value = "a";
            cell.history.mutate("inner", () -> cell.value = "ab");
        });
        cell.history.undo();
        cell.history.redo();

        assertEquals(List.of("ab", "start", "ab"), saves);
    }

    @Test
    void clearingLeavesTheCurrentStateAsTheBaselineAndSaysNothing() {
        // A rename clears the history — the snapshots refer to names that no longer exist. It must not fire
        // the save callback, because clearing is not itself an edit; the rename does its own saving.
        Cell cell = new Cell();
        cell.history.mutate("first", () -> cell.value = "a");
        AtomicReference<Boolean> told = new AtomicReference<>(false);
        cell.history.setOnChanged(() -> told.set(true));

        cell.history.clear();
        assertFalse(cell.history.canUndoProperty().get());
        assertFalse(cell.history.canRedoProperty().get());
        assertEquals("a", cell.value, "clearing forgets the steps, not the state");
        assertFalse(told.get());
    }

    @Test
    void theArrowsSayWhatTheyWouldTakeBack() {
        Cell cell = new Cell();
        assertEquals("", cell.history.undoLabelProperty().get());
        cell.history.mutate("wire A to B", () -> cell.value = "a");
        assertEquals("wire A to B", cell.history.undoLabelProperty().get());
        cell.history.undo();
        assertEquals("wire A to B", cell.history.redoLabelProperty().get());
        assertEquals("", cell.history.undoLabelProperty().get());
    }

    @Test
    void aHistoryThatCannotReadTheStateStillRecordsBothHalves() {
        // The code editor's shape: by the time a write is announced the old text exists only in the
        // announcement, so both halves arrive together and there is nothing to capture.
        AtomicReference<String> file = new AtomicReference<>("before");
        SnapshotHistory<String> history = new SnapshotHistory<>(file::set);

        history.record("rename it", "before", "after");
        file.set("after");

        history.undo();
        assertEquals("before", file.get());
        history.redo();
        assertEquals("after", file.get());
    }

    @Test
    void aHistoryWithNoCaptureRefusesToInventOne() {
        SnapshotHistory<String> history = new SnapshotHistory<>(value -> { });
        assertThrows(IllegalStateException.class, history::mark);
    }
}
