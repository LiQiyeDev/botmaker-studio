package com.botmaker.studio.state;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Undo/redo for one editing session, by <em>whole-state snapshots</em> rather than per-mutation inverses.
 *
 * <p>A command-with-an-inverse stack is the usual shape, and it is the wrong one here. The flow canvas has
 * eight mutations (move, link, unlink, switch on/off, set start, add, delete, auto-arrange) and several of them
 * have side effects on each other — deleting a card drops its wires, disabling the start activity moves the
 * start, renaming rewires every edge that named it. Each inverse would have to know all of that, and the day
 * one forgets is the day undo leaves a wire pointing at a card that isn't there. A snapshot cannot forget:
 * {@code before} is simply what the state was, and undo puts it back.
 *
 * <p>The same argument decided the code editor's history ({@link HistoryManager}): a signature change rewrites
 * the declaration and every call to it across files, and the inverse of that is not a rename backwards — it is
 * the files as they were. Which is why this class lives in {@code state/} under a neutral name rather than
 * beside the flow canvas it was written for.
 *
 * <p>Snapshots are cheap because what they hold is small — for the flow, a few dozen cards, each a name, two
 * doubles and a flag; for the editor, only the files one change actually wrote. In the flow's case the card
 * <em>objects</em> are shared, not copied, and that sharing is deliberate: the drafts are bound to the side
 * panel's fields, so an undo has to bring back the very object the panel is editing, not an equal one. It is
 * also why {@code S} must have a value-based {@code equals} — a no-op "mutation" (a drag that put the card back
 * where it started) is dropped by comparing before with after, and nothing else can tell.
 *
 * <p>Two ways to feed it, depending on whether the caller can read the current state on demand. The flow canvas
 * can, and passes a {@code capture} so {@link #mutate} and {@link #commit} can take the "after" themselves. The
 * code editor cannot — by the time a write is announced the old text is gone from everywhere but the
 * announcement — so it constructs with {@linkplain #SnapshotHistory(Consumer) restore alone} and hands both
 * halves to {@link #record}.
 *
 * @param <S> the snapshot type: an immutable value describing everything undo restores
 */
public final class SnapshotHistory<S> {

    /** Past a hundred steps this is a memory question with no user behind it — nobody undoes that far. */
    private static final int LIMIT = 100;

    private record Step<S>(String label, S before, S after) {}

    /** Null for a history whose caller records both halves itself — see {@link #record}. */
    private final Supplier<S> capture;
    private final Consumer<S> restore;
    private final Deque<Step<S>> done = new ArrayDeque<>();
    private final Deque<Step<S>> undone = new ArrayDeque<>();

    private final ReadOnlyBooleanWrapper canUndo = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper canRedo = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper undoLabel = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper redoLabel = new ReadOnlyStringWrapper("");

    /** Fired after anything is recorded, undone or redone — this is what autosave listens to. */
    private Runnable onChanged = () -> {};

    /** True while {@link #mutate} is running, so a mutation that provokes another records one step, not two. */
    private boolean batching;

    /**
     * @param capture reads the current state; must return a value with a meaningful {@code equals}
     * @param restore puts a state back, and must not record anything while doing so
     */
    public SnapshotHistory(Supplier<S> capture, Consumer<S> restore) {
        this.capture = capture;
        this.restore = restore;
    }

    /**
     * A history for a caller that knows both halves of every step and hands them to {@link #record}.
     *
     * <p>{@link #mutate}, {@link #commit} and {@link #mark} are unavailable on one of these — they exist to
     * <em>read</em> the current state, and a caller that could do that would have passed a {@code capture}.
     */
    public SnapshotHistory(Consumer<S> restore) {
        this(null, restore);
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo.getReadOnlyProperty(); }

    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo.getReadOnlyProperty(); }

    /** What the next undo would take back, for a tooltip; blank when there is nothing to take back. */
    public ReadOnlyStringProperty undoLabelProperty() { return undoLabel.getReadOnlyProperty(); }

    public ReadOnlyStringProperty redoLabelProperty() { return redoLabel.getReadOnlyProperty(); }

    /** Runs {@code change} and records it as one undoable step named {@code label}. */
    public void mutate(String label, Runnable change) {
        if (batching) {   // an inner mutation belongs to the outer step, not to one of its own
            change.run();
            return;
        }
        S before = mark();
        batching = true;
        try {
            change.run();
        } finally {
            batching = false;
        }
        commit(label, before);
    }

    /**
     * Records a change that has already happened, given the state from before it. For the two mutations that
     * cannot be wrapped in {@link #mutate}: a card drag (which begins and ends in different event handlers)
     * and a tick bound straight to a property (where the listener only ever runs afterwards).
     */
    public void commit(String label, S before) {
        if (batching) return;
        record(label, before, mark());
    }

    /**
     * Records a step whose two halves the caller already holds — the form for a state this history cannot read
     * on demand.
     */
    public void record(String label, S before, S after) {
        if (before.equals(after)) return;   // a drag that ended where it started is not a step
        done.push(new Step<>(label, before, after));
        while (done.size() > LIMIT) done.removeLast();
        undone.clear();
        published();
        onChanged.run();
    }

    /** The current state, to be handed back to {@link #commit} once the change it precedes has happened. */
    public S mark() {
        if (capture == null) {
            throw new IllegalStateException("This history has no way to read the current state; use record().");
        }
        return capture.get();
    }

    public void undo() {
        if (done.isEmpty()) return;
        Step<S> step = done.pop();
        restore.accept(step.before());
        undone.push(step);
        published();
        onChanged.run();
    }

    public void redo() {
        if (undone.isEmpty()) return;
        Step<S> step = undone.pop();
        restore.accept(step.after());
        done.push(step);
        published();
        onChanged.run();
    }

    /**
     * Forgets everything, leaving the current state as the new baseline. Used after loading a flow, and after
     * a change no snapshot describes — a rename, which rewrites the names every stored snapshot refers to, so
     * undoing across it would restore wires between activities that no longer answer to those names.
     *
     * <p>Deliberately silent: clearing is not itself an edit, so it does not fire {@link #setOnChanged}.
     */
    public void clear() {
        done.clear();
        undone.clear();
        published();
    }

    /** What the next undo would take back, for a caller that reads it rather than binding to it. */
    public String undoLabel() { return undoLabel.get(); }

    public String redoLabel() { return redoLabel.get(); }

    public boolean canUndo() { return canUndo.get(); }

    public boolean canRedo() { return canRedo.get(); }

    private void published() {
        canUndo.set(!done.isEmpty());
        canRedo.set(!undone.isEmpty());
        undoLabel.set(done.isEmpty() ? "" : done.peek().label());
        redoLabel.set(undone.isEmpty() ? "" : undone.peek().label());
    }
}
