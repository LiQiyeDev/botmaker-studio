package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.types.ResolvedType;
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
        Button selectBtn = new Button("Select Expression...");
        selectBtn.getStyleClass().add("null-block-button");
        selectBtn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.5); -fx-text-fill: #555; -fx-border-color: #ccc; -fx-border-style: dashed; -fx-cursor: hand;");

        selectBtn.setOnAction(e -> {
            ResolvedType expected = com.botmaker.suggestions.ProjectAnalyzer.inferExpectedType(this.astNode);
            showExpressionMenuAndReplace(selectBtn, context, expected, (Expression) this.astNode);
        });

        return selectBtn;
    }
}