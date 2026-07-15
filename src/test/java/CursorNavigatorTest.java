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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link CursorNavigator} step / step-into / step-out semantics that drive the overlay authoring
 * caret (Phase 2B). Builds a real block tree with a nested {@code if} body via {@link BlockConverter}, so the
 * traversal (owner/parent lookup, child-body descent) is exercised end-to-end without the JavaFX toolkit.
 */
public class CursorNavigatorTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public void run() {
                    int a = 1;
                    if (a > 0) {
                        int b = 2;
                    }
                    int c = 3;
                }
            }
            """;

    private AbstractCodeBlock root;
    private BodyBlock methodBody;   // [decl a, if, decl c]
    private BodyBlock ifBody;       // [decl b]

    @BeforeEach
    void setUp() {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, SOURCE));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());

        BlockConverter converter = new BlockConverter(null, state);
        BlockConverter.ConvertResult result = converter.convert(
                SOURCE, state.getMutableNodeToBlockMap(),
                new BlockDragAndDropManager(new EventBus(false)), false, false);
        root = result.root();
        assertNotNull(root, "converter should produce a root block");

        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof BodyBlock bb) {
                if (bb.getStatements().size() == 3) methodBody = bb;
                else if (bb.getStatements().size() == 1) ifBody = bb;
            }
        }
        assertNotNull(methodBody, "should find the 3-statement method body");
        assertNotNull(ifBody, "should find the 1-statement if body");
    }

    @Test
    void defaultCursorSeedsFirstNonEmptyBody() {
        InsertionCursor c = CursorNavigator.defaultCursor(root);
        assertNotNull(c);
        assertSame(methodBody, c.body());
        assertEquals(0, c.index());
    }

    @Test
    void stepOverAdvancesAndClampsAtEnd() {
        InsertionCursor c = new InsertionCursor(methodBody, 0);
        c = CursorNavigator.stepOver(c);
        assertEquals(1, c.index());
        c = CursorNavigator.stepOver(CursorNavigator.stepOver(c)); // 2 then clamp
        assertEquals(3, c.index());
        assertEquals(3, CursorNavigator.stepOver(c).index(), "clamped at body size");
    }

    @Test
    void stepIntoDescendsOnlyWhenBlockHasBody() {
        // index 0 is the "int a = 1;" declaration — no child body, so no-op.
        assertFalse(CursorNavigator.canStepInto(new InsertionCursor(methodBody, 0)));
        assertSame(methodBody, CursorNavigator.stepInto(new InsertionCursor(methodBody, 0)).body());

        // index 1 is the if — step-into lands at the start of its body.
        InsertionCursor atIf = new InsertionCursor(methodBody, 1);
        assertTrue(CursorNavigator.canStepInto(atIf));
        InsertionCursor inside = CursorNavigator.stepInto(atIf);
        assertSame(ifBody, inside.body());
        assertEquals(0, inside.index());
    }

    @Test
    void stepOutReturnsToOwnersSlot() {
        assertTrue(CursorNavigator.canStepOut(new InsertionCursor(ifBody, 0), root));
        InsertionCursor out = CursorNavigator.stepOut(new InsertionCursor(ifBody, 0), root);
        assertSame(methodBody, out.body());
        assertEquals(1, out.index(), "focus returns to the if's slot in the method body");

        // The top-level method body has no owner block, so step-out is a no-op there.
        assertFalse(CursorNavigator.canStepOut(new InsertionCursor(methodBody, 0), root));
    }
}
