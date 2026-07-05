package com.botmaker.studio.ui.render.components;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

/**
 * Pure JavaFX widget factories for blocks (buttons, labels, spacers, header rows). No domain
 * knowledge — the type-aware menus live in
 * {@link com.botmaker.studio.ui.render.menu.ExpressionMenuFactory}.
 */
public final class BlockUIComponents {

    private BlockUIComponents() {}

    public static Button createDeleteButton(Runnable onDelete) {
        Button btn = new Button("X");
        btn.getStyleClass().add("icon-button");
        btn.setOnAction(e -> onDelete.run());
        return btn;
    }

    public static Button createAddButton(EventHandler<ActionEvent> handler) {
        Button btn = new Button("+");
        btn.getStyleClass().addAll("icon-button", "expression-add-button");
        btn.setOnAction(handler);
        return btn;
    }

    // UNIFIED: Change button now looks like Add button
    public static Button createChangeButton(EventHandler<ActionEvent> handler) {
        Button btn = new Button("+");
        btn.getStyleClass().addAll("icon-button", "expression-add-button");
        btn.setOnAction(handler);
        return btn;
    }

    /** Small up-arrow reorder button — same fixed footprint as the other {@code icon-button}s. */
    public static Button createMoveUpButton(Runnable onMove) {
        return moveButton("▲", onMove);
    }

    /** Small down-arrow reorder button — same fixed footprint as the other {@code icon-button}s. */
    public static Button createMoveDownButton(Runnable onMove) {
        return moveButton("▼", onMove);
    }

    private static Button moveButton(String glyph, Runnable onMove) {
        Button btn = new Button(glyph);
        btn.getStyleClass().addAll("icon-button", "list-move-button");
        btn.setOnAction(e -> onMove.run());
        return btn;
    }

    public static Label createKeywordLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("keyword-label");
        return label;
    }

    public static Label createTypeLabel(String type) {
        Label label = new Label(type);
        label.getStyleClass().add("type-label");
        return label;
    }

    public static Label createOperatorLabel(String operator) {
        Label label = new Label(operator);
        label.getStyleClass().add("operator-label");
        return label;
    }

    public static Pane createSpacer() {
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /**
     * A call-argument "pill": {@code [leadingLabel] argNode changeButton} laid out horizontally. Shared by
     * {@code InstantiationBlock} and {@code MethodInvocationBlock}. Styled via the {@code argument-pill} class in
     * blocks.css; pass {@code onDarkBackground=true} for blocks whose background is dark (translucent-white fill)
     * vs. light (translucent-black fill). {@code leadingLabel} may be null.
     */
    public static HBox createArgumentPill(Node leadingLabel, Node argNode, Button changeButton, boolean onDarkBackground) {
        HBox argBox = new HBox(2);
        argBox.setAlignment(Pos.CENTER_LEFT);
        argBox.getStyleClass().add("argument-pill");
        if (onDarkBackground) {
            argBox.getStyleClass().add("argument-pill--on-dark");
        }
        if (leadingLabel != null) {
            argBox.getChildren().add(leadingLabel);
        }
        argBox.getChildren().addAll(argNode, changeButton);
        return argBox;
    }

    public static HBox createHeaderRow(Runnable onDelete, Node... content) {
        HBox container = new HBox(5);
        container.setAlignment(Pos.CENTER_LEFT);
        if (content != null) container.getChildren().addAll(content);
        container.getChildren().addAll(createSpacer(), createDeleteButton(onDelete));
        return container;
    }
}
