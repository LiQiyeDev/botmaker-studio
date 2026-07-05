package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.ui.dnd.DropZoneFactory;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

public class HeaderLayoutBuilder {
    private final List<Node> leftContent = new ArrayList<>();
    private final List<Node> rightContent = new ArrayList<>();
    private Runnable onDelete;
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    public HeaderLayoutBuilder withKeyword(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("keyword-label");
        leftContent.add(label);
        return this;
    }

    public HeaderLayoutBuilder withLabel(String text) {
        leftContent.add(new Label(text));
        return this;
    }

    public HeaderLayoutBuilder withExpressionSlot(
            com.botmaker.studio.core.ExpressionBlock expr,
            CodeEditorService context,
            String targetType) {
        if (expr != null) {
            leftContent.add(expr.getUINode(context));
        } else {
            leftContent.add(createDropZone(context));
        }
        return this;
    }

    public HeaderLayoutBuilder withChangeButton(Runnable onClick) {
        Button btn = new Button("+");
        btn.getStyleClass().add("icon-button");
        btn.setOnAction(e -> onClick.run());
        leftContent.add(btn);
        return this;
    }

    public HeaderLayoutBuilder withAddButton(Runnable onClick) {
        Button btn = new Button("+");
        btn.getStyleClass().add("expression-add-button");
        btn.setOnAction(e -> onClick.run());
        leftContent.add(btn);
        return this;
    }

    public HeaderLayoutBuilder withDeleteButton(Runnable onDelete) {
        this.onDelete = onDelete;
        return this;
    }

    public HeaderLayoutBuilder withCustomNode(Node node) {
        leftContent.add(node);
        return this;
    }

    public HeaderLayoutBuilder withRightNode(Node node) {
        rightContent.add(node);
        return this;
    }

    public HeaderLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    public HeaderLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    // Terminal operation - builds and returns the Node
    public HBox build() {
        HBox container = new HBox(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(leftContent);

        if (onDelete != null || !rightContent.isEmpty()) {
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            container.getChildren().add(spacer);

            container.getChildren().addAll(rightContent);

            if (onDelete != null) {
                Button deleteBtn = new Button("X");
                deleteBtn.setOnAction(e -> onDelete.run());
                container.getChildren().add(deleteBtn);
            }
        }

        return container;
    }

    // Chaining into body builder
    public BodyLayoutBuilder andBody() {
        HBox header = build();
        return new BodyLayoutBuilder(header);
    }

    // ===== HELPER METHODS =====

    /**
     * Creates a drop zone for expressions with drag-and-drop support
     */
    private Node createDropZone(CodeEditorService context) {
        return DropZoneFactory.createExpressionDropZone(context);
    }
}