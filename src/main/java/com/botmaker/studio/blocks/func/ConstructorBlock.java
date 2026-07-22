package com.botmaker.studio.blocks.func;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.util.DefaultNames;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.List;

public class ConstructorBlock extends MethodDeclarationBlock {

    public ConstructorBlock(String id, MethodDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode, manager);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(0);
        MethodDeclaration md = (MethodDeclaration) this.astNode;
        String name = md.getName().getIdentifier();

        // Header Style (Distinct color for Constructor)
        VBox headerBox = new VBox(5);
        headerBox.getStyleClass().addAll("block-header", "block-header--constructor");

        // 1. Top Row: Keyword + Name (Read Only) + Delete
        Label keywordLabel = new Label("Constructor");
        keywordLabel.getStyleClass().add("header-keyword-label");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("header-name-label");

        var topRowBuilder = BlockLayout.sentence()
                .addNode(keywordLabel)
                .addNode(nameLabel)
                .addNode(BlockUIComponents.createSpacer());

        Button deleteBtn = new Button("×");
        deleteBtn.getStyleClass().add("header-delete-button");
        deleteBtn.setOnAction(e -> context.getCodeEditor().deleteMethod(md));
        topRowBuilder.addNode(deleteBtn);

        headerBox.getChildren().add(topRowBuilder.build());

        // 2. Parameters Row (Reusing parent logic for creating parameter pills)
        Label paramsLabel = new Label("Inputs:");
        paramsLabel.getStyleClass().add("header-params-label");

        var paramRowBuilder = BlockLayout.sentence().addNode(paramsLabel);

        List<?> params = md.parameters();
        for (int i = 0; i < params.size(); i++) {
            // We use the parent method (which we updated in Phase 2) to render interactive pills
            paramRowBuilder.addNode(super.createParamNode((SingleVariableDeclaration) params.get(i), i, context));
        }

        Button addParamBtn = new Button("+");
        addParamBtn.getStyleClass().add("add-param-button");
        addParamBtn.setOnAction(e -> com.botmaker.studio.ui.render.menu.ExpressionMenu.showTypeMenu(
                addParamBtn, null, context, null, false, false,
                type -> context.getCodeEditor().addParameterToMethod(md, type,
                        DefaultNames.forType(type.simpleName()))));
        paramRowBuilder.addNode(addParamBtn);

        headerBox.getChildren().add(paramRowBuilder.build());
        container.getChildren().add(headerBox);

        // 3. Body (Reusing parent logic is tricky because parent combines header creation.
        // We manually render the body using the wrapper style)
        VBox bodyWrapper = new VBox();
        bodyWrapper.getStyleClass().addAll("block-body-wrapper", "block-body-wrapper--constructor");

        if (getChildren().size() > 0) {
            Node bodyNode = getChildren().getFirst().getUINode(context);
            javafx.scene.layout.VBox.setVgrow(bodyNode, javafx.scene.layout.Priority.ALWAYS);
            bodyWrapper.getChildren().add(bodyNode);
        }
        container.getChildren().add(bodyWrapper);

        return container;
    }
}