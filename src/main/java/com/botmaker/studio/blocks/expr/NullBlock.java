package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NullLiteral;

public class NullBlock extends AbstractExpressionBlock {

    public NullBlock(String id, NullLiteral astNode) {
        super(id, astNode);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // In generated code a null is a real value, not a hole: FlowDriver's `String node = null;` and its
        // `return null;` are the flow's own "nowhere to go". Rendering those as a red "fill me in" button
        // invited an edit the file would then refuse — so a locked null reads as the literal it is.
        if (isReadOnly()) {
            Label literal = new Label("null");
            literal.getStyleClass().add("null-block-literal");
            return literal;
        }

        // An empty required slot: shown red (dashed) so it's obvious — before any compile — that the argument
        // still needs a value. Filling it replaces this NullLiteral with a real expression.
        Button selectBtn = new Button("Select Expression...");
        selectBtn.getStyleClass().add("null-block-button");

        selectBtn.setOnAction(e -> {
            ResolvedType expected = com.botmaker.studio.suggestions.ProjectAnalyzer.inferExpectedType(this.astNode);
            showExpressionMenuAndReplace(selectBtn, context, expected, (Expression) this.astNode);
        });

        return selectBtn;
    }
}
