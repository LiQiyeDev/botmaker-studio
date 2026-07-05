package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import com.botmaker.studio.ui.render.components.ArgumentEditors;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class ListBlock extends AbstractExpressionBlock {

    private final List<ExpressionBlock> elements = new ArrayList<>();
    private final boolean isFixedArray;

    public ListBlock(String id, ASTNode astNode) {
        super(id, astNode);
        this.isFixedArray = (astNode instanceof ArrayInitializer);
    }

    public void addElement(ExpressionBlock element) { this.elements.add(element); }
    public List<ExpressionBlock> getElements() { return elements; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);
        container.setAlignment(Pos.TOP_LEFT);
        container.getStyleClass().add("list-block");

        boolean isNested = (this.astNode.getParent() instanceof ArrayInitializer) ||
                (this.astNode.getParent() instanceof MethodInvocation);
        container.getStyleClass().add(isNested ? "list-block-nested" : "list-block-root");
        container.setPadding(isNested ? new Insets(4, 6, 4, 6) : new Insets(6, 10, 6, 10));

        ResolvedType itemType = ListElementType.of(this.astNode);

        String typeLabel = isFixedArray ? "Array" : "List";
        Label listLabel = new Label(typeLabel + " (" + elements.size() + ")");
        listLabel.getStyleClass().addAll("list-label", isFixedArray ? "list-label-array" : "list-label-generic");
        container.getChildren().add(listLabel);

        if (elements.isEmpty()) {
            Label emptyLabel = new Label(" (empty) ");
            emptyLabel.getStyleClass().add("list-empty-label");
            container.getChildren().add(emptyLabel);
        } else {
            VBox elementsContainer = new VBox(3);
            elementsContainer.setPadding(new Insets(2, 0, 0, 12));
            for (int i = 0; i < elements.size(); i++) {
                HBox elementRow = createElementRow(i, elements.get(i), context, itemType);
                elementsContainer.getChildren().add(elementRow);
            }
            container.getChildren().add(elementsContainer);
        }

        // The "+" add button lives beneath the last element (append position), not above the list.
        Button addButton = new Button("+");
        addButton.getStyleClass().add("list-add-button");
        addButton.setOnAction(e -> showAddElementMenu(addButton, context, elements.size(), itemType));
        container.getChildren().add(addButton);

        return container;
    }

    private HBox createElementRow(int index, ExpressionBlock element, CodeEditorService context, ResolvedType itemType) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("list-element-row");

        Label indexLabel = new Label(String.valueOf(index));
        indexLabel.getStyleClass().add("list-index-label");

        // For typed elements (ImageTemplate / Rect / Point / enum) the specialized editor IS the editor —
        // the generic type-change menu is redundant.
        Node specialized = ArgumentEditors.editorFor(context, element, itemType);
        boolean hasSpecialEditor = specialized != null;
        Node elementNode = hasSpecialEditor ? specialized : element.getUINode(context);
        if (element instanceof ListBlock) HBox.setHgrow(elementNode, javafx.scene.layout.Priority.ALWAYS);

        Button upButton = BlockUIComponents.createMoveUpButton(
                () -> context.getCodeEditor().moveListElement(this.astNode, index, index - 1));
        upButton.setDisable(index == 0);

        Button downButton = BlockUIComponents.createMoveDownButton(
                () -> context.getCodeEditor().moveListElement(this.astNode, index, index + 1));
        downButton.setDisable(index == elements.size() - 1);

        Button deleteButton = new Button("✕");
        deleteButton.getStyleClass().addAll("icon-button", "list-delete-button");
        deleteButton.setOnAction(e -> deleteElement(index, context));

        row.getChildren().addAll(indexLabel, elementNode);
        if (!hasSpecialEditor) {
            Button changeButton = new Button("+");
            changeButton.getStyleClass().addAll("icon-button", "list-change-button");
            changeButton.setOnAction(e -> showChangeElementMenu(changeButton, context, index, itemType));
            row.getChildren().add(changeButton);
        }
        row.getChildren().addAll(upButton, downButton, deleteButton);
        return row;
    }

    private void showAddElementMenu(Button button, CodeEditorService context, int insertIndex, ResolvedType targetType) {
        // For an ImageTemplate list, skip the generic expression menu — add a template element directly and
        // let its per-element image picker drive the actual choice.
        if (ImageTemplatePicker.isImageTemplateType(targetType)) {
            context.getCodeEditor().addImageTemplateToList(this.astNode, insertIndex);
            return;
        }
        // Reuse the same type-aware menu the "change" path uses (variable / method / constructor / enum
        // submenus), but insert the chosen expression at the target index instead of replacing one.
        ContextMenu menu = ExpressionMenuFactory.createExpressionTypeMenu(
                targetType,
                false,
                context,
                this.astNode,
                x -> true,
                selection -> context.getCodeEditor().insertIntoList(this.astNode, insertIndex, selection, targetType)
        );
        menu.show(button, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void showChangeElementMenu(Button button, CodeEditorService context, int elementIndex, ResolvedType targetType) {
        if (elementIndex < elements.size()) {
            ExpressionBlock oldElement = elements.get(elementIndex);
            Expression oldExpr = (Expression) oldElement.getAstNode();
            // Delegate to the shared type-aware replace menu in AbstractExpressionBlock.
            showExpressionMenuAndReplace(button, context, targetType, oldExpr);
        }
    }

    private void deleteElement(int index, CodeEditorService context) {
        if (index >= 0 && index < elements.size()) {
            context.getCodeEditor().deleteElementFromList(this.astNode, index);
        }
    }

    @Override
    public String getDetails() { return (isFixedArray ? "Array" : "List") + " (" + elements.size() + " items)"; }
}
