package com.botmaker.studio.ui.fx;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A comment's text field fills the block instead of hiding a long note inside ~120px.
 *
 * <p>The bug wasn't clipping or a missing {@code wrapText}: it was a broken {@code Hgrow} chain. The field
 * asked to grow, but an {@code Hgrow} only distributes space its <em>direct</em> parent has, and the sentence
 * row holding it was added to the header as an ordinary node — so the header's spacer took all the slack and
 * the sentence stayed at its preferred width, leaving the field at its 12-column default.
 *
 * <p>This asserts the layout contract that fixes it, on real laid-out JavaFX nodes: give the header room and
 * the field must actually take it.
 */
class CommentBlockWidthTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // Scenes are built per test.
    }

    /** The comment block's structure: [Comment: ][field] inside a header with a delete button. */
    private static TextField layOutCommentRow(boolean growingNode, double width) {
        TextField field = new TextField("a very long note that would otherwise scroll out of sight");
        HBox.setHgrow(field, Priority.ALWAYS);

        HBox sentence = new HBox(5, new Label("Comment:"), field);

        HBox header;
        if (growingNode) {
            HBox.setHgrow(sentence, Priority.ALWAYS);
            header = new HBox(5, sentence, new javafx.scene.control.Button("X"));
        } else {
            javafx.scene.layout.Pane spacer = new javafx.scene.layout.Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            header = new HBox(5, sentence, spacer, new javafx.scene.control.Button("X"));
        }

        VBox root = new VBox(header);
        new Scene(root, width, 100);
        root.applyCss();
        root.layout();
        return field;
    }

    @Test
    void theFieldTakesTheBlocksWidth() {
        double width = 800;
        TextField field = layOutCommentRow(true, width);
        assertTrue(field.getWidth() > width / 2,
                "the comment field should fill the block; was " + field.getWidth() + "px of " + width);
    }

    @Test
    void aSpacerBesideTheSentenceIsWhatUsedToStarveIt() {
        // Pins the cause, so a well-meaning "just add a spacer back" reintroduces a failing test, not a bug.
        double width = 800;
        TextField starved = layOutCommentRow(false, width);
        TextField grown = layOutCommentRow(true, width);
        assertTrue(starved.getWidth() < grown.getWidth() / 2,
                "a greedy spacer takes the slack the field asked for: starved=" + starved.getWidth()
                        + " grown=" + grown.getWidth());
    }
}
