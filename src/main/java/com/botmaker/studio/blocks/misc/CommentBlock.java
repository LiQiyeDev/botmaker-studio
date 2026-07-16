package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.eclipse.jdt.core.dom.Comment;

public class CommentBlock extends AbstractStatementBlock {

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
        // The field asks to grow, but only its direct parent can grant that — so the sentence has to be told
        // to take the header's spare width, or a long note scrolls inside a ~120px box.
        HBox.setHgrow(sentence, Priority.ALWAYS);

        return BlockLayout.header()
                .withGrowingNode(sentence)
                .withDeleteButton(deleteAction(context))
                .build();
    }

    @Override
    public String getDetails() {
        return "Comment: " + (commentText != null ? commentText : "");
    }
}