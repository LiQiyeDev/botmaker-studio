package com.botmaker.studio.ui.render.components;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

public class TextFieldComponents {

    public static TextField createCommentField(String initialText, String prompt, Consumer<String> onCommit) {
        TextField field = new TextField(initialText != null ? initialText : "");
        field.setPromptText(prompt);
        field.getStyleClass().add("comment-text-field");
        HBox.setHgrow(field, Priority.ALWAYS);

        // Save on Focus Lost or Enter
        field.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onCommit.accept(field.getText());
        });
        field.setOnAction(e -> onCommit.accept(field.getText()));

        return field;
    }

    /**
     * A multi-line, wrapping editor for a comment. Unlike {@link #createCommentField} (a single line that
     * scrolls a long note out of view) this grows downward and wraps, so a paragraph-length note is fully
     * visible while editing. Commits on focus-loss; Enter inserts a newline (a comment can be multi-line).
     */
    public static TextArea createCommentEditArea(String initialText, String prompt, Consumer<String> onCommit) {
        TextArea area = new TextArea(initialText != null ? initialText : "");
        area.setPromptText(prompt);
        area.getStyleClass().add("comment-edit-area");
        area.setWrapText(true);
        area.setPrefRowCount(2);
        HBox.setHgrow(area, Priority.ALWAYS);

        area.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onCommit.accept(area.getText());
        });

        return area;
    }

    /**
     * A name field, or — when {@code editable} is false — a plain label showing the same name.
     *
     * <p>A read-only block gets no text field at all, not a disabled one: a field you can click into and type
     * in reads as editable no matter what happens when you commit, and what happened before was that the edit
     * silently vanished on reload.
     */
    public static Node createVariableName(String initialText, boolean editable, Consumer<String> onCommit) {
        if (!editable) {
            Label label = new Label(initialText == null ? "" : initialText);
            label.getStyleClass().addAll("variable-name-field", "static-value-label");
            return label;
        }
        return createVariableNameField(initialText, onCommit);
    }

    public static TextField createVariableNameField(String initialText, Consumer<String> onCommit) {
        TextField nameField = new TextField(initialText);
        nameField.getStyleClass().add("variable-name-field");
        nameField.setPrefWidth(100);

        nameField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) onCommit.accept(nameField.getText());
        });

        return nameField;
    }
}