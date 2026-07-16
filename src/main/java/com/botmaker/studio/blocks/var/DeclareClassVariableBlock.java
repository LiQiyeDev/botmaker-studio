package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;

import com.botmaker.studio.blocks.expr.ListBlock;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import static com.botmaker.studio.ui.render.components.BlockUIComponents.createTypeLabel;

public class DeclareClassVariableBlock extends AbstractStatementBlock {

    private final String variableName;
    private final ResolvedType fieldType;
    private final boolean isStatic;
    private final boolean isPrivate;
    private ExpressionBlock initializer;

    public DeclareClassVariableBlock(String id, FieldDeclaration astNode) {
        super(id, astNode);
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) astNode.fragments().getFirst();
        this.variableName = fragment.getName().getIdentifier();
        this.fieldType = ProjectAnalyzer.resolveType(astNode.getType());
        this.isStatic = Modifier.isStatic(astNode.getModifiers());
        this.isPrivate = Modifier.isPrivate(astNode.getModifiers());
        this.initializer = null;
    }

    public void setInitializer(ExpressionBlock initializer) {
        this.initializer = initializer;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        container.setStyle(
                "-fx-background-color: linear-gradient(to right, #F39C12 0%, #E67E22 100%);" +
                        "-fx-background-radius: 6;" +
                        "-fx-padding: 10;" +
                        "-fx-border-color: rgba(0,0,0,0.1);" +
                        "-fx-border-width: 1;" +
                        "-fx-border-radius: 6;"
        );

        Label modifiersLabel = new Label((isPrivate ? "Private" : "Public") + (isStatic ? " Static" : "") + " Field");
        modifiersLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10px;");

        Label typeLabel = createTypeLabel(fieldType.simpleName());
        if (!isReadOnly()) {
            ExpressionMenuFactory.installTypeSelector(typeLabel, "Click to change type", () -> fieldType,
                    context, this.astNode,
                    newTypeName -> context.getCodeEditor().replaceFieldType((FieldDeclaration) this.astNode, newTypeName.simpleName()));
        }

        Node nameField = TextFieldComponents.createVariableName(variableName, !isReadOnly(), newName -> {
            FieldDeclaration fieldDecl = (FieldDeclaration) this.astNode;
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) fieldDecl.fragments().getFirst();
            if (!newName.equals(variableName) && !newName.isEmpty()) {
                context.getCodeEditor().replaceSimpleName(fragment.getName(), newName);
            }
        });

        var mainRowBuilder = BlockLayout.sentence()
                .addNode(typeLabel)
                .addNode(nameField);

        if (initializer == null) {
            // "Set Value" writes an initializer, so a locked field must not offer it — like the delete and add
            // buttons, a read-only block gets no control at all rather than one that no-ops (or worse, an edit
            // the write layer then refuses). A read-only field with no value simply shows "type name".
            if (!isReadOnly()) {
                Button setValueBtn = new Button("Set Value");
                setValueBtn.setStyle(
                        "-fx-background-color: rgba(255,255,255,0.3);" +
                                "-fx-text-fill: white;" +
                                "-fx-font-weight: bold;" +
                                "-fx-font-size: 11px;" +
                                "-fx-padding: 4 12 4 12;" +
                                "-fx-background-radius: 4;" +
                                "-fx-cursor: hand;"
                );
                setValueBtn.setOnAction(e -> {
                    context.getCodeEditor().setFieldInitializerToDefault(
                            (FieldDeclaration) this.astNode, fieldType);
                });
                mainRowBuilder.addNode(setValueBtn);
            }
        } else {
            Node initNode = (initializer instanceof ListBlock) ?
                    initializer.getUINode(context) :
                    (initializer.getAstNode() instanceof ArrayInitializer) ?
                            createListDisplay(context) : initializer.getUINode(context);

            Button addButton = createAddButton(e -> {
                Expression currentInitializer = (Expression) initializer.getAstNode();

                // FIXED: Passed missing parameters (astNode, filter)
                ContextMenu menu = ExpressionMenuFactory.createExpressionTypeMenu(
                        fieldType,
                        false,
                        context,
                        this.astNode,
                        x -> true, // Allow all
                        selection -> applyExpressionSelection(context, currentInitializer, selection)
                );
                menu.show((Button)e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0);
            });

            mainRowBuilder
                    .addKeyword("=")
                    .addNode(initNode)
                    .addNode(addButton);
        }

        HBox mainRow = mainRowBuilder.build();

        HBox headerRow = new HBox(10);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getChildren().addAll(modifiersLabel, BlockUIComponents.createSpacer());

        // Null when this block is read-only: createDeleteButton returns null rather than a disabled button,
        // so a locked field simply has no delete affordance. Styling it unconditionally NPE'd and aborted the
        // whole render pass (a generated file with a field showed no blocks at all).
        Button deleteBtn = createDeleteButton(context);
        if (deleteBtn != null) {
            deleteBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 0; -fx-cursor: hand;");
            headerRow.getChildren().add(deleteBtn);
        }

        container.getChildren().addAll(headerRow, mainRow);

        return container;
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "[", "]", true);
    }
}