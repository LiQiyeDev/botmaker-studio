package com.botmaker.studio.project;

import java.nio.file.Path;

/**
 * What a project file <em>is</em>, from the editor's point of view — the single source of truth for which
 * files the user owns and which are scaffolding.
 *
 * <p>Before this existed the rules were duplicated as inline path checks in {@code CodeEditorService.refreshUI}
 * and half-mirrored in {@code FileExplorerManager}'s cell factory, which is how {@code ActivityRegistry.java}
 * ended up read-only in the editor but freely deletable (with no confirmation) from the tree. Anything that
 * needs to know "may the user change this?" asks {@link #of} — do not re-derive it from a path.
 *
 * <p>This is a <b>file</b>-level verdict. "This whole file is the user's, but one method in it is load-bearing"
 * is {@link MethodLock}'s job — see {@code GoHome.run}, which the SDK's {@code Bot.supervise} binds as a
 * {@code Runnable} even though the file around it is {@link #EDITABLE}.
 */
public enum FileRole {

    /** Ordinary user code. Fully editable, edits persist. */
    EDITABLE,

    /**
     * Scaffolding the Studio generates and owns: the game-bot entry point, {@code ActivityRegistry},
     * {@code Activities}, and the game loop.
     *
     * <p>Like {@link #LIBRARY}, these are <b>fully locked</b>: no menus, no drop targets, no edits. This used
     * to be laxer — blocks stayed interactive and only the compile-time flush refused to persist the changes —
     * but that is a worse contract than a lock, not a gentler one: the edit appears to work, survives until the
     * next reload, and then vanishes with no warning. If it can't be saved, it must not be offered.
     * Deleting them from the explorer is still allowed (Project ▸ Recover Project Files regenerates them).
     */
    GENERATED,

    /** Bundled library source under {@code com/botmaker/library}. Fully locked: no interaction at all. */
    LIBRARY;

    /** True when the user's edits to this role are never written to disk. */
    public boolean isReadOnly() {
        return this != EDITABLE;
    }

    /** True when blocks should refuse interaction entirely (menus suppressed). */
    public boolean suppressesInteraction() {
        return this != EDITABLE;
    }

    /** True when the compile-time flush must not write this file's in-memory content over the real one. */
    public boolean blocksPersistence() {
        return this != EDITABLE;
    }

    /** A short suffix for the editor status line / explorer label, or {@code null} for ordinary files. */
    public String badge() {
        return switch (this) {
            case EDITABLE -> null;
            case GENERATED -> "Generated - Read Only";
            case LIBRARY -> "Library - Read Only";
        };
    }

    /** The file names the game-bot template generates and owns, alongside the entry point. */
    private static final String GAME_LOOP_FILE = "GameLoop.java";

    /**
     * Classifies {@code file} for {@code config}, given the project's {@code template}. Never null; unknown
     * files are {@link #EDITABLE}, so a file the Studio doesn't recognise always belongs to the user.
     *
     * <p>{@code template} decides the entry point: only the {@link ProjectTemplate#GAME_BOT} one is scaffolding
     * (it is just {@code Bot.supervise(GameLoop::run, GoHome::run, Startup::run)}). An
     * {@link ProjectTemplate#EMPTY} project's {@code main} is the user's only file and must stay editable — a
     * null {@code template} (legacy project, or a caller that doesn't know) is therefore treated as EMPTY.
     */
    public static FileRole of(ProjectConfig config, ProjectTemplate template, Path file) {
        if (config == null || file == null) return EDITABLE;

        String normalized = file.toString().replace("\\", "/");
        if (normalized.contains("com/botmaker/library")) return LIBRARY;

        Path abs = file.toAbsolutePath();
        if (template == ProjectTemplate.GAME_BOT && sameFile(abs, config.mainSourceFile())) return GENERATED;
        if (sameFile(abs, config.activitiesSourceFile())
                || sameFile(abs, config.activityRegistrySourceFile())
                || sameFile(abs, gameLoopSourceFile(config))) {
            return GENERATED;
        }
        return EDITABLE;
    }

    /** {@code GameLoop.java}, which sits beside the entry point in the main package. */
    public static Path gameLoopSourceFile(ProjectConfig config) {
        Path mainDir = config.mainSourceFile().getParent();
        return mainDir == null ? null : mainDir.resolve(GAME_LOOP_FILE);
    }

    private static boolean sameFile(Path abs, Path other) {
        return other != null && abs.equals(other.toAbsolutePath());
    }
}
