package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.css.PseudoClass;
import javafx.scene.Node;

/**
 * Marks read-only blocks with the {@code :read-only} pseudo-class (dimmed/non-interactive styling lives
 * in {@code blocks.css}).
 */
public final class ReadOnlyDecorator implements BlockDecorator {

    /**
     * The single source of the {@code :read-only} pseudo-class name. Public because the component schema
     * ({@code core/component}) marks an individual locked component with the same pseudo-class rather than
     * inventing a second one — a block can be editable while one of its fields is not.
     */
    public static final PseudoClass READ_ONLY = PseudoClass.getPseudoClass("read-only");

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (block.isReadOnly()) {
            node.pseudoClassStateChanged(READ_ONLY, true);
        }
    }
}
