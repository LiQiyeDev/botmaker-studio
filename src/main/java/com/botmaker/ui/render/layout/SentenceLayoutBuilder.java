package com.botmaker.ui.render.layout;

import com.botmaker.ui.dnd.DropZoneFactory;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.components.SelectorComponents;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SentenceLayoutBuilder {
    private final List<Node> nodes = new ArrayList<>();
    private double spacing = 5.0;
    private Pos alignment = Pos.CENTER_LEFT;

    public SentenceLayoutBuilder addKeyword(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("keyword-label");
        nodes.add(label);
        return this;
    }

    public SentenceLayoutBuilder addLabel(String text) {
        nodes.add(new Label(text));
        return this;
    }

    public SentenceLayoutBuilder addNode(Node node) {
        nodes.add(node);
        return this;
    }

    /**
     * ResolvedType overload for addExpressionSlot
     */
    public SentenceLayoutBuilder addExpressionSlot(com.botmaker.core.ExpressionBlock expression,
                                                   com.botmaker.services.CodeEditorService context,
                                                   com.botmaker.types.ResolvedType expectedType) {
        if (expression != null) {
            // Any ImageTemplate-typed slot gets the thumbnail/menu picker (same control call-argument slots use),
            // so e.g. the whileExists/ifExists image slot is fillable — not just a raw expression node.
            if (com.botmaker.ui.render.components.ImageTemplatePicker.isImageTemplateType(expectedType)) {
                nodes.add(com.botmaker.ui.render.components.ImageTemplatePicker.create(context, expression));
            } else {
                nodes.add(expression.getUINode(context));
            }
        } else {
            javafx.scene.control.Label placeholder = new javafx.scene.control.Label("⟨expression⟩");
            placeholder.setStyle("-fx-text-fill: rgba(255,255,255,0.4); -fx-font-style: italic;");
            nodes.add(placeholder);
        }
        return this;
    }

    public SentenceLayoutBuilder addOperatorSelector(
            String[] names,
            String[] symbols,
            String current,
            Consumer<String> onChange) {
        ComboBox<String> selector = SelectorComponents.createOperatorSelector(
                names, symbols, current, onChange
        );
        nodes.add(selector);
        return this;
    }

    public SentenceLayoutBuilder spacing(double spacing) {
        this.spacing = spacing;
        return this;
    }

    // --- ADDED MISSING METHOD ---
    public SentenceLayoutBuilder alignment(Pos alignment) {
        this.alignment = alignment;
        return this;
    }

    public HBox build() {
        HBox container = new HBox(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(nodes);
        return container;
    }

    private Node createDropZone(CodeEditorService context) {
        return DropZoneFactory.createExpressionDropZone(context);
    }
}