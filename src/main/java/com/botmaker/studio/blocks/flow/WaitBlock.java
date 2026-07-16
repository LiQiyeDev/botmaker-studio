package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.types.ResolvedType;
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
    protected BlockCategory category() {
        return BlockCategory.UTILITY;
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
                .withDeleteButton(deleteAction(context))
                .build();
    }

    @Override
    public String getDetails() {
        return "Wait: " + (duration != null ? duration.getDetails() : "...");
    }
}