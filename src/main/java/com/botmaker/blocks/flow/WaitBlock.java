package com.botmaker.blocks.flow;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.Statement;

public class WaitBlock extends AbstractStatementBlock {

    private ExpressionBlock duration;

    public WaitBlock(String id, Statement astNode) {
        super(id, astNode);
    }

    public void setDuration(ExpressionBlock duration) {
        this.duration = duration;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        var sentence = BlockLayout.sentence()
                .addKeyword("Wait")
                .addExpressionSlot(duration, context, ResolvedType.primitive("int"))
                .addKeyword("ms")
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build();
    }

    @Override
    public String getDetails() {
        return "Wait: " + (duration != null ? duration.getDetails() : "...");
    }
}