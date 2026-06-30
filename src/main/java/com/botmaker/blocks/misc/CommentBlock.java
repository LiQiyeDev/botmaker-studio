package com.botmaker.blocks.misc;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.TextFieldComponents;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.eclipse.jdt.core.dom.Comment;

public class CommentBlock extends AbstractStatementBlock {

    private String commentText;

    public CommentBlock(String id, Comment astNode, String commentText) {
        super(id, astNode);
        this.commentText = commentText;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label commentLabel = new Label("Comment:");
        commentLabel.getStyleClass().add("comment-indicator");

        TextField commentField = TextFieldComponents.createCommentField(
                commentText,
                "Write your note here...",
                newText -> {
                    if (!newText.equals(commentText)) {
                        this.commentText = newText;
                        javafx.application.Platform.runLater(() -> {
                            context.getCodeEditor().updateComment((Comment) this.astNode, this.commentText);
                        });
                    }
                }
        );

        var sentence = BlockLayout.sentence()
                .addNode(commentLabel)
                .addNode(commentField)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((org.eclipse.jdt.core.dom.Statement) this.astNode))
                .build();
    }

    @Override
    public String getDetails() {
        return "Comment: " + (commentText != null ? commentText : "");
    }
}