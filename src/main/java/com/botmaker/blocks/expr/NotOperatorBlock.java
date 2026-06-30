package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.PrefixExpression;

public class NotOperatorBlock extends AbstractExpressionBlock {

    private ExpressionBlock operand;

    public NotOperatorBlock(String id, PrefixExpression astNode) {
        super(id, astNode);
    }

    public void setOperand(ExpressionBlock operand) {
        this.operand = operand;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // "!" [Expression] [Change]
        var sentence = BlockLayout.sentence()
                .addLabel("!")
                .addExpressionSlot(operand, context, ResolvedType.primitive("boolean"))
                .addNode(BlockUIComponents.createChangeButton(e ->
                        showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.primitive("boolean"),
                                operand != null ? (Expression) operand.getAstNode() : null)
                ));

        Node root = sentence.build();
        root.getStyleClass().add("logic-expression-block");
        return root;
    }
}