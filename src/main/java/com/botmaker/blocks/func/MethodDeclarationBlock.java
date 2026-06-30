// FILE: rs\bgroi\Documents\dev\IntellijProjects\BotMaker\src\main\java\com\botmaker\blocks\MethodDeclarationBlock.java
package com.botmaker.blocks.func;

import com.botmaker.ui.render.menu.ExpressionMenuFactory;
import com.botmaker.ui.render.menu.MenuComponents;
import com.botmaker.util.DefaultNames;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.BlockWithChildren;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.CodeBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.dnd.BlockDragAndDropManager;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.BlockUIComponents;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.*;

public class MethodDeclarationBlock extends AbstractStatementBlock implements BlockWithChildren {

    /** Drives the collapsed-header corner radius via blocks.css (`.block-header:collapsed`). */
    protected static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");

    private final String methodName;
    private final String returnType;
    private BodyBlock body;

    protected boolean isDeletable = true; // False for Main method
    private boolean isCollapsed = false;

    public MethodDeclarationBlock(String id, MethodDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode);
        this.methodName = astNode.getName().getIdentifier();
        if (astNode.getReturnType2() != null) {
            this.returnType = astNode.getReturnType2().toString();
        } else {
            this.returnType = "void";
        }
    }

    public void setBody(BodyBlock body) {
        this.body = body;
    }

    @Override
    public List<CodeBlock> getChildren() {
        return body != null ? Collections.singletonList(body) : Collections.emptyList();
    }

    // Hook for subclasses (MainBlock) to hide specific parameters like 'args'
    protected boolean shouldDisplayParameter(SingleVariableDeclaration param) {
        return true;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(0);

        // --- STATE SYNC ---
        String parentName = "";
        if (this.astNode.getParent() instanceof AbstractTypeDeclaration) {
            parentName = ((AbstractTypeDeclaration) this.astNode.getParent()).getName().getIdentifier();
        }
        String methodKey = parentName + "." + methodName;

        // Restore state from ApplicationState
        this.isCollapsed = context.getState().isMethodCollapsed(methodKey);

        // --- HEADER SECTION ---
        VBox headerBox = new VBox(5);
        headerBox.getStyleClass().add("block-header");
        headerBox.pseudoClassStateChanged(COLLAPSED, isCollapsed);

        // 1. Create the Body Wrapper
        VBox bodyWrapper = new VBox();
        bodyWrapper.getStyleClass().add("block-body-wrapper");

        if (body != null) {
            Node bodyNode = body.getUINode(context);
            VBox.setVgrow(bodyNode, javafx.scene.layout.Priority.ALWAYS);
            bodyWrapper.getChildren().add(bodyNode);
        }

        // 2. Collapse Toggle Button
        Button collapseBtn = new Button(isCollapsed ? "▶" : "▼");
        collapseBtn.getStyleClass().add("collapse-button");
        collapseBtn.setMinWidth(25);

        collapseBtn.setOnAction(e -> {
            this.isCollapsed = !this.isCollapsed;
            collapseBtn.setText(isCollapsed ? "▶" : "▼");
            context.getState().setMethodCollapsed(methodKey, this.isCollapsed);
            headerBox.pseudoClassStateChanged(COLLAPSED, isCollapsed);

            if (isCollapsed) {
                container.getChildren().remove(bodyWrapper);
            } else {
                container.getChildren().add(bodyWrapper);
            }
        });

        // 3. Top Row (Name & Return Type)
        Label funcLabel = new Label("Function");
        funcLabel.getStyleClass().add("header-keyword-label");

        Node nameNode;
        if (isDeletable) {
            TextField nameField = new TextField(methodName);
            nameField.getStyleClass().add("method-name-field");
            nameField.setPrefWidth(Math.max(80, methodName.length() * 8 + 20));

            nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    String newName = nameField.getText().trim();
                    if (!newName.isEmpty() && !newName.equals(methodName) && !"main".equals(newName)) {
                        context.getCodeEditor().renameMethod((MethodDeclaration) this.astNode, newName);
                    } else {
                        nameField.setText(methodName);
                    }
                }
            });
            nameNode = nameField;
        } else {
            Label nameLabel = new Label(methodName);
            nameLabel.getStyleClass().add("header-name-label");
            nameNode = nameLabel;
        }

        Label returnsLabel = new Label("returns");
        returnsLabel.getStyleClass().add("method-returns-label");

        ComboBox<String> typeSelector = new ComboBox<>();
        typeSelector.getItems().add("void");
        typeSelector.getItems().addAll(ProjectAnalyzer.getFundamentalTypeNames());
        typeSelector.setValue(returnType);
        typeSelector.getStyleClass().add("inline-type-selector");
        typeSelector.setOnAction(e -> {
            String selected = typeSelector.getValue();
            if (!selected.equals(returnType)) {
                context.getCodeEditor().setMethodReturnType((MethodDeclaration) this.astNode, selected);
            }
        });

        var topRowBuilder = BlockLayout.sentence()
                .addNode(collapseBtn)
                .addNode(funcLabel)
                .addNode(nameNode)
                .addNode(BlockUIComponents.createSpacer())
                .addNode(returnsLabel)
                .addNode(typeSelector);

        if (isDeletable) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("header-delete-button");
            deleteBtn.setOnAction(e -> context.getCodeEditor().deleteMethod((MethodDeclaration) this.astNode));
            topRowBuilder.addNode(deleteBtn);
        }

        HBox topRow = topRowBuilder.build();

        // 4. Parameters Row
        Label paramsLabel = new Label("Inputs:");
        paramsLabel.getStyleClass().add("header-params-label");

        var paramRowBuilder = BlockLayout.sentence()
                .addNode(paramsLabel);

        MethodDeclaration md = (MethodDeclaration) this.astNode;
        List<?> params = md.parameters();

        for (int i = 0; i < params.size(); i++) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(i);
            if (shouldDisplayParameter(param)) {
                paramRowBuilder.addNode(createParamNode(param, i, context));
            }
        }

        if (isDeletable) {
            MenuButton addParamBtn = new MenuButton("+");
            addParamBtn.getStyleClass().add("add-param-button");

            List<ResolvedType> availableTypes = context.getProjectAnalyzer().getAvailableTypes(null);
            MenuComponents.populateGroupedTypeMenu(addParamBtn.getItems(), availableTypes,
                    ProjectAnalyzer.getFundamentalTypeNames(),
                    type -> context.getCodeEditor().addParameterToMethod((MethodDeclaration) this.astNode,
                            type.simpleName(), DefaultNames.forType(type.simpleName())));

            paramRowBuilder.addNode(addParamBtn);
        }

        HBox paramRow = paramRowBuilder.build();

        headerBox.getChildren().addAll(topRow, paramRow);
        container.getChildren().add(headerBox);

        if (!isCollapsed) {
            container.getChildren().add(bodyWrapper);
        }

        return container;
    }

    Node createParamNode(SingleVariableDeclaration param, int index, CodeEditorService context) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("param-pill");

        Label typeLabel = new Label(param.getType().toString());
        typeLabel.getStyleClass().add("param-type-label");

        if (isDeletable) {
            ExpressionMenuFactory.installTypeSelector(typeLabel, "Click to change type",
                    () -> ProjectAnalyzer.resolveType(param.getType()), context, null,
                    newType -> context.getCodeEditor().changeMethodParameterType((MethodDeclaration) this.astNode, index, newType));
        }

        String currentName = param.getName().getIdentifier();
        TextField nameField = new TextField(currentName);

        nameField.getStyleClass().add("param-name-field");
        nameField.setPrefWidth(Math.max(30, currentName.length() * 7));

        nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                String val = nameField.getText().trim();
                if (!val.isEmpty() && !val.equals(currentName)) {
                    context.getCodeEditor().renameMethodParameter((MethodDeclaration) this.astNode, index, val);
                } else {
                    nameField.setText(currentName);
                }
            }
        });

        box.getChildren().addAll(typeLabel, nameField);

        if (isDeletable) {
            Button deleteBtn = new Button("×");
            deleteBtn.getStyleClass().add("param-delete-button");

            deleteBtn.setOnAction(e -> {
                context.getCodeEditor().deleteParameterFromMethod((MethodDeclaration) this.astNode, index);
            });
            box.getChildren().add(deleteBtn);
        }

        return box;
    }
}