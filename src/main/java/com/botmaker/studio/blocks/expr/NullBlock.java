package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NullLiteral;

public class NullBlock extends AbstractExpressionBlock {

    public NullBlock(String id, NullLiteral astNode) {
        super(id, astNode);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // An empty required slot: shown red (dashed) so it's obvious — before any compile — that the argument
        // still needs a value. Filling it replaces this NullLiteral with a real expression.
        Button selectBtn = new Button("Select Expression...");
        selectBtn.getStyleClass().add("null-block-button");
        selectBtn.setStyle("-fx-background-color: rgba(231, 76, 60, 0.10); -fx-text-fill: #c0392b; "
                + "-fx-border-color: #e74c3c; -fx-border-style: dashed; -fx-cursor: hand;");

        selectBtn.setOnAction(e -> {
            ResolvedType expected = com.botmaker.studio.suggestions.ProjectAnalyzer.inferExpectedType(this.astNode);
            showExpressionMenuAndReplace(selectBtn, context, expected, (Expression) this.astNode);
        });

        return selectBtn;
    }
}