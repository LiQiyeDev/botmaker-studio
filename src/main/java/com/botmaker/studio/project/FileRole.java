package com.botmaker.studio.project;

import java.nio.file.Path;

/**
 * What a project file <em>is</em>, from the editor's point of view — the single source of truth for which
 * files the user owns.
 *
 * <p>Before this existed the rules were duplicated as inline path checks in {@code CodeEditorService.refreshUI}
 * and half-mirrored in {@code FileExplorerManager}'s cell factory, which is how a generated file ended up
 * read-only in the editor but freely deletable (with no confirmation) from the tree. Anything that needs to
 * know "may the user change this?" asks {@link #of} — do not re-derive it from a path.
 *
 * <h2>There used to be a third role, and it is worth knowing why there is not</h2>
 *
 * <p>{@code GENERATED} classified the files BotMaker wrote into a user's own source tree — the game-bot entry
 * point, {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates} — and locked
 * them, because an edit that appears to work and then vanishes on the next regeneration is worse than one
 * that was never offered. Around it grew {@code MethodLock} (a method-level grant inside a locked file),
 * {@code GeneratedMembers} (a member-level lock inside an editable one), {@code LockedRegions} (what may
 * reach disk) and {@code MemberVisibility} (what is worth drawing).
 *
 * <p>All of it went on 2026-08-29, because <b>nothing generates a project's Java any more</b>. A project's
 * structure belongs to the user: what BotMaker writes, it writes once at creation and never reads back. A
 * lock over files nobody rewrites protects nothing and only refuses the owner their own code.
 *
 * <p>So the question this enum answers is now the small one it started as: is this file the user's, or is it
 * bundled library source they happen to be able to open? Enforcement of the <em>other</em> read-only case —
 * a bot installed from the gallery, opened for reading — is {@link ProjectMode}'s, and is applied in
 * {@link LockResolver} rather than here: it is a property of the checkout, not of the file.
 */
public enum FileRole {

    /** Ordinary user code. Fully editable, edits persist. */
    EDITABLE,

    /** Bundled library source under {@code com/botmaker/library}. Fully locked: no interaction at all. */
    LIBRARY;

    /** True when this file's contents are not the user's to change. */
    public boolean isReadOnly() {
        return this != EDITABLE;
    }

    /** True when blocks default to refusing interaction (menus suppressed). */
    public boolean suppressesInteraction() {
        return this != EDITABLE;
    }

    /** A short suffix for the editor status line / explorer label, or {@code null} for ordinary files. */
    public String badge() {
        return switch (this) {
            case EDITABLE -> null;
            case LIBRARY -> "Library - Read Only";
        };
    }

    /**
     * Classifies {@code file}. Never null; anything that is not bundled library source is {@link #EDITABLE},
     * so a file the Studio doesn't recognise always belongs to the user — which, since nothing is generated,
     * is every file in the project.
     */
    public static FileRole of(Path file) {
        if (file == null) return EDITABLE;
        return file.toString().replace("\\", "/").contains("com/botmaker/library") ? LIBRARY : EDITABLE;
    }
}
