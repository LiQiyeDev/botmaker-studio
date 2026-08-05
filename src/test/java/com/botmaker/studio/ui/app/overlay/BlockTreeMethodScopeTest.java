package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the overlay addresses a method, and how it tells whether the caret is still inside the one it is scoped
 * to. Both used to be answered by the bare method <em>name</em>, which is not an identity: two overloads
 * collapsed to one picker entry and every lookup silently answered with whichever the parser saw first.
 */
class BlockTreeMethodScopeTest {

    private static final String OVERLOADED = """
            package com.test.activities;

            public class Mining {
                public String run() {
                    int a = 1;
                    return "NEXT";
                }

                public void aim(int x) {
                    int b = 2;
                }

                public void aim(int x, int y) {
                    int c = 3;
                }
            }
            """;

    @Test
    void everyMethodGetsItsOwnLabelAndItsOwnBody() {
        BlockTree.Index index = BlockTree.index(OverlayTestTrees.treeOf(OVERLOADED));
        List<String> labels = index.methodLabels();

        assertEquals(List.of("run()", "aim(int x)", "aim(int x, int y)"), labels);
        assertEquals(labels.size(), labels.stream().distinct().count(), "labels are the picker's keys");

        // The point of the signature: the two overloads resolve to different bodies.
        BodyBlock one = index.methodBody("aim(int x)");
        BodyBlock two = index.methodBody("aim(int x, int y)");
        assertNotNull(one);
        assertNotNull(two);
        assertNotSame(one, two);
        assertEquals("int b=2;", one.getStatements().getFirst().getAstNode().toString().trim());
        assertEquals("int c=3;", two.getStatements().getFirst().getAstNode().toString().trim());
    }

    @Test
    void aCaretIsOnlyInScopeInsideItsOwnMethod() {
        CodeBlock root = OverlayTestTrees.treeOf(OVERLOADED);
        BlockTree.Index index = BlockTree.index(root);
        BodyBlock run = index.methodBody("run()");
        BodyBlock aim = index.methodBody("aim(int x)");
        assertNotNull(run);
        assertNotNull(aim);

        assertTrue(BlockTree.containsDescendant(run, run), "a body contains itself");
        // The check behind ensureCursor: a caret seeded by defaultCursor can land in a different method than
        // the tree is scoped to, and the scoped render then shows no focus anywhere while inserts still land.
        assertFalse(BlockTree.containsDescendant(run, aim), "a sibling method is out of scope");
    }

    @Test
    void aNestedBodyIsStillInsideItsMethod() {
        CodeBlock root = OverlayTestTrees.activityTree("""
                        if (true) {
                            int a = 1;
                        }
                """);
        BlockTree.Index index = BlockTree.index(root);
        BodyBlock run = index.methodBody("run()");
        assertNotNull(run);
        BodyBlock thenBody = BlockTree.flatten(run, 0).stream()
                .filter(r -> r.depth() == 1).map(BlockTree.Row::body).findFirst().orElseThrow();

        assertNotSame(run, thenBody);
        assertTrue(BlockTree.containsDescendant(run, thenBody), "the caret may sit in any body of the method");
    }
}
