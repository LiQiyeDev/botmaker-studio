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
 *
 * <p><b>This enum outranks {@link FileRole} at method granularity, and may unlock as well as lock.</b> A
 * {@link #SIGNATURE} verdict hands the body back to the user even inside a locked file, and a {@link #FULL}
 * verdict locks a method inside a file the user otherwise owns (an activity's {@code isEnabled()}).
 * {@code project/LockResolver} is where the two verdicts are combined; it is the only thing that should call
 * this method, and nothing else should re-derive the rule.
 */
public enum MethodLock {

    /**
     * No opinion — <b>defer to {@link FileRole}</b>. An ordinary method in an editable file is the user's to
     * rename, retype, delete and fill in; the same verdict in a generated or library file leaves it locked,
     * because the file says so.
     */
    NONE,

    /**
     * The signature is fixed but <b>the body is unconditionally the user's</b>: {@code run()} in {@code GoHome}
     * (bound as a {@code Runnable} by {@code Bot.supervise}, shipped as a TODO stub for the user to fill in), and
     * an activity's {@code run()} (an {@code @Override} of {@code Activity.run}). Unlike {@link #NONE} this does
     * not defer — it grants body edits however locked the surrounding file is.
     */
    SIGNATURE,

    /**
     * The whole method is generated wiring: an activity's {@code isEnabled()}, {@code GameLoop.run} (the
     * generated activity-dispatch loop), and {@code Startup.run} (generated {@code StartMode}-driven launch of
     * the project's configured launch target). {@code GameLoop.run} was briefly {@link #SIGNATURE} on
     * the theory that the game loop was the user's to fill in, but the generator ships it complete and the
     * user's workspace is the activities. {@code Startup.run} likewise ships complete — the game/target is
     * chosen in the Studio, not hand-coded — so an edited body is damage for {@code ProjectRepair} to restore,
     * not user code to preserve.
     */
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
     * the Studio doesn't know about is left to {@link FileRole} to judge.
     */
    public static MethodLock of(ProjectConfig config, ProjectTemplate template, Path file, MethodDeclaration method) {
        if (config == null || file == null || method == null || method.getName() == null) return NONE;
        // Only the game-bot scaffold has a supervise contract to protect.
        if (template != ProjectTemplate.GAME_BOT) return NONE;

        String methodName = method.getName().getIdentifier();

        if (isActivityStub(config, file)) {
            if ("isEnabled".equals(methodName)) return FULL;
            // @Override public void run() — renaming it or giving it a parameter de-wires the override.
            if ("run".equals(methodName)) return SIGNATURE;
            return NONE;
        }
        if (isSuperviseHook(config, file) && "run".equals(methodName)) {
            // GameLoop.run (the generated dispatch loop) and Startup.run (generated StartMode-driven launch of
            // the project's configured launch target) are both wholly BotMaker's. Only GoHome ships as a TODO
            // stub whose body is the user's to write — its signature is bound by Bot.supervise, the body is not.
            String fileName = file.getFileName().toString();
            return ("GameLoop.java".equals(fileName) || "Startup.java".equals(fileName)) ? FULL : SIGNATURE;
        }
        return NONE;
    }

    /**
     * True when {@code file} is one of the per-activity subclasses under the project's {@code activities}
     * package — the files {@code ActivityService.ensureStubs} creates.
     */
    private static boolean isActivityStub(ProjectConfig config, Path file) {
        return isChildOf(file, config.activitiesPackageDir());
    }

    /**
     * True when {@code file} is a {@code Bot.supervise} hook <em>of this project</em> — i.e. it sits beside the
     * entry point in the main package.
     *
     * <p>Matching on the bare file name is not enough. {@link #SIGNATURE} <em>grants</em> body edits, so a
     * {@code GoHome.java} vendored under {@code com/botmaker/library} (or any the user parks in a subpackage)
     * would otherwise have its {@code run()} body unlocked inside a file nothing should be able to touch.
     */
    private static boolean isSuperviseHook(ProjectConfig config, Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        return SUPERVISED_HOOKS.contains(fileName) && isChildOf(file, config.mainSourceFile().getParent());
    }

    private static boolean isChildOf(Path file, Path dir) {
        Path parent = file.toAbsolutePath().getParent();
        return parent != null && dir != null && parent.equals(dir.toAbsolutePath());
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
