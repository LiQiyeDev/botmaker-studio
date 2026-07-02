package com.botmaker.blocks.flow;

import com.botmaker.ui.render.menu.ExpressionMenuFactory;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.BlockWithChildren;
import com.botmaker.core.CodeBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.dnd.BlockDragAndDropManager;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class SwitchBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock expression;
    private final List<SwitchCaseBlock> cases = new ArrayList<>();
    private final BlockDragAndDropManager dragAndDropManager;

    public SwitchBlock(String id, SwitchStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
        this.dragAndDropManager = dragAndDropManager;
    }

    public void setExpression(ExpressionBlock expression) { this.expression = expression; }
    public void addCase(SwitchCaseBlock caseBlock) { this.cases.add(caseBlock); }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (expression != null) children.add(expression);
        children.addAll(cases);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox mainContainer = new VBox(5);

        // 1. Determine Switch Type
        ResolvedType switchType = ResolvedType.UNKNOWN;
        if (expression != null && expression.getAstNode() != null) {
            Expression expr = (Expression) expression.getAstNode();
            ITypeBinding binding = expr.resolveTypeBinding();
            if (binding != null) {
                switchType = ResolvedType.of(binding);
            }
        }

        ResolvedType finalSwitchType = switchType;
        Button changeSwitchExprBtn = BlockUIComponents.createChangeButton(e ->
                showExpressionMenuAndReplace((Button)e.getSource(), context, finalSwitchType,
                        expression != null ? (Expression) expression.getAstNode() : null)
        );

        var headerSentence = BlockLayout.sentence()
                .addKeyword("switch")
                .addExpressionSlot(expression, context, switchType)
                .addNode(changeSwitchExprBtn)
                .build();

        mainContainer.getChildren().add(BlockLayout.header()
                .withCustomNode(headerSentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((org.eclipse.jdt.core.dom.Statement) this.astNode))
                .build());

        VBox casesContainer = new VBox(5);
        casesContainer.setPadding(new javafx.geometry.Insets(5, 0, 0, 20));

        // Pass switchType down to cases
        for (int i = 0; i < cases.size(); i++) {
            SwitchCaseBlock caseBlock = cases.get(i);
            casesContainer.getChildren().add(caseBlock.createUINode(context, i, cases.size(), switchType));
        }

        mainContainer.getChildren().add(casesContainer);

        Button addCaseButton = new Button("+ Add Case");
        addCaseButton.setOnAction(e -> context.getCodeEditor().addCaseToSwitch((SwitchStatement) this.astNode));
        mainContainer.getChildren().add(addCaseButton);

        return mainContainer;
    }

    public static class SwitchCaseBlock extends AbstractStatementBlock implements BlockWithChildren {
        private ExpressionBlock caseExpression;
        private BodyBlock body;

        public SwitchCaseBlock(String id, SwitchCase astNode) {
            super(id, astNode);
        }

        public void setCaseExpression(ExpressionBlock caseExpression) { this.caseExpression = caseExpression; }
        public void setBody(BodyBlock body) { this.body = body; }
        public boolean isDefault() { return caseExpression == null; }

        @Override
        public List<CodeBlock> getChildren() {
            List<CodeBlock> children = new ArrayList<>();
            if (caseExpression != null) children.add(caseExpression);
            if (body != null) children.add(body);
            return children;
        }

        @Override
        protected Node createUINode(CodeEditorService context) {
            // Fallback if called directly without parent context (shouldn't happen in normal UI)
            return createUINode(context, -1, -1, ResolvedType.UNKNOWN);
        }

        public Node createUINode(CodeEditorService context, int index, int totalCases, ResolvedType switchType) {
            VBox container = new VBox(5);
            var caseHeaderBuilder = BlockLayout.sentence();

            if (isDefault()) {
                caseHeaderBuilder.addKeyword("default:");
            } else {
                // strict filtering for case values
                Button changeBtn = BlockUIComponents.createChangeButton(e -> {
                    ContextMenu menu = ExpressionMenuFactory.createExpressionTypeMenu(
                            switchType,
                            true, // constantOnly = true (Critical for Switch)
                            context,
                            this.astNode,
                            x -> true,
                            selection -> applyExpressionSelection(context, (Expression) caseExpression.getAstNode(), selection)
                    );
                    menu.show((Button)e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0);
                });

                caseHeaderBuilder
                        .addKeyword("case")
                        .addExpressionSlot(caseExpression, context, switchType)
                        .addNode(changeBtn)
                        .addKeyword(":");
            }

            if (index >= 0) {
                Button upBtn = BlockUIComponents.createMoveUpButton(
                        () -> context.getCodeEditor().moveSwitchCase((SwitchCase) this.astNode, true));
                upBtn.setDisable(index == 0);

                Button downBtn = BlockUIComponents.createMoveDownButton(
                        () -> context.getCodeEditor().moveSwitchCase((SwitchCase) this.astNode, false));
                downBtn.setDisable(index == totalCases - 1);

                caseHeaderBuilder
                        .addNode(BlockUIComponents.createSpacer())
                        .addNode(upBtn)
                        .addNode(downBtn);
            }

            HBox caseHeader = caseHeaderBuilder.build();
            caseHeader.getChildren().add(createDeleteButton(context));

            container.getChildren().add(caseHeader);

            VBox bodyNode = createIndentedBody(body, context, "switch-case-body");
            if (bodyNode != null) container.getChildren().add(bodyNode);

            return container;
        }
    }
}