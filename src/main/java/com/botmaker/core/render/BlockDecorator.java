package com.botmaker.core.render;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.services.CodeEditorService;
import javafx.scene.Node;

/**
 * A one-time decoration applied to a block's freshly created UI node — the cross-cutting concerns
 * (gutter, breakpoint circle, context menu, tooltip, read-only state) that used to be hand-wired inside
 * {@code AbstractCodeBlock.getUINode}. Decorators run in order right after {@code createUINode}.
 */
@FunctionalInterface
public interface BlockDecorator {
    void decorate(Node node, AbstractCodeBlock block, CodeEditorService context);
}
