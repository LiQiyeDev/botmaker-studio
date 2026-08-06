package com.botmaker.studio.project.vcs;

/**
 * How a path in the working tree differs from the last commit — the four buckets
 * {@link ProjectVcs.FileStatus} sorts JGit's status into, as a type rather than a word.
 *
 * <p>It was a bare {@code String} produced in one place ({@code FileStatus.labelled()}) and consumed in three
 * others: a colour {@code switch}, a {@code "new".equals(...)} test choosing between "delete the file" and
 * "restore it from the last commit", and the tag text itself. The set had already drifted — the producer emits
 * four labels and the colour {@code switch} only listed three, so a staged addition rendered grey by falling
 * through {@code default}. Nothing here crosses a process boundary, so the constants carry no wire id.
 */
public enum VcsFileStatus {

    /** Not tracked at all: git has never seen it, so discarding means deleting it. */
    NEW("new", "#1a7f37", true),
    /**
     * Staged for the next commit. Deliberately <em>not</em> {@link #uncommitted()}: {@code FileStatus} folds
     * JGit's {@code getChanged()} (a staged <em>modification</em>) into this bucket alongside
     * {@code getAdded()}, so a path here may well have committed content, and treating the whole bucket as
     * deletable would throw away edits to a tracked file.
     */
    ADDED("added", "#1a7f37", false),
    /** Tracked and edited; discarding restores the committed content. */
    MODIFIED("modified", "#9a6700", false),
    /** Tracked and gone from the working tree; discarding brings it back. */
    DELETED("deleted", "#cf222e", false);

    private final String label;
    private final String color;
    private final boolean uncommitted;

    VcsFileStatus(String label, String color, boolean uncommitted) {
        this.label = label;
        this.color = color;
        this.uncommitted = uncommitted;
    }

    /** The one-word tag shown beside the file name. */
    public String label() {
        return label;
    }

    /** The tag's colour, as a CSS value for the inline {@code -fx-text-fill}. */
    public String color() {
        return color;
    }

    /**
     * True when the path has no committed content to go back to, so "discard" means <em>delete</em>. Only
     * {@link #NEW} qualifies — see {@link #ADDED} for why the staged bucket does not.
     */
    public boolean uncommitted() {
        return uncommitted;
    }
}
