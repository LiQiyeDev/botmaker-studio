package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CursorNavigator;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Where the overlay parks its caret inside an activity. Every case here is one the user would experience as
 * "recording did nothing": the caret lands somewhere an insert is dropped, or below a {@code return} where the
 * inserted block never runs. Neither reports anything, which is why they are pinned down here.
 */
class ProgramShapeOverlayCursorTest {

    /** The block tree for {@code source}, built through the real converter (no JavaFX toolkit needed). */
    private static AbstractCodeBlock treeOf(String source) {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Mining.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, source));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                new BlockConverter(null, state), state, source,
                new BlockDragAndDropManager(new EventBus(false)), false, false);
        assertNotNull(result.root(), "converter should produce a root block");
        return result.root();
    }

    /** The body of the method named {@code name}. */
    private static BodyBlock bodyOf(CodeBlock root, String name) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof com.botmaker.studio.blocks.func.MethodDeclarationBlock m
                    && name.equals(m.getMethodName())) {
                for (CodeBlock child : m.getChildren()) {
                    if (child instanceof BodyBlock body) return body;
                }
            }
        }
        return null;
    }

    private static String activity(String runBody) {
        return """
                package com.test.activities;

                public class Mining {
                    public boolean isEnabled() {
                        return true;
                    }

                    public String run() {
                %s    }
                }
                """.formatted(runBody);
    }

    @Test
    void aFreshStubParksAboveItsLoneReturn() {
        // The generated stub is `return Outcome.NEXT;` and nothing else. Anything inserted below that return is
        // unreachable — so the caret has to sit above it, which is index -1.
        AbstractCodeBlock root = treeOf(activity("        return \"NEXT\";\n"));
        BodyBlock run = bodyOf(root, "run");
        assertNotNull(run);

        InsertionCursor c = ProgramShapeOverlay.runCursor(root);
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
        InsertionCursor c = ProgramShapeOverlay.runCursor(root);
        assertNotNull(c);
        assertEquals(1, c.index(), "below index 1 is above the return at index 2");
    }

    @Test
    void aRunThatDoesNotEndInAReturnParksAtItsEnd() {
        AbstractCodeBlock root = treeOf(activity("""
                        int a = 1;
                        int b = 2;
                """));
        InsertionCursor c = ProgramShapeOverlay.runCursor(root);
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
        InsertionCursor c = ProgramShapeOverlay.runCursor(root);
        assertNotNull(c);
        assertEquals(CursorNavigator.defaultCursor(root), c);
    }
}
