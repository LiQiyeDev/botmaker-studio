package com.botmaker.studio.parser;

/**
 * What happens to the <em>uses</em> of a variable that is being deleted — the question a delete cross has no
 * room to ask and, until now, never asked at all.
 *
 * <p>Deleting {@code int attempts = 0} that three lines later read {@code attempts} left a file that does not
 * compile, with no warning and no way back but undo. There are only two answers worth offering: put a value
 * where the variable was, or point the uses at a variable that is still there. Both are one choice applied to
 * every use — the maintainer's call, deliberately, over a per-use screen nobody wants to fill in three times.
 *
 * <p>A sealed pair rather than a boolean plus a nullable string: {@code delete(decl, true, null)} reads as
 * nothing at the call site, and the illegal fourth combination ("point them somewhere" with no name) cannot be
 * written at all here.
 */
public sealed interface UseFix {

    /**
     * Replace every use with the declared type's default — the same node
     * {@link com.botmaker.studio.parser.factories.InitializerFactory#createDefaultInitializer} writes for a
     * retype or a drag-out backfill, so the codebase has one answer to "what does a {@code T} start as".
     */
    record Default() implements UseFix {}

    /** Point every use at {@code variableName}, a variable of the same type still in scope. */
    record Rename(String variableName) implements UseFix {}

    /** The {@link Default} instance, since it carries nothing. */
    UseFix DEFAULT = new Default();
}
