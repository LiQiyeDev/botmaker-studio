package com.botmaker.studio.core.component;

import com.botmaker.studio.project.LockResolver;
import org.eclipse.jdt.core.dom.ASTNode;

/**
 * The one answer to "should this component be drawn, and may it be touched?" — the visibility counterpart to
 * {@link LockResolver}, and deliberately shaped like it.
 *
 * <p><b>Division of labour.</b> {@link LockResolver} stays the sole authority on <em>editability</em>: whether
 * an edit at a given AST node is allowed, and what to tell the user when it isn't. This class adds exactly one
 * thing on top — the <em>visible</em> axis — by combining that verdict with the current {@link Audience}. It
 * never re-derives a lock from a path, a file name or a method name; if you find yourself asking
 * {@code FileRole} or {@code MethodLock} here, that is the bug {@code LockResolver} was written to end, and it
 * applies to this class too.
 *
 * <p>Pure and cheap: no state, no I/O, no JavaFX. The three-line verdict table below is the whole contract, and
 * it is unit-tested headlessly.
 *
 * <pre>
 * EDITOR_ONLY component + USER audience -> HIDDEN
 * locked + WhenLocked.HIDE              -> HIDDEN
 * locked                                -> SHOWN_LOCKED
 * otherwise                             -> SHOWN_EDITABLE
 * </pre>
 */
public final class ComponentResolver {

    private ComponentResolver() {}

    /** What the render path should do with one component. */
    public enum Verdict {
        /** Draw it, fully interactive. */
        SHOWN_EDITABLE,
        /** Draw it, marked with the {@code :read-only} pseudo-class — no new mechanism, the existing one. */
        SHOWN_LOCKED,
        /** Do not draw it, and do not build its node. */
        HIDDEN;

        public boolean isVisible() {
            return this != HIDDEN;
        }

        public boolean isReadOnly() {
            return this == SHOWN_LOCKED;
        }
    }

    /**
     * The verdict for {@code component}, given who is looking and whether the code behind it is locked.
     *
     * <p>Audience is checked first: a component the user was never meant to see stays hidden whether or not it
     * happens to be editable, so an unlocked generated member cannot leak through.
     */
    public static Verdict resolve(BlockComponent component, Audience audience, boolean locked) {
        if (component == null) return Verdict.HIDDEN;
        Audience who = audience == null ? Audience.EDITOR : audience;

        if (component.visibility() == BlockComponent.Visibility.EDITOR_ONLY && !who.seesScaffolding()) {
            return Verdict.HIDDEN;
        }
        if (!locked) return Verdict.SHOWN_EDITABLE;
        return component.whenLocked() == BlockComponent.WhenLocked.HIDE ? Verdict.HIDDEN : Verdict.SHOWN_LOCKED;
    }

    /**
     * The verdict for {@code component}, taking the lock straight from {@code resolver} rather than from a
     * boolean the caller worked out — the overload render paths should prefer, so the two verdicts cannot
     * drift apart.
     *
     * <p>A null {@code resolver} means "we don't know what project this is" (tests, and editor paths with no
     * project open) and is permissive, matching {@code LockResolver}'s own convention.
     */
    public static Verdict resolve(BlockComponent component, Audience audience, LockResolver resolver,
                                  ASTNode node, LockResolver.EditKind kind) {
        return resolve(component, audience, isLocked(resolver, node, kind));
    }

    /** Whether an edit of {@code kind} at {@code node} is refused. Permissive when there is no resolver. */
    public static boolean isLocked(LockResolver resolver, ASTNode node, LockResolver.EditKind kind) {
        if (resolver == null || node == null) return false;
        return !resolver.permits(node, kind);
    }
}
