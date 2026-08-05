package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.services.CursorNavigator;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Where the overlay parks its caret inside an activity. Every case here is one the user would experience as
 * "recording did nothing": the caret lands somewhere an insert is dropped, or below a {@code return} where the
 * inserted block never runs. Neither reports anything, which is why they are pinned down here.
 */
class ProgramShapeOverlayCursorTest {

    private static AbstractCodeBlock treeOf(String source) {
        return OverlayTestTrees.treeOf(source);
    }

    private static BodyBlock bodyOf(CodeBlock root, String name) {
        return OverlayTestTrees.bodyOf(root, name);
    }

    private static String activity(String runBody) {
        return OverlayTestTrees.activity(runBody);
    }

    @Test
    void aFreshStubParksAboveItsLoneReturn() {
        // The generated stub is `return Outcome.NEXT;` and nothing else. Anything inserted below that return is
        // unreachable — so the caret has to sit above it, which is index -1.
        AbstractCodeBlock root = treeOf(activity("        return \"NEXT\";\n"));
        BodyBlock run = bodyOf(root, "run");
        assertNotNull(run);

        InsertionCursor c = BlockTree.runCursor(root);
        assertNotNull(c);
        assertSame(run, c.body(), "the caret belongs in run(), not isEnabled()");
        assertEquals(-1, c.index());
    }

    @Test
    void anActivityWithWorkParksOnTheStatementBeforeTheReturn() {
        // Inserts go *below* the caret, so "before the return" means "on the last statement that isn't it".
        AbstractCodeBlock root = treeOf(activity("""
                        int a = 1;
                        int b = 2;
                        return "NEXT";
                """));
        InsertionCursor c = BlockTree.runCursor(root);
        assertNotNull(c);
        assertEquals(1, c.index(), "below index 1 is above the return at index 2");
    }

    @Test
    void aRunThatDoesNotEndInAReturnParksAtItsEnd() {
        AbstractCodeBlock root = treeOf(activity("""
                        int a = 1;
                        int b = 2;
                """));
        InsertionCursor c = BlockTree.runCursor(root);
        assertNotNull(c);
        assertEquals(1, c.index(), "below index 1 is the end of the body");
    }

    @Test
    void aStubWhoseRunWasRenamedFallsBackToTheDefaultCursor() {
        // Not a shape we generate, but a file the user owns and may have edited. Falling back beats returning
        // null, which would silently disable every insert.
        String source = """
                package com.test.activities;

                public class Mining {
                    public String doTheThing() {
                        int a = 1;
                        return "NEXT";
                    }
                }
                """;
        AbstractCodeBlock root = treeOf(source);
        InsertionCursor c = BlockTree.runCursor(root);
        assertNotNull(c);
        assertEquals(CursorNavigator.defaultCursor(root), c);
    }
}
