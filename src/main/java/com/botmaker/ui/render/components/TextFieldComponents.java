package com.botmaker.ui.render.components;

import javafx.scene.Cursor;
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