package com.botmaker.studio.ui.render.components;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.botmaker.studio.ui.render.layout.WrappingSentencePane;

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
        // A wrapping pill (tight hgap, small hanging indent): when the parent row clamps this pill to the
        // canvas width, its own contents (label / nested expression / change button) wrap internally rather
        // than overflow — so a deeply-nested argument is still fully visible.
        WrappingSentencePane argBox = new WrappingSentencePane(2, 2, 12);
        argBox.setAlignment(Pos.CENTER_LEFT);
        argBox.getStyleClass().add("argument-pill");
        if (onDarkBackground) {
            argBox.getStyleClass().add("argument-pill--on-dark");
        }
        if (leadingLabel != null) {
            argBox.getChildren().add(leadingLabel);
        }
        argBox.getChildren().add(argNode);
        // Null when the owning block is read-only: the argument shows, with no way to change it.
        if (changeButton != null) {
            argBox.getChildren().add(changeButton);
        }
        return argBox;
    }

    /**
     * A small "?" explanation button that opens a click-dismissable popover ({@code title} in bold over a
     * word-wrapped {@code body}). Used for the "learn about it" SDK method help on
     * {@code MethodInvocationBlock} / {@code LambdaCallBlock}; domain-free — callers assemble the text from
     * {@code palette.SdkDocs}.
     */
    public static Button createInfoButton(String title, String body) {
        Button btn = new Button("?");
        btn.getStyleClass().add("icon-button");
        btn.setTooltip(new Tooltip("Explain this method"));

        VBox content = new VBox(4);
        content.setStyle("-fx-padding: 8 10 8 10;");
        content.setMaxWidth(360);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");
        Label bodyLabel = new Label(body);
        bodyLabel.setWrapText(true);
        bodyLabel.setMaxWidth(340);
        content.getChildren().addAll(titleLabel, bodyLabel);

        ContextMenu popover = new ContextMenu();
        popover.getItems().add(new CustomMenuItem(content, false)); // hideOnClick=false → text stays put
        btn.setOnAction(e -> popover.show(btn, Side.BOTTOM, 0, 0));
        return btn;
    }

    /**
     * A header row. A null {@code onDelete} means this block may not be deleted, so no delete button — and no
     * spacer to push one to, which would otherwise leave a stray gap on every read-only block.
     */
    public static HBox createHeaderRow(Runnable onDelete, Node... content) {
        HBox container = new HBox(5);
        container.setAlignment(Pos.CENTER_LEFT);
        if (content != null) {
            for (Node node : content) {
                if (node != null) container.getChildren().add(node);
            }
        }
        if (onDelete != null) {
            container.getChildren().addAll(createSpacer(), createDeleteButton(onDelete));
        }
        return container;
    }
}
