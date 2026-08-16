package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.Audience;
import com.botmaker.studio.core.component.ComponentSpec;

/**
 * Main entry point for building block UIs.
 * Provides fluent API for common block layouts.
 */
public class BlockLayout {

    // Factory methods for the layouts blocks actually use. A header() can continue into a body via
    // HeaderLayoutBuilder.andBody().
    public static HeaderLayoutBuilder header() {
        return new HeaderLayoutBuilder();
    }

    public static SentenceLayoutBuilder sentence() {
        return new SentenceLayoutBuilder();
    }

    /**
     * Renders a declared {@link ComponentSpec} instead of a hand-assembled sentence, filtering each component
     * through {@link com.botmaker.studio.core.component.ComponentResolver}.
     *
     * <p>This is the migration target for {@code createUINode}, not a replacement for the builders above: a
     * block that has not declared a spec keeps using {@link #sentence()} / {@link #header()} and renders
     * exactly as before. Both coexist for as long as the migration takes.
     *
     * @param locked whether the code this block edits is locked — take it from
     *               {@code ComponentResolver.isLocked(LockResolver, ASTNode, EditKind)} rather than
     *               re-deriving it, so the visibility verdict cannot drift from the edit verdict.
     */
    public static ComponentLayoutBuilder components(ComponentSpec spec, Audience audience, boolean locked) {
        return new ComponentLayoutBuilder(spec, audience, locked);
    }
}
