package com.botmaker.studio.ui.render;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.StyleBuilder;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio ui MISSING 8 — a {@code render/theme} + {@code render/layout} smoke test before deleting 55
 * members.</b> Gates <b>SU5</b>.
 *
 * <p>Explicitly <em>not</em> a test of the DSL. 74% of ui's {@code unused} findings are in these two
 * packages, and SU5 deletes the dead majority; what this file exists to say is what the <b>live minority</b>
 * does, so a deletion that reaches one member too far fails here instead of at the next launch. The live
 * entry points are {@code BlockLayout.header()}, {@code BlockLayout.sentence()},
 * {@code HeaderLayoutBuilder.andBody()}, and on the theme side {@code BlockTheme.current()} and
 * {@code StyleBuilder.create()} — the five names every block renderer in the module actually calls.
 *
 * <p>Each assertion is about a structural property a block depends on: how many children a container ends up
 * with, whether the spacer that pushes the delete button right exists, whether the body indents. A deletion
 * that keeps a method's signature but loses its effect fails these too.
 */
class ThemeAndLayoutSmokeTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene under test; layout builders construct real controls, which need the toolkit up.
    }

    // =====================================================================
    // layout — the three live entry points
    // =====================================================================

    @Test
    void aHeaderRendersItsKeywordAndLabelInOrder() {
        HBox header = BlockLayout.header().withKeyword("if").withLabel("condition").build();

        assertEquals(2, header.getChildren().size());
        assertEquals("if", ((Label) header.getChildren().get(0)).getText());
        assertEquals("condition", ((Label) header.getChildren().get(1)).getText());
    }

    /**
     * The delete button is pushed to the trailing edge by a growing spacer. Without it the "X" sits against
     * the block's text, which is the one visual detail every block header shares.
     */
    @Test
    void aHeaderWithADeleteButtonGrowsASpacerBeforeIt() {
        boolean[] deleted = { false };
        HBox header = BlockLayout.header().withKeyword("while").withDeleteButton(() -> deleted[0] = true).build();

        assertEquals(3, header.getChildren().size(), "keyword, spacer, delete: " + header.getChildren());
        assertSame(Priority.ALWAYS, HBox.getHgrow(header.getChildren().get(1)),
                "the middle child must be the growing spacer");

        Button delete = (Button) header.getChildren().get(2);
        assertEquals("X", delete.getText());
        assertTrue(delete.getStyleClass().contains("icon-button"), "the delete button is styled by CSS class");
        delete.fire();
        assertTrue(deleted[0], "the delete callback must be wired to the button it built");
    }

    /**
     * A growing node takes the spare width <em>instead</em> of a spacer — the comment in {@code build()} says
     * adding both would split the slack in half, leaving a text field half the width it should have. That is
     * a behaviour a signature-preserving deletion could silently undo.
     */
    @Test
    void aGrowingNodeTakesTheSlackAndSuppressesTheSpacer() {
        Label field = new Label("editable");
        HBox header = BlockLayout.header()
                .withKeyword("set")
                .withGrowingNode(field)
                .withDeleteButton(() -> { })
                .build();

        assertEquals(3, header.getChildren().size(), "keyword, growing node, delete — no spacer");
        assertSame(Priority.ALWAYS, HBox.getHgrow(field));
    }

    @Test
    void aGrowingNodeStillGrowsWithNoRightHandContentAtAll() {
        Label field = new Label("editable");
        HBox header = BlockLayout.header().withGrowingNode(field).build();

        assertEquals(1, header.getChildren().size());
        assertSame(Priority.ALWAYS, HBox.getHgrow(field));
    }

    @Test
    void aSentenceRendersItsPartsInTheOrderTheyWereAdded() {
        HBox sentence = BlockLayout.sentence()
                .addKeyword("print")
                .addLabel("(")
                .addNode(new Button("value"))
                .addLabel(")")
                .build();

        assertEquals(4, sentence.getChildren().size());
        assertEquals("print", ((Label) sentence.getChildren().get(0)).getText());
        assertEquals("value", ((Button) sentence.getChildren().get(2)).getText());
        assertEquals(")", ((Label) sentence.getChildren().get(3)).getText());
    }

    @Test
    void spacingAndAlignmentAreAppliedToTheContainerRatherThanIgnored() {
        HBox sentence = BlockLayout.sentence().addKeyword("x").spacing(13).alignment(Pos.BOTTOM_RIGHT).build();
        assertEquals(13, sentence.getSpacing());
        assertEquals(Pos.BOTTOM_RIGHT, sentence.getAlignment());

        HBox header = BlockLayout.header().withKeyword("x").spacing(7).alignment(Pos.TOP_LEFT).build();
        assertEquals(7, header.getSpacing());
        assertEquals(Pos.TOP_LEFT, header.getAlignment());
    }

    /** The third live entry point: a header continuing into an indented body — every control-flow block. */
    @Test
    void aHeaderContinuedIntoABodyKeepsTheHeaderAsItsFirstChild() {
        HBox header = BlockLayout.header().withKeyword("if").build();
        VBox whole = BlockLayout.header().withKeyword("if").andBody().build();

        assertNotNull(whole);
        assertFalse(whole.getChildren().isEmpty(), "the body layout must keep the header it was chained from");
        assertTrue(whole.getChildren().getFirst() instanceof HBox,
                "the first child is the built header: " + whole.getChildren().getFirst());
        assertEquals(header.getChildren().size(),
                ((HBox) whole.getChildren().getFirst()).getChildren().size());
    }

    /**
     * The indent lands on the <em>inner</em> body container, and only once content has been attached — a
     * header chained to an empty body renders as a bare header. Worth stating, because "the body indents" is
     * true of a container the caller never touches directly.
     */
    @Test
    void aBodyWithContentIndentsItAndCanBeToldNotTo() {
        EditorFixture fixture = new EditorFixture("""
                package com.example;
                public class Subject { void run() { int a = 1; } }
                """);
        BodyBlock body = fixture.body("run");

        VBox indented = BlockLayout.header().withKeyword("if").andBody()
                .withContent(body, fixture.context()).build();
        VBox flush = BlockLayout.header().withKeyword("if").andBody()
                .withContent(body, fixture.context()).noIndentation().build();

        assertEquals(2, indented.getChildren().size(), "header, then the body container");
        VBox indentedBody = (VBox) indented.getChildren().get(1);
        assertTrue(indentedBody.getPadding().getLeft() > 0, "a nested body reads as nested by its indent");
        assertTrue(indentedBody.getStyleClass().contains("block-body"),
                "the left accent bar is a CSS class, and blocks.css is what draws the enclosure");

        assertEquals(Insets.EMPTY, ((VBox) flush.getChildren().get(1)).getPadding());
    }

    @Test
    void aBodyWithNoContentIsJustItsHeader() {
        VBox whole = BlockLayout.header().withKeyword("if").andBody().build();

        assertEquals(1, whole.getChildren().size(), "no content attached, so no body container to indent");
    }

    @Test
    void aBodysStyleClassesReachTheContainer() {
        VBox body = BlockLayout.header().withKeyword("if").andBody().withStyleClass("block-body", "if-body")
                .build();

        assertTrue(body.getStyleClass().containsAll(java.util.List.of("block-body", "if-body")));
    }

    // =====================================================================
    // theme — the two live entry points
    // =====================================================================

    /**
     * {@code BlockTheme.current()} is read at render time by the gutter decorator, {@code IfBlock} and the
     * drag-and-drop highlighter. All three navigate two levels down ({@code .spacing().gutter()},
     * {@code .colors().primary()}), so the accessors in between are live even though nothing names them
     * directly — which is exactly how a deletion pass loses them.
     */
    @Test
    void theCurrentThemeAnswersTheThreeChainsTheRenderersWalk() {
        BlockTheme theme = BlockTheme.current();
        assertNotNull(theme, "every block render starts here");

        assertTrue(theme.spacing().gutter() > 0,
                "the gutter width is the single source of the block's left margin");
        assertNotNull(theme.colors().primary());
        assertNotNull(theme.colors().hover());
        assertTrue(theme.colors().primary().startsWith("#"),
                "colours go straight into a -fx- style string, so they must be CSS-shaped");
    }

    @Test
    void theCurrentThemeIsStableWithinARender() {
        assertSame(BlockTheme.current(), BlockTheme.current(),
                "two reads inside one render must not produce different geometry");
    }

    /** {@code StyleBuilder.create()} builds the inline {@code -fx-} strings three block renderers apply. */
    @Test
    void theStyleBuilderProducesAnFxStyleString() {
        String style = StyleBuilder.create()
                .backgroundColor(BlockTheme.current().colors().primary())
                .build();

        assertNotNull(style);
        assertTrue(style.contains("-fx-background-color"), "not an FX style string: " + style);
        assertTrue(style.contains(BlockTheme.current().colors().primary()), style);
    }

    @Test
    void anEmptyStyleBuilderIsAnEmptyStringRatherThanNull() {
        assertEquals("", StyleBuilder.create().build());
    }
}
