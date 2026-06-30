package com.botmaker.blocks.flow;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ContinueStatement;

public class ContinueBlock extends AbstractStatementBlock {

    public ContinueBlock(String id, ContinueStatement astNode) {
        super(id, astNode);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withKeyword("continue")
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((org.eclipse.jdt.core.dom.Statement) this.astNode))
                .build();
    }
}