package com.botmaker.studio.core.component;

/**
 * Who the canvas is being rendered <em>for</em> — the axis Studio has never had.
 *
 * <p>Until now the person who wrote a bot and the person who runs it were the same person as far as the
 * editor was concerned, so every component of every block was rendered unconditionally. That is why a user
 * sees an activity's generated {@code Outcome} enum, scaffold static fields, and read-only method shells that
 * mean nothing to them: not because anyone decided they should, but because there was nothing to ask.
 *
 * <p>This is <b>not</b> a permission level and must not be used as one. {@link com.botmaker.studio.project.LockResolver}
 * remains the only authority on whether an edit is allowed; {@code Audience} decides only whether a component
 * is worth <em>showing</em>. A component hidden from {@link #USER} is not thereby protected — if it also has
 * to be unmodifiable, the lock that says so lives in {@code LockResolver}, as it did before.
 *
 * <p>Its reach narrowed on 2026-08-29. A {@code USER} parse used to drop whole members from the tree —
 * a generated {@code Outcome} enum, a scaffold's static field, a read-only method shell — through a
 * {@code MemberVisibility} class that is gone with them. Nothing generates a project's Java, so there is no
 * member that is not the user's, and what is left is the component-level rule: which <em>parts</em> of a
 * block are worth showing to someone who did not write this bot.
 */
public enum Audience {

    /** The bot's author: everything is shown, including the scaffolding they are responsible for. */
    EDITOR,

    /** Someone running or reading a bot they did not write: only what is theirs to look at and change. */
    USER;

    /** True when scaffolding and generated members should be rendered at all. */
    public boolean seesScaffolding() {
        return this == EDITOR;
    }
}
