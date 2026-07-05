package com.botmaker.studio.blocks;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class ClassBlock extends AbstractCodeBlock implements BlockWithChildren {

    private final String className;
    private final List<CodeBlock> bodyDeclarations = new ArrayList<>();
    private final BlockDragAndDropManager dragAndDropManager;

    public ClassBlock(String id, TypeDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode);
        this.className = astNode.getName().getIdentifier();
        this.dragAndDropManager = manager;
    }

    public void addBodyDeclaration(CodeBlock block) {
        bodyDeclarations.add(block);
    }

    @Override
    public List<CodeBlock> getChildren() {
        return new ArrayList<>(bodyDeclarations);
    }

// In ClassBlock.java

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(10);
        container.setPadding(new Insets(15));

        container.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #ecf0f1 0%, #bdc3c7 100%);" +
                        "-fx-border-color: #34495e;" +
                        "-fx-border-width: 3px;" +
                        "-fx-border-radius: 10px;" +
                        "-fx-background-radius: 10px;"
        );

        Label header = new Label("Class: " + className);
        header.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-padding: 0 0 15 0;");
        container.getChildren().add(header);

        // --- 1. ADD BUTTONS AT TOP (Call helper to get NEW instances) ---
        container.getChildren().add(createControlToolbar(context));

        // Top separator
        container.getChildren().add(createClassMemberSeparator(context, 0));

        for (int i = 0; i < bodyDeclarations.size(); i++) {
            CodeBlock block = bodyDeclarations.get(i);

            Node node = block.getUINode(context);

            // Make methods draggable for reordering. makeBlockMovable installs the onDragDetected
            // handler itself, so call it directly at render time (not from inside an onDragDetected).
            if (block instanceof MethodDeclarationBlock) {
                context.getDragAndDropManager().makeBlockMovable(node, block);
            }

            container.getChildren().add(node);
            container.getChildren().add(createClassMemberSeparator(context, i + 1));
        }

        // --- 2. ADD BUTTONS AT BOTTOM (Call helper AGAIN to get NEW instances) ---
        container.getChildren().add(createControlToolbar(context));

        return container;
    }

    /**
     * Helper method to create a fresh set of buttons every time it is called.
     */
    private Node createControlToolbar(CodeEditorService context) {
        // Using HBox to put them side-by-side, or VBox if you prefer them stacked
        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button addConstructorBtn = new Button("+ Add Constructor");
        addConstructorBtn.setStyle(
                "-fx-background-color: #27AE60; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"
        );
        addConstructorBtn.setOnAction(e -> {
            context.getCodeEditor().addConstructorToClass((TypeDeclaration) this.astNode);
        });

        Button addMethodBtn = new Button("+ Add Function");
        addMethodBtn.setStyle(
                "-fx-background-color: #8E44AD; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"
        );
        addMethodBtn.setOnAction(e -> {
            context.getCodeEditor().addMethodToClass(
                    (TypeDeclaration) this.astNode,
                    "newMethod",
                    "void",
                    bodyDeclarations.size()
            );
        });

        toolbar.getChildren().addAll(addConstructorBtn, addMethodBtn);
        return toolbar;
    }

    private Region createClassMemberSeparator(CodeEditorService context, int insertIndex) {
        Region separator = new Region();
        separator.setMinHeight(15);
        separator.setMaxHeight(15);
        // No inline background: it would override the :drag-over-* pseudo-class feedback (inline styles
        // beat author stylesheets). A Region is transparent by default.
        separator.getStyleClass().add("class-member-separator");

        context.getDragAndDropManager().addClassMemberDropHandlers(separator, this, insertIndex);

        return separator;
    }
}