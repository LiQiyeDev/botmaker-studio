package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.ui.dnd.DropZoneFactory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.SelectorComponents;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;

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
        nodes.add(noEllipsis(label));
        return this;
    }

    public SentenceLayoutBuilder addLabel(String text) {
        nodes.add(noEllipsis(new Label(text)));
        return this;
    }

    /**
     * The wrapping row ({@link WrappingSentencePane}) only ever clamps a token that is wider than a whole
     * line, so a label should clip rather than ellipsize when that happens — an "…" would be the very
     * "content hidden" symptom the wrapping layout exists to remove.
     */
    private static Label noEllipsis(Label label) {
        label.setTextOverrun(OverrunStyle.CLIP);
        return label;
    }

    /**
     * Adds {@code node}, or nothing at all when it is null.
     *
     * <p>Null is the "this affordance does not exist" signal, not an error: a read-only block's factories
     * ({@code AbstractStatementBlock.createAddButton}, {@code createDeleteButton},
     * {@code AbstractCodeBlock.createChangeButton}) return null, and the control is then simply never built.
     * A locked block must offer no interaction — not a disabled or greyed one — so there is nothing to click,
     * nothing to explain, and nothing for a future block to forget to guard.
     */
    public SentenceLayoutBuilder addNode(Node node) {
        if (node != null) nodes.add(node);
        return this;
    }

    /**
     * ResolvedType overload for addExpressionSlot
     */
    public SentenceLayoutBuilder addExpressionSlot(com.botmaker.studio.core.ExpressionBlock expression,
                                                   com.botmaker.studio.services.CodeEditorService context,
                                                   com.botmaker.studio.types.ResolvedType expectedType) {
        if (expression != null) {
            // A typed slot gets its specialized picker (image/group/rect/point/enum), same as call-argument
            // slots — so e.g. the whileFind/ifFind image slot is fillable, not just a raw expression node.
            Node picker = com.botmaker.studio.ui.render.components.pickers.PickerRegistry.pickerNodeFor(
                    com.botmaker.studio.ui.render.components.pickers.PickerContext.of(context, expression, expectedType));
            nodes.add(picker != null ? picker : expression.getUINode(context));
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
        // A wrapping row (not a plain HBox): overflowing pills fall onto indented continuation lines instead
        // of being squeezed/ellipsized. Returned as HBox so every caller (styleContainer, getChildren, CSS)
        // is unchanged — only the layout math differs.
        WrappingSentencePane container = new WrappingSentencePane(spacing);
        container.setAlignment(alignment);
        container.getChildren().addAll(nodes);
        return container;
    }

    private Node createDropZone(CodeEditorService context) {
        return DropZoneFactory.createExpressionDropZone(context);
    }
}