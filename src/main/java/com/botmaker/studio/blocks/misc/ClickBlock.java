package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.Statement;

public class ClickBlock extends AbstractStatementBlock {

    private ExpressionBlock pointExpression;

    public ClickBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
    }

    public void setPointExpression(ExpressionBlock pointExpression) {
        this.pointExpression = pointExpression;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.GAME;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // Target the API Point class
        ResolvedType pointType = ResolvedType.named("com.botmaker.sdk.api.Point");

        var sentence = BlockLayout.sentence()
                .addLabel("Mouse Click")
                .addExpressionSlot(pointExpression, context, pointType)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(deleteAction(context))
                .build();
    }
}