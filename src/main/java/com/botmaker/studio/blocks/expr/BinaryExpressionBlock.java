package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.Expression;

public class BinaryExpressionBlock extends AbstractExpressionBlock {

    private ExpressionBlock leftOperand;
    private ExpressionBlock rightOperand;
    private String operator;
    private final ITypeBinding returnType;

    private static final String[] MATH_OPERATOR_NAMES = { "plus", "minus", "times", "divided by", "modulo" };
    private static final String[] MATH_OPERATOR_SYMBOLS = { "+", "-", "*", "/", "%" };

    public BinaryExpressionBlock(String id, InfixExpression astNode) {
        super(id, astNode);
        this.operator = astNode.getOperator().toString();
        this.returnType = astNode.resolveTypeBinding();
    }

    public void setLeftOperand(ExpressionBlock leftOperand) { this.leftOperand = leftOperand; }
    public void setRightOperand(ExpressionBlock rightOperand) { this.rightOperand = rightOperand; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox container = new HBox(5);
        container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox expressionBox = new HBox(5);
        expressionBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Left operand + Change Button (null when read-only — nothing to change a locked operand by)
        if (leftOperand != null) {
            expressionBox.getChildren().add(leftOperand.getUINode(context));
            javafx.scene.control.Button changeLeft = createChangeButton(e ->
                    showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.primitive("int"),
                            (Expression) leftOperand.getAstNode())
            );
            if (changeLeft != null) {
                changeLeft.getStyleClass().add("small-change-button");
                expressionBox.getChildren().add(changeLeft);
            }
        }

        // Operator Selector (a plain label when read-only: no live control on a locked block)
        if (isMathOperator(operator) && !isReadOnly()) {
            javafx.scene.control.ComboBox<String> selector = createOperatorSelector(
                    MATH_OPERATOR_NAMES,
                    MATH_OPERATOR_SYMBOLS,
                    operator,
                    newOp -> {
                        this.operator = newOp;
                        context.getCodeEditor().updateBinaryOperator((InfixExpression) this.astNode, newOp);
                    }
            );
            expressionBox.getChildren().add(selector);
        } else {
            expressionBox.getChildren().add(createOperatorLabel(operator));
        }

        // Right operand + Change Button
        if (rightOperand != null) {
            expressionBox.getChildren().add(rightOperand.getUINode(context));
            javafx.scene.control.Button changeRight = createChangeButton(e ->
                    showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.primitive("int"),
                            (Expression) rightOperand.getAstNode())
            );
            if (changeRight != null) {
                changeRight.getStyleClass().add("small-change-button");
                expressionBox.getChildren().add(changeRight);
            }
        }

        container.getChildren().add(expressionBox);

        // Type indicator
        String typeName = (returnType != null) ? returnType.getName() : "unknown";
        javafx.scene.control.Label typeLabel = new javafx.scene.control.Label("→ " + typeName);
        typeLabel.getStyleClass().add("type-indicator-label");
        container.getChildren().add(typeLabel);

        return container;
    }

    private boolean isMathOperator(String op) {
        for (String mathOp : MATH_OPERATOR_SYMBOLS) {
            if (mathOp.equals(op)) return true;
        }
        return false;
    }
}