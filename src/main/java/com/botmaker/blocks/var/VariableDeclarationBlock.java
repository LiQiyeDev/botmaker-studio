
        package com.botmaker.blocks.var;

import com.botmaker.ui.render.menu.ExpressionMenuFactory;
import com.botmaker.blocks.expr.ListBlock;
import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.ui.render.components.LayoutComponents;
import com.botmaker.ui.render.components.TextFieldComponents;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import static com.botmaker.ui.render.components.BlockUIComponents.createTypeLabel;

public class VariableDeclarationBlock extends AbstractStatementBlock {

    private final String variableName;
    private final ResolvedType varType;
    private ExpressionBlock initializer;

    public VariableDeclarationBlock(String id, VariableDeclarationStatement astNode) {
        super(id, astNode);
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) astNode.fragments().getFirst();
        this.variableName = fragment.getName().getIdentifier();
        this.varType = ProjectAnalyzer.resolveType(astNode.getType());
        this.initializer = null;
    }

    public void setInitializer(ExpressionBlock initializer) { this.initializer = initializer; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label typeLabel = createTypeLabel(varType.simpleName());
        // PASS THE AST NODE TO ENABLE LOCAL TYPE DETECTION
        ExpressionMenuFactory.installTypeSelector(typeLabel, "Click to change type", () -> varType,
                context, this.astNode,
                newTypeName -> context.getCodeEditor().replaceVariableType((VariableDeclarationStatement) this.astNode, newTypeName));

        TextField nameField = TextFieldComponents.createVariableNameField(variableName, newName -> {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment)
                    ((VariableDeclarationStatement) this.astNode).fragments().getFirst();
            if (!newName.equals(variableName) && !newName.isEmpty()) {
                context.getCodeEditor().replaceSimpleName(fragment.getName(), newName);
            }
        });

        Node initNode;
        if (initializer != null) {
            if (initializer instanceof ListBlock) {
                initNode = initializer.getUINode(context);
            } else if (initializer.getAstNode() instanceof ArrayInitializer) {
                initNode = createListDisplay(context);
            } else {
                initNode = initializer.getUINode(context);
            }
        } else {
            initNode = createExpressionDropZone(context);
        }

        Button addButton = createAddButton(e -> {
            Expression currentInitializer = initializer != null ?
                    (Expression) initializer.getAstNode() : null;

            ContextMenu menu = ExpressionMenuFactory.createExpressionTypeMenu(
                    varType, false, context, this.astNode, x -> true,
                    selection -> {
                        if (currentInitializer != null) {
                            applyExpressionSelection(context, currentInitializer, selection);
                        } else {
                            context.getCodeEditor().setVariableInitializer((VariableDeclarationStatement) this.astNode, selection);
                        }
                    });
            menu.show((Button)e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0);
        });

        var sentence = BlockLayout.sentence()
                .addNode(typeLabel)
                .addNode(nameField)
                .addKeyword("=")
                .addNode(initNode)
                .addNode(addButton)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement(
                        (Statement) this.astNode))
                .build();
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "{", "}", false);
    }
}