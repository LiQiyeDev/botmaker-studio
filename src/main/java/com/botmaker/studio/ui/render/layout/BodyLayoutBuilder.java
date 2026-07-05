package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BodyLayoutBuilder {
    private final Node headerNode; // Optional header
    private BodyBlock bodyBlock;
    private CodeEditorService context;
    private boolean indented = true;
    private Insets indentation = new Insets(5, 0, 0, 20);
    private final List<String> styleClasses = new ArrayList<>();

    public BodyLayoutBuilder() {
        this.headerNode = null;
    }

    public BodyLayoutBuilder(Node headerNode) {
        this.headerNode = headerNode;
    }

    public BodyLayoutBuilder withContent(BodyBlock body, CodeEditorService context) {
        this.bodyBlock = body;
        this.context = context;
        return this;
    }

    public BodyLayoutBuilder withIndentation(Insets insets) {
        this.indentation = insets;
        this.indented = true;
        return this;
    }

    public BodyLayoutBuilder noIndentation() {
        this.indented = false;
        return this;
    }

    public BodyLayoutBuilder withStyleClass(String... classes) {
        styleClasses.addAll(Arrays.asList(classes));
        return this;
    }

    public VBox build() {
        VBox container = new VBox(5);
        styleClasses.forEach(c -> container.getStyleClass().add(c));

        if (headerNode != null) {
            container.getChildren().add(headerNode);
        }

        if (bodyBlock != null) {
            VBox bodyContainer = new VBox();
            // "block-body" draws the left accent bar (blocks.css) so the body reads as enclosed by the block,
            // not merely indented.
            bodyContainer.getStyleClass().add("block-body");
            if (indented) {
                bodyContainer.setPadding(indentation);
            }
            bodyContainer.getChildren().add(bodyBlock.getUINode(context));
            container.getChildren().add(bodyContainer);
        }

        return container;
    }
}