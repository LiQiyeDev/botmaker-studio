package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class DeclareEnumBlock extends AbstractStatementBlock {

    private final String enumName;
    private final List<String> constants;
    private final EnumDeclaration enumDeclaration;
    private final boolean isStatement; // True if inside a method, False if inside a class

    // Constructor 1: Inside a Method (Wrapped in TypeDeclarationStatement)
    public DeclareEnumBlock(String id, TypeDeclarationStatement astNode) {
        super(id, astNode);
        if (astNode.getDeclaration() instanceof EnumDeclaration) {
            this.enumDeclaration = (EnumDeclaration) astNode.getDeclaration();
            this.isStatement = true;
        } else {
            throw new IllegalArgumentException("Statement is not an EnumDeclaration");
        }
        this.enumName = enumDeclaration.getName().getIdentifier();
        this.constants = extractConstants(enumDeclaration);
    }

    // Constructor 2: Inside a Class (Raw EnumDeclaration)
    public DeclareEnumBlock(String id, EnumDeclaration astNode) {
        super(id, astNode); // We pass it as ASTNode, AbstractStatementBlock handles generic ASTNode
        this.enumDeclaration = astNode;
        this.isStatement = false;
        this.enumName = astNode.getName().getIdentifier();
        this.constants = extractConstants(astNode);
    }

    private List<String> extractConstants(EnumDeclaration decl) {
        List<String> list = new ArrayList<>();
        for (Object obj : decl.enumConstants()) {
            if (obj instanceof EnumConstantDeclaration) {
                list.add(((EnumConstantDeclaration) obj).getName().getIdentifier());
            }
        }
        return list;
    }

    // DeclareEnumBlock.java
    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        container.getStyleClass().add("enum-block");

        // --- Header ---
        Label label = BlockUIComponents.createKeywordLabel("Enum");
        label.getStyleClass().add("block-chip");

        Node nameField = TextFieldComponents.createVariableName(enumName, !isReadOnly(), newName -> {
            if (!newName.equals(enumName) && !newName.isEmpty()) {
                context.getCodeEditor().renameEnum(enumDeclaration, newName);
            }
        });

        // Null on a locked enum, and the sentence builder skips nulls: an activity's generated Outcome enum is
        // edited on the flow canvas, so offering "+ Add Value" here was an invitation the write layer refuses.
        Button addConstantBtn = null;
        if (!isReadOnly()) {
            addConstantBtn = new Button("+ Add Value");
            addConstantBtn.getStyleClass().addAll("block-action-button", "block-action-button--mini");
            addConstantBtn.setOnAction(e -> context.getCodeEditor().addEnumConstant(enumDeclaration, "NEW_VALUE"));
        }

        var headerSentence = BlockLayout.sentence()
                .addNode(label)
                .addNode(nameField)
                .addNode(addConstantBtn)
                .build();

        // Not deleteAction(context): a class-level enum is removed from its type, not from a statement list.
        // The read-only gate is the shared one all the same — this block used to build the delete
        // unconditionally and hand it straight to createHeaderRow, which is why a locked enum kept its cross.
        Runnable deleteAction = whenEditable(() -> {
            if (isStatement) {
                context.getCodeEditor().deleteStatement((Statement) this.astNode);
            } else {
                context.getCodeEditor().deleteEnumFromClass(enumDeclaration);
            }
        });

        HBox headerWrapper = BlockUIComponents.createHeaderRow(deleteAction, headerSentence);
        container.getChildren().add(headerWrapper);

        // --- Constants List ---
        if (!constants.isEmpty()) {
            VBox constantsBox = new VBox(2);
            constantsBox.setStyle("-fx-padding: 5 0 5 20;");

            for (int i = 0; i < constants.size(); i++) {
                String constant = constants.get(i);
                final int index = i;

                TextField constField = new TextField(constant);
                constField.setPrefWidth(120);
                constField.getStyleClass().add("block-inset-field");
                constField.setEditable(!isReadOnly());

                constField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal) {
                        String val = constField.getText();
                        if (!val.equals(constant) && !val.isEmpty()) {
                            context.getCodeEditor().renameEnumConstant(enumDeclaration, index, val);
                        }
                    }
                });

                Button deleteBtn = null;
                if (!isReadOnly()) {
                    deleteBtn = new Button("×");
                    deleteBtn.getStyleClass().add("block-icon-button");
                    deleteBtn.setOnAction(e -> context.getCodeEditor().deleteEnumConstant(enumDeclaration, index));
                }

                HBox row = BlockLayout.sentence()
                        .addNode(constField)
                        .addNode(deleteBtn)
                        .build();

                constantsBox.getChildren().add(row);
            }
            container.getChildren().add(constantsBox);
        }

        return container;
    }
}
