package com.botmaker.ui.render.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class LayoutComponents {

    private static final Insets STANDARD_INDENTATION = new Insets(5, 0, 0, 20);

    /**
     * Creates a VBox representing a nested body of code (e.g., inside an If or Loop).
     * Applies standard indentation and specific style classes.
     */
    public static VBox createIndentedBody(Node content, String... styleClasses) {
        VBox container = new VBox();
        if (styleClasses != null) {
            container.getStyleClass().addAll(styleClasses);
        }
        container.setPadding(STANDARD_INDENTATION);
        if (content != null) {
            container.getChildren().add(content);
        }
        return container;
    }

    /**
     * Inline bracketed list/array display: {@code open content close} (e.g. {@code [ … ]} or <code>{ … }</code>).
     * Styled via the {@code inline-list-display} / {@code list-bracket} classes in blocks.css; pass
     * {@code lightText=true} on dark-background blocks so the brackets render white.
     */
    public static HBox createInlineListDisplay(Node content, String open, String close, boolean lightText) {
        HBox listBox = new HBox(3);
        listBox.setAlignment(Pos.CENTER_LEFT);
        listBox.getStyleClass().add("inline-list-display");
        if (lightText) {
            listBox.getStyleClass().add("inline-list-display--light");
        }

        Label openLabel = new Label(open);
        Label closeLabel = new Label(close);
        openLabel.getStyleClass().add("list-bracket");
        closeLabel.getStyleClass().add("list-bracket");

        listBox.getChildren().addAll(openLabel, content, closeLabel);
        return listBox;
    }

    /**
     * Creates a horizontal row for building "sentences" (e.g., "for each [var] in [list]").
     */
    public static HBox createSentenceRow(Node... nodes) {
        HBox row = new HBox(5);
        row.setAlignment(Pos.CENTER_LEFT);
        if (nodes != null) {
            row.getChildren().addAll(nodes);
        }
        return row;
    }
}