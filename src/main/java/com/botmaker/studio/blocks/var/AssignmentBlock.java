package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.*;

public class AssignmentBlock extends AbstractStatementBlock {

    private ExpressionBlock leftHandSide;
    private ExpressionBlock rightHandSide;
    private String operator;

    private static final String[] OPERATOR_NAMES = {
            "set to", "add", "subtract", "multiply by", "divide by", "increment", "decrement"
    };

    private static final String[] OPERATOR_SYMBOLS = {
            "=", "+=", "-=", "*=", "/=", "++", "--"
    };

    public AssignmentBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
        initializeOperator(astNode);
    }

    private void initializeOperator(ExpressionStatement astNode) {
        if (astNode.getExpression() instanceof Assignment) {
            this.operator = ((Assignment) astNode.getExpression()).getOperator().toString();
        } else if (astNode.getExpression() instanceof PostfixExpression) {
            this.operator = ((PostfixExpression) astNode.getExpression()).getOperator().toString();
        } else if (astNode.getExpression() instanceof PrefixExpression) {
            this.operator = ((PrefixExpression) astNode.getExpression()).getOperator().toString();
        } else {
            this.operator = "=";
        }
    }

    public void setLeftHandSide(ExpressionBlock leftHandSide) { this.leftHandSide = leftHandSide; }
    public void setRightHandSide(ExpressionBlock rightHandSide) { this.rightHandSide = rightHandSide; }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        var sentenceBuilder = BlockLayout.sentence()
                .addNode(leftHandSide != null ? leftHandSide.getUINode(context) : createExpressionDropZone(context))
                .addOperatorSelector(
                        OPERATOR_NAMES,
                        OPERATOR_SYMBOLS,
                        operator,
                        newOperator -> {
                            this.operator = newOperator;
                            if (this.astNode instanceof ExpressionStatement) {
                                Expression expr = ((ExpressionStatement) this.astNode).getExpression();
                                context.getCodeEditor().updateAssignmentOperator(expr, newOperator);
                            }
                        }
                );

        // Right hand side (only for non-increment/decrement)
        if (!operator.equals("++") && !operator.equals("--")) {
            sentenceBuilder
                    .addNode(rightHandSide != null ? rightHandSide.getUINode(context) : createExpressionDropZone(context))
                    .addNode(createAddButton(e -> showExpressionMenu((javafx.scene.control.Button) e.getSource(), context)));
        }

        return BlockLayout.header()
                .withCustomNode(sentenceBuilder.build())
                .withDeleteButton(deleteAction(context))
                .build();
    }

    private void showExpressionMenu(javafx.scene.control.Button button, CodeEditorService context) {
        // UPDATED: Use ResolvedType instead of string types
        ResolvedType targetType = ResolvedType.UNKNOWN;

        if (leftHandSide != null && leftHandSide.getAstNode() != null) {
            Expression lhsExpr = (org.eclipse.jdt.core.dom.Expression) leftHandSide.getAstNode();
            org.eclipse.jdt.core.dom.ITypeBinding binding = lhsExpr.resolveTypeBinding();
            if (binding != null) {
                targetType = ResolvedType.of(binding);
            }
        }

        org.eclipse.jdt.core.dom.Expression toReplace = null;
        if (rightHandSide != null) {
            toReplace = (org.eclipse.jdt.core.dom.Expression) rightHandSide.getAstNode();
        }

        showExpressionMenuAndReplace(button, context, targetType, toReplace);
    }
}