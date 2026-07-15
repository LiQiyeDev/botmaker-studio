package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.nio.file.Path;

/**
 * How much of one method the user may change — the method-level counterpart to {@link FileRole}.
 *
 * <p><b>Why a second concept.</b> {@link FileRole} answers "whose file is this?", which can't express the case
 * that actually bites: {@code GoHome.java} is the user's file — its body is the whole point — but the SDK's
 * {@code Bot.supervise(GameLoop::run, GoHome::run, Startup::run)} binds {@code GoHome::run} as a
 * {@link Runnable}, so the moment the method is renamed, given a parameter, or made non-static, the generated
 * entry point stops compiling. The signature is scaffolding; the body is not. Likewise an activity's
 * {@code isEnabled()} is wired to its {@code Activities} flag and is not a thing to hand-edit, while the
 * {@code run()} beside it is exactly what the user came to write.
 *
 * <p>Rules live here and nowhere else — like {@link FileRole}, callers ask {@link #of} rather than re-deriving
 * from names.
 */
public enum MethodLock {

    /** Ordinary user method: rename it, retype it, delete it, fill it in. */
    NONE,

    /**
     * The signature is fixed but the body is the user's: {@code run()} in {@code GoHome} / {@code Startup} /
     * {@code GameLoop}, each bound as a {@code Runnable} by {@code Bot.supervise}.
     */
    SIGNATURE,

    /** The whole method is generated wiring: an activity's {@code isEnabled()}. */
    FULL;

    /** True when the method's name/params/return type/existence must not change. */
    public boolean locksSignature() {
        return this != NONE;
    }

    /** True when the method's body must not change either. */
    public boolean locksBody() {
        return this == FULL;
    }

    /**
     * A short badge for the method header, telling the user at a glance whether this method is theirs. Never
     * null — an ordinary method in a file the Studio scaffolded still gets a nudge toward the right place.
     */
    public String badge() {
        return switch (this) {
            case NONE -> null;
            case SIGNATURE -> "Name and parameters required by BotMaker";
            case FULL -> "Generated - Read Only";
        };
    }

    /** The {@code Bot.supervise} recovery hooks, whose {@code run()} is bound as a {@code Runnable}. */
    private static final java.util.Set<String> SUPERVISED_HOOKS =
            java.util.Set.of("GoHome.java", "Startup.java", "GameLoop.java");

    /**
     * Classifies {@code method} of {@code file}. Never null; anything unrecognised is {@link #NONE}, so a method
     * the Studio doesn't know about always belongs to the user.
     */
    public static MethodLock of(ProjectConfig config, ProjectTemplate template, Path file, MethodDeclaration method) {
        if (config == null || file == null || method == null || method.getName() == null) return NONE;
        // Only the game-bot scaffold has a supervise contract to protect.
        if (template != ProjectTemplate.GAME_BOT) return NONE;

        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String methodName = method.getName().getIdentifier();

        if (isActivityStub(config, file) && "isEnabled".equals(methodName)) return FULL;
        if (SUPERVISED_HOOKS.contains(fileName) && "run".equals(methodName)) return SIGNATURE;
        return NONE;
    }

    /**
     * True when {@code file} is one of the per-activity subclasses under the project's {@code activities}
     * package — the files {@code ActivityService.ensureStubs} creates.
     */
    private static boolean isActivityStub(ProjectConfig config, Path file) {
        Path dir = file.toAbsolutePath().getParent();
        return dir != null && dir.equals(config.activitiesPackageDir().toAbsolutePath());
    }

    /**
     * True when {@code method} is the one the user is meant to fill in: an activity's {@code run()}. Drives the
     * "your code goes here" affordance, so the locked {@code isEnabled()} beside it isn't mistaken for the
     * place to work.
     */
    public static boolean isUsersEntryPoint(ProjectConfig config, ProjectTemplate template, Path file,
                                            MethodDeclaration method) {
        if (config == null || file == null || method == null || method.getName() == null) return false;
        if (template != ProjectTemplate.GAME_BOT) return false;
        return isActivityStub(config, file) && "run".equals(method.getName().getIdentifier());
    }
}
