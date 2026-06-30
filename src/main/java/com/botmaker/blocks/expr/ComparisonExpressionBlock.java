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
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;

public class ComparisonExpressionBlock extends AbstractExpressionBlock {

    private ExpressionBlock leftOperand;
    private ExpressionBlock rightOperand;
    private String operator;
    private final ITypeBinding returnType;

    // Operator display names
    private static final String[] OPERATOR_NAMES = {
            "less than", "less than or equal", "greater than", "greater than or equal",
            "equal to", "not equal to", "AND (&&)", "OR (||)"
    };

    // Java operators
    private static final String[] OPERATOR_SYMBOLS = {
            "<", "<=", ">", ">=", "==", "!=", "&&", "||"
    };

    public ComparisonExpressionBlock(String id, InfixExpression astNode) {
        super(id, astNode);
        this.operator = astNode.getOperator().toString();
        this.returnType = astNode.resolveTypeBinding();
    }

    public void setLeftOperand(ExpressionBlock leftOperand) { this.leftOperand = leftOperand; }
    public void setRightOperand(ExpressionBlock rightOperand) { this.rightOperand = rightOperand; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // 1. Determine Input Types based on Operator
        ResolvedType operandType;
        if ("&&".equals(operator) || "||".equals(operator)) {
            operandType = ResolvedType.primitive("boolean");
        } else if ("==".equals(operator) || "!=".equals(operator)) {
            operandType = ResolvedType.UNKNOWN; // Equality checks anything
        } else {
            operandType = ResolvedType.primitive("int");     // Comparison (<, >) requires numbers (defaulting to INT context)
        }

        final ResolvedType targetType = operandType;

        // 2. Build Sentence with explicit Change Buttons
        var sentence = BlockLayout.sentence();

        // Left Operand
        sentence.addExpressionSlot(leftOperand, context, targetType);
        sentence.addNode(BlockUIComponents.createChangeButton(e ->
                showExpressionMenuAndReplace((Button)e.getSource(), context, targetType,
                        leftOperand != null ? (Expression) leftOperand.getAstNode() : null)
        ));

        // Operator
        sentence.addOperatorSelector(OPERATOR_NAMES, OPERATOR_SYMBOLS, operator, newOp -> {
            this.operator = newOp;
            if (this.astNode instanceof InfixExpression) {
                context.getCodeEditor().updateBinaryOperator((InfixExpression) this.astNode, newOp);
            }
        });

        // Right Operand
        sentence.addExpressionSlot(rightOperand, context, targetType);
        sentence.addNode(BlockUIComponents.createChangeButton(e ->
                showExpressionMenuAndReplace((Button)e.getSource(), context, targetType,
                        rightOperand != null ? (Expression) rightOperand.getAstNode() : null)
        ));

        Node root = sentence.build();

        // 3. Styling
        if ("&&".equals(operator) || "||".equals(operator)) {
            root.getStyleClass().add("logic-expression-block");
        } else {
            root.getStyleClass().add("comparison-expression-block");
        }

        return root;
    }
}