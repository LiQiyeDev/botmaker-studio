package com.botmaker.state;

import java.util.Stack;

public class HistoryManager {

    private final Stack<String> undoStack = new Stack<>();
    private final Stack<String> redoStack = new Stack<>();
    private static final int MAX_HISTORY_SIZE = 50; // Limit memory usage

    /**
     * Saves a snapshot of the code.
     * Call this BEFORE applying a new change.
     */
    public void pushState(String code) {
        // Avoid saving duplicates (e.g. if multiple events fire for same code)
        if (!undoStack.isEmpty() && undoStack.peek().equals(code)) {
            return;
        }

        undoStack.push(code);

        // Enforce size limit
        if (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.remove(0);
        }

        // New change clears the redo future
        redoStack.clear();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public String undo(String currentCode) {
        if (!canUndo()) return currentCode;

        // Save current state to Redo stack
        redoStack.push(currentCode);

        // Return previous state
        return undoStack.pop();
    }

    public String redo(String currentCode) {
        if (!canRedo()) return currentCode;

        // Save current state to Undo stack
        undoStack.push(currentCode);

        // Return future state
        return redoStack.pop();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}