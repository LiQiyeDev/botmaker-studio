package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.eclipse.jdt.core.dom.Comment;

/**
 * A code comment, rendered as a read-only note by default with a small edit button — a comment is documentation,
 * not a control, so it shouldn't look like an always-open text field. The text wraps, so a long note stays fully
 * visible instead of scrolling inside a narrow box. In a locked/reader file there is no edit button at all.
 */
public class CommentBlock extends AbstractStatementBlock {

    private static final String PROMPT = "Write your note here…";

    private String commentText;

    public CommentBlock(String id, Comment astNode, String commentText) {
        super(id, astNode);
        this.commentText = commentText;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.UTILITY;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox row = new HBox(6);
        row.getStyleClass().add("comment-block");
        row.setAlignment(Pos.TOP_LEFT);

        Label icon = new Label("💬");
        icon.getStyleClass().add("comment-icon");
        row.getChildren().add(icon);

        showReadOnly(row, context);
        return row;
    }

    /** Renders the note as wrapping read-only text, with an edit button unless the file is locked. */
    private void showReadOnly(HBox row, CodeEditorService context) {
        row.getChildren().remove(1, row.getChildren().size());   // keep the leading 💬 icon, replace the rest

        Label view = new Label(displayText());
        view.getStyleClass().add("comment-view");
        view.setWrapText(true);
        view.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(view, Priority.ALWAYS);
        row.getChildren().add(view);

        if (!isReadOnly()) {
            Button edit = new Button("✎");
            edit.getStyleClass().add("comment-edit-button");
            edit.setOnAction(e -> showEditing(row, context));
            row.getChildren().add(edit);
        }
    }

    /** Swaps in a wrapping editor; commits (and returns to read-only) on focus loss. */
    private void showEditing(HBox row, CodeEditorService context) {
        row.getChildren().remove(1, row.getChildren().size());   // keep the leading 💬 icon, replace the rest

        TextArea editor = TextFieldComponents.createCommentEditArea(commentText, PROMPT, newText -> {
            if (!newText.equals(commentText)) {
                this.commentText = newText;
                javafx.application.Platform.runLater(() ->
                        context.getCodeEditor().updateComment((Comment) this.astNode, this.commentText));
            }
            javafx.application.Platform.runLater(() -> showReadOnly(row, context));
        });
        row.getChildren().add(editor);
        editor.requestFocus();
    }

    /** The note text, or a muted placeholder when it is empty, so an empty comment is still clickable. */
    private String displayText() {
        return (commentText == null || commentText.isBlank()) ? PROMPT : commentText;
    }

    @Override
    public String getDetails() {
        return "Comment: " + (commentText != null ? commentText : "");
    }
}
