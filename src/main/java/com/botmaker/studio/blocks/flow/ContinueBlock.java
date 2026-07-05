package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
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