package com.botmaker.studio.core;

import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

public class BodyBlock extends AbstractStatementBlock implements BlockWithChildren {
    private final List<StatementBlock> statements = new ArrayList<>();
    private final BlockDragAndDropManager dragAndDropManager;

    public BodyBlock(String id, Statement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
        this.dragAndDropManager = dragAndDropManager;
    }

    public void addStatement(StatementBlock statement) {
        statements.add(statement);
    }

    public List<StatementBlock> getStatements() {
        return new ArrayList<>(statements);
    }

    public void removeStatement(StatementBlock statement) {
        statements.remove(statement);
    }

    public void insertStatement(int index, StatementBlock statement) {
        if (index < 0 || index > statements.size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + statements.size());
        }
        statements.add(index, statement);
    }

    @Override
    public List<CodeBlock> getChildren() {
        return new ArrayList<>(statements);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox();
        container.getStyleClass().add("body-block");
        VBox.setVgrow(container, Priority.ALWAYS);

        if (statements.isEmpty()) {
            javafx.scene.control.Label placeholder = new javafx.scene.control.Label("Click to add a block");
            placeholder.getStyleClass().add("empty-body-placeholder");
            // Enable interaction
            placeholder.setMouseTransparent(false);
            placeholder.setCursor(Cursor.HAND);

            // Add Click Handler
            placeholder.setOnMouseClicked(e -> {
                ContextMenu menu = ExpressionMenuFactory.createStatementMenu(type -> {
                    context.getCodeEditor().addStatement(this, type, 0);
                });
                menu.show(placeholder, javafx.geometry.Side.BOTTOM, 0, 0);
            });

            container.getChildren().add(placeholder);
            container.setAlignment(Pos.CENTER);
            container.setMinHeight(30);
            dragAndDropManager.addEmptyBodyDropHandlers(container, this);
        } else {
            // Lay out as sep[0], stmt[0], sep[1], stmt[1], …, sep[n]; keep the separator Panes so each statement
            // node can light the one above/below it as its drop indicator.
            List<Pane> separators = new ArrayList<>();
            List<Node> statementNodes = new ArrayList<>();

            Pane firstSep = createSeparatorWithHandlers(this, 0, context);
            separators.add(firstSep);
            container.getChildren().add(firstSep);

            for (int i = 0; i < statements.size(); i++) {
                StatementBlock statement = statements.get(i);
                Node statementNode = statement.getUINode(context);
                makeStatementDraggable(statementNode, statement);
                statementNodes.add(statementNode);
                container.getChildren().add(statementNode);

                Pane sep = createSeparatorWithHandlers(this, i + 1, context);
                separators.add(sep);
                container.getChildren().add(sep);
            }

            // Wire the whole-block drop hitboxes now that all separators exist.
            for (int i = 0; i < statements.size(); i++) {
                dragAndDropManager.addBlockDropHitbox(
                        statementNodes.get(i), statements.get(i), this, i,
                        separators.get(i), separators.get(i + 1));
            }
        }
        return container;
    }

    private void makeStatementDraggable(Node statementNode, StatementBlock statement) {
        statementNode.setOnMouseEntered(e -> statementNode.setCursor(Cursor.OPEN_HAND));
        statementNode.setOnMouseExited(e -> statementNode.setCursor(Cursor.DEFAULT));
        dragAndDropManager.makeBlockMovable(statementNode, statement);
    }

    private Pane createSeparatorWithHandlers(BodyBlock targetBody, int insertionIndex, CodeEditorService context) {
        // 1. Create the Smart Separator (StackPane)
        Pane separator = dragAndDropManager.createSeparator();
        separator.getStyleClass().add("body-block-separator");

        // 2. Setup Drag-and-Drop Handlers
        dragAndDropManager.addSeparatorDragHandlers(separator, targetBody, insertionIndex);

        // 3. Setup Click Insert Handler
        // This wires the hidden "+" button to the CodeEditor
        dragAndDropManager.enableSeparatorClick(separator, type -> {
            context.getCodeEditor().addStatement(targetBody, type, insertionIndex);
        });

        return separator;
    }
}