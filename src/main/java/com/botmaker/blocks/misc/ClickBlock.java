package com.botmaker.blocks.misc;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.types.ResolvedType;
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
    protected Node createUINode(CodeEditorService context) {
        // Target the API Point class
        ResolvedType pointType = ResolvedType.named("com.botmaker.sdk.api.Point");

        var sentence = BlockLayout.sentence()
                .addLabel("Mouse Click")
                .addExpressionSlot(pointExpression, context, pointType)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build();
    }
}