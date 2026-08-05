package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the overlay's compact tree actually draws for a branching program.
 *
 * <p>Every case here was invisible before {@link BlockTree#flatten}: the HUD walked only the direct
 * {@link BodyBlock} children of a statement, so an {@code else} body drew as an unlabelled twin of the
 * {@code then} above it, and {@code else if} / {@code case} bodies — which are not direct children — did not
 * draw <em>at all</em>. A user could look at a complete-looking program with most of its logic missing from
 * the view, which is the worst thing an authoring surface can do, and nothing failed to make it visible.
 */
class BlockTreeFlattenTest {

    /** The flattened rows of {@code run()} for an activity whose body is {@code runBody}. */
    private static List<BlockTree.Row> rowsOf(String runBody) {
        CodeBlock root = OverlayTestTrees.activityTree(runBody);
        BodyBlock run = OverlayTestTrees.bodyOf(root, "run");
        assertNotNull(run, "the fixture must have a run() body");
        return BlockTree.flatten(run, 0);
    }

    /** The rows rendered as {@code "<depth>:<caption or code>"}, which is what a reader of the HUD sees. */
    private static List<String> shape(List<BlockTree.Row> rows) {
        return rows.stream().map(r -> switch (r.kind()) {
            case CAPTION -> r.depth() + ":" + r.caption();
            case EMPTY -> r.depth() + ":(empty)";
            case STATEMENT -> r.depth() + ":" + OverlayTreeView.compactLabel(r.stmt());
        }).toList();
    }

    @Test
    void anIfElseIfElseChainDrawsEveryBranch() {
        List<String> shape = shape(rowsOf("""
                        int x = 1;
                        if (x == 1) {
                            int a = 1;
                        } else if (x == 2) {
                            int b = 2;
                        } else {
                            int c = 3;
                        }
                """));

        // The chain stays flat — `else if` aligns with its `if`, as the block editor draws it — and each
        // branch body sits one level in. Before this, only `int a = 1;` appeared: `b` and `c` lived in bodies
        // reached through the chained IfBlock, which the old walk never opened.
        assertEquals(List.of(
                "0:int x=1;",
                "0:if (x == 1)",
                "1:int a=1;",
                "0:else if (x == 2)",
                "1:int b=2;",
                "0:else",
                "1:int c=3;"), shape);
    }

    @Test
    void aSwitchDrawsEveryCaseWithItsLabel() {
        List<String> shape = shape(rowsOf("""
                        int x = 1;
                        switch (x) {
                            case 1:
                                int a = 1;
                                break;
                            case 2:
                                int b = 2;
                                break;
                            default:
                                int c = 3;
                        }
                """));

        assertTrue(shape.contains("0:case 1:"), () -> "no case-1 caption in " + shape);
        assertTrue(shape.contains("0:case 2:"), () -> "no case-2 caption in " + shape);
        assertTrue(shape.contains("0:default:"), () -> "no default caption in " + shape);
        // The bodies themselves: a switch's cases are SwitchCaseBlocks, not bodies, so the old direct-children
        // walk found nothing to draw and the whole switch rendered as a single unexpandable row.
        assertTrue(shape.contains("1:int a=1;"), () -> "case 1's body is missing from " + shape);
        assertTrue(shape.contains("1:int b=2;"), () -> "case 2's body is missing from " + shape);
        assertTrue(shape.contains("1:int c=3;"), () -> "default's body is missing from " + shape);
    }

    @Test
    void aLoopBodyStillDrawsUncaptioned() {
        // The non-branching path is unchanged: a body with no branch identity gets no caption row.
        assertEquals(List.of(
                "0:while (true)",
                "1:int a=1;"), shape(rowsOf("""
                        while (true) {
                            int a = 1;
                        }
                """)));
    }

    @Test
    void anEmptyBranchDrawsItsOwnPlaceholderRow() {
        // An empty `else` must still be visible and clickable — it is the only way to aim the caret at it.
        List<String> shape = shape(rowsOf("""
                        if (true) {
                            int a = 1;
                        } else {
                        }
                """));
        assertEquals(List.of(
                "0:if (true)",
                "1:int a=1;",
                "0:else",
                "1:(empty)"), shape);
    }

    @Test
    void aCollapsedStatementKeepsItsRowAndHidesEverythingUnderIt() {
        String source = """
                        int x = 1;
                        if (x == 1) {
                            int a = 1;
                        } else {
                            int c = 3;
                        }
                        int y = 2;
                """;
        CodeBlock root = OverlayTestTrees.activityTree(source);
        BodyBlock run = OverlayTestTrees.bodyOf(root, "run");
        assertNotNull(run);

        // Fold the `if`. Its own row stays — the program still has an `if` there, and hiding that would be a
        // lie — but the branches, their captions and their statements all go, and what follows still draws.
        List<BlockTree.Row> folded = BlockTree.flatten(run, 0,
                s -> OverlayTreeView.compactLabel(s).startsWith("if"));
        assertEquals(List.of("0:int x=1;", "0:if (x == 1)", "0:int y=2;"), shape(folded));

        BlockTree.Row owner = folded.get(1);
        assertEquals(BlockTree.Fold.COLLAPSED, owner.fold());
        // …and the same statement reports EXPANDED when nothing is folded, so the view can draw ▸ vs ▾ from
        // the row alone rather than re-deriving the fold state it was just rendered with.
        assertEquals(BlockTree.Fold.EXPANDED, BlockTree.flatten(run, 0).get(1).fold());
    }

    @Test
    void aStatementWithNothingBeneathItIsNotFoldable() {
        // The ▸/▾ toggle is drawn from Fold != NONE, so a plain statement must never report one — an
        // expand control on a row with nothing to expand reads as "this row is hiding something".
        List<BlockTree.Row> rows = rowsOf("int x = 1;");
        assertEquals(BlockTree.Fold.NONE, rows.getFirst().fold());
    }

    @Test
    void aCaptionAddressesTheBodyItIntroduces() {
        // Clicking `else` parks the caret above the else body's first statement, so the next insert lands
        // inside the else rather than after the whole `if`.
        List<BlockTree.Row> rows = rowsOf("""
                        if (true) {
                            int a = 1;
                        } else {
                            int b = 2;
                        }
                """);
        BlockTree.Row caption = rows.stream()
                .filter(r -> r.kind() == BlockTree.Kind.CAPTION).findFirst().orElseThrow();
        BlockTree.Row elseStmt = rows.getLast();

        assertEquals("else", caption.caption());
        assertSame(elseStmt.body(), caption.body(), "the caption must point at the body it labels");
        assertEquals(-1, caption.index(), "a caption addresses the slot above the body's first statement");
    }
}
