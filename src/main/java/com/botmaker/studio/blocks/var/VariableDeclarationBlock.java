package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;
import com.botmaker.studio.blocks.expr.ListBlock;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import static com.botmaker.studio.ui.render.components.BlockUIComponents.createTypeLabel;

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
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label typeLabel = createTypeLabel(varType.simpleName());
        // The label always shows the type; only an editable block gets the click-to-change behaviour.
        if (!isReadOnly()) {
            // PASS THE AST NODE TO ENABLE LOCAL TYPE DETECTION
            ExpressionMenuFactory.installTypeSelector(typeLabel, "Click to change type", () -> varType,
                    context, this.astNode,
                    newTypeName -> context.getCodeEditor().replaceVariableType((VariableDeclarationStatement) this.astNode, newTypeName));
        }

        Node nameField = TextFieldComponents.createVariableName(variableName, !isReadOnly(), newName -> {
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
                // Route the initializer through the same specialized pickers used for call arguments, keyed on
                // the declared variable type — so `ImageTemplate t = new ImageTemplate(...)`, `Rect r = ...`,
                // `Point p = ...`, `Direction d = ...` get their thumbnail/region/enum editor instead of a raw
                // expression node. Falls back to the generic node when no picker matches.
                Node picker = PickerRegistry.pickerNodeFor(PickerContext.of(context, initializer, varType));
                initNode = picker != null ? picker : initializer.getUINode(context);
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
                .withDeleteButton(deleteAction(context))
                .build();
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "{", "}", false);
    }
}