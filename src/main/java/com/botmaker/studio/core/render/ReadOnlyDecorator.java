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

    static final PseudoClass READ_ONLY = PseudoClass.getPseudoClass("read-only");

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (block.isReadOnly()) {
            node.pseudoClassStateChanged(READ_ONLY, true);
        }
    }
}
