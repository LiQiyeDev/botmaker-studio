package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.BreakStatement;

public class BreakBlock extends AbstractStatementBlock {

    public BreakBlock(String id, BreakStatement astNode) {
        super(id, astNode);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withKeyword("break")
                .withDeleteButton(deleteAction(context))
                .build();
    }
}