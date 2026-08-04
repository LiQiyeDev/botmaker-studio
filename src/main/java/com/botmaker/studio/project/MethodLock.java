package com.botmaker.studio.project;

import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.nio.file.Path;

/**
 * How much of one method the user may change — the method-level counterpart to {@link FileRole}.
 *
 * <p><b>Why a second concept.</b> {@link FileRole} answers "whose file is this?", which can't express the case
 * that actually bites: {@code GoHome.java} is the user's file — its body is the whole point — but it is an
 * {@code Activity} subclass, so {@code run()} is an {@code @Override} whose signature (name, no params, the
 * {@code Outcome} it returns) is BotMaker's, and the generated entry point / driver call it through
 * {@code GoHome.INSTANCE.execute()}. Rename it, give it a parameter, or change its return type and that wiring
 * stops compiling. The signature is scaffolding; the body is not. Likewise an activity's {@code isEnabled()}
 * is wired to its {@code Activities} flag (GoHome's simply returns {@code true}) and is not a thing to
 * hand-edit, while the {@code run()} beside it is exactly what the user came to write.
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
     * The signature is fixed but <b>the body is unconditionally the user's</b>: an activity's {@code run()} and
     * {@code GoHome}'s {@code run()} — both {@code @Override}s of {@code Activity.run} shipped as a TODO stub for
     * the user to fill in. Unlike {@link #NONE} this does not defer — it grants body edits however locked the
     * surrounding file is.
     */
    SIGNATURE,

    /**
     * The whole method is generated wiring: an activity's {@code isEnabled()}, and {@code GoHome.isEnabled()}.
     * An edited body is damage for {@code ProjectRepair} to restore, not user code to preserve.
     *
     * <p>{@code GameLoop.run} and {@code Startup.run} used to be the other two, and the argument for both was
     * the same: the generator ships them complete, and the user's workspace is the activities. Which was the
     * tell that neither belonged in the project at all — a file whose every line is BotMaker's, in every
     * project, is the SDK's. Both are gone; the reasoning survives here because it is what identifies the next
     * one.
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

    /**
     * The hooks the generated entry point binds as a method reference, so their signature is BotMaker's while
     * their body is the user's: {@code GoHome} (the {@code Bot.start} recovery) and {@code Popups} (the
     * {@code PopupGuard.install} check). {@code GameLoop.java} and {@code Startup.java} were retired — the
     * entry point binds {@code FlowDriver::run} directly, and the SDK's 2-arg {@code Bot.start} supplies the
     * launch step.
     */
    private static final java.util.Set<String> SUPERVISED_HOOKS =
            java.util.Set.of("GoHome.java", "Popups.java");

    /**
     * {@link #SUPERVISED_HOOKS}, for callers that need to <em>offer</em> the hooks rather than classify one —
     * the overlay's activity picker lists them beside the real activities. They live directly beside the
     * project's main source file, not under {@code activities/}, and they have no {@code ActivityDefinition},
     * which is why a picker built from the activity flow alone can never reach them.
     */
    public static java.util.Set<String> superviseHookFiles() {
        return SUPERVISED_HOOKS;
    }

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
            // @Override public Outcome run() — renaming it, giving it a parameter or changing its return type
            // de-wires the override. The return type is BotMaker's too: the flow routes on what it reports.
            if ("run".equals(methodName)) return SIGNATURE;
            return NONE;
        }
        if (isSuperviseHook(config, file)) {
            // GoHome is an Activity subclass, so it is shaped exactly like an activity stub: its run() is an
            // @Override the user fills in (BotMaker owns the signature — Activity.run's contract and the outcome
            // it routes on), and its isEnabled() is generated wiring the user shouldn't hand-edit.
            if ("run".equals(methodName)) return SIGNATURE;
            if ("isEnabled".equals(methodName)) return FULL;
            return NONE;
        }
        return NONE;
    }

    /**
     * True when {@code file} is one of the per-activity subclasses under the project's {@code activities}
     * package — the files {@code ActivityService.ensureStubs} creates. Public because
     * {@link GeneratedMembers} asks the same question about the generated members <em>inside</em> such a file;
     * the answer must not be worked out twice.
     */
    public static boolean isActivityStub(ProjectConfig config, Path file) {
        return isChildOf(file, config.activitiesPackageDir());
    }

    /**
     * True when {@code file} is a {@code Bot.start} hook <em>of this project</em> — i.e. it sits beside the
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
