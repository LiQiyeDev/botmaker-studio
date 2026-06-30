package com.botmaker.blocks.flow;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.core.StatementBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.types.ResolvedType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;

public class IfBlock extends AbstractStatementBlock {

    private ExpressionBlock condition;
    private BodyBlock thenBody;
    private StatementBlock elseStatement;

    // Flag to alter rendering if this block is part of an 'else if' chain
    private boolean isElseIf = false;

    public IfBlock(String id, IfStatement astNode) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setThenBody(BodyBlock thenBody) { this.thenBody = thenBody; }
    public void setElseStatement(StatementBlock elseStatement) { this.elseStatement = elseStatement; }

    public void setIsElseIf(boolean isElseIf) {
        this.isElseIf = isElseIf;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        container.getStyleClass().add("if-block");

        // Header with condition
        // Change keyword based on context
        String keyword = isElseIf ? "Else If" : "If";

        Button addButton = createAddButton(e ->
                showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.primitive("boolean"),
                        condition != null ? (Expression) condition.getAstNode() : null)
        );

        Node headerContent = BlockLayout.sentence()
                .addKeyword(keyword)
                .addExpressionSlot(condition, context, ResolvedType.primitive("boolean"))
                .addNode(addButton)
                .build();

        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build());

        // Then Body
        if (thenBody != null) {
            VBox thenNode = createIndentedBody(thenBody, context, "if-body");
            container.getChildren().add(thenNode);
        }

        // Else / Else If Logic
        if (elseStatement != null) {
            if (elseStatement instanceof IfBlock) {
                // Else If (Recursive IfBlock)
                // We delegate the "Else If" rendering to the child block itself to keep the hierarchy flat visually
                IfBlock childIf = (IfBlock) elseStatement;
                childIf.setIsElseIf(true);

                Node elseIfNode = childIf.getUINode(context);

                // The child block comes with its own gutter padding; pull it back left by exactly the
                // gutter width so "Else If" aligns with "If" rather than indenting.
                double gutter = com.botmaker.ui.render.theme.BlockTheme.current().spacing().gutter();
                VBox.setMargin(elseIfNode, new Insets(0, 0, 0, -gutter));

                container.getChildren().add(elseIfNode);

            } else if (elseStatement instanceof BodyBlock) {
                // Regular Else
                VBox elseContainer = new VBox(5);

                HBox elseHeader = BlockLayout.sentence()
                        .addKeyword("Else")
                        .addNode(createAddButton(e ->
                                context.getCodeEditor().convertElseToElseIf((IfStatement) this.astNode)))
                        .addNode(BlockUIComponents.createSpacer())
                        .addNode(BlockUIComponents.createDeleteButton(() ->
                                context.getCodeEditor().deleteElseFromIfStatement((IfStatement) this.astNode)))
                        .build();

                VBox elseBodyNode = createIndentedBody((BodyBlock) elseStatement, context, "if-body");
                elseContainer.getChildren().addAll(elseHeader, elseBodyNode);
                container.getChildren().add(elseContainer);
            }
        } else {
            // Add Else Button
            Button addElseButton = createAddButton(e ->
                    context.getCodeEditor().addElseToIfStatement((IfStatement) this.astNode));
            container.getChildren().add(addElseButton);
        }

        return container;
    }

    @Override
    public int getBreakpointLine(CompilationUnit cu) {
        if (condition != null) return condition.getBreakpointLine(cu);
        return super.getBreakpointLine(cu);
    }

    @Override
    public com.botmaker.core.CodeBlock getHighlightTarget() {
        return condition != null ? condition : this;
    }
}