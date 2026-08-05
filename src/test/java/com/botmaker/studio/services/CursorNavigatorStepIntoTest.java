package com.botmaker.studio.services;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the keyboard can reach every branch of a block.
 *
 * <p>Step-into used to take the first direct {@link BodyBlock} child of the focused block. That is one body for
 * an {@code if} and <em>none</em> for a {@code switch} (whose bodies live inside case blocks), so an
 * {@code else}, an {@code else if} and every {@code case} were unreachable without a mouse — and step-out of a
 * case body did nothing at all, because the case block that owns it is not a statement of any body.
 */
class CursorNavigatorStepIntoTest {

    private static AbstractCodeBlock treeOf(String runBody) {
        String source = """
                package com.test.activities;

                public class Mining {
                    public String run() {
                %s        return "NEXT";
                    }
                }
                """.formatted(runBody);
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

    /** The first block of type {@code type} in the tree. */
    private static <T> T find(CodeBlock root, Class<T> type) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (type.isInstance(b)) return type.cast(b);
        }
        return null;
    }

    /** A caret parked on {@code stmt}, wherever it lives. */
    private static InsertionCursor on(CodeBlock root, StatementBlock stmt) {
        for (CodeBlock b : CursorNavigator.collectAll(root)) {
            if (b instanceof BodyBlock body) {
                int i = body.getStatements().indexOf(stmt);
                if (i >= 0) return new InsertionCursor(body, i);
            }
        }
        throw new IllegalStateException("statement is not in the tree");
    }

    @Test
    void stepIntoNextReachesTheElseBranch() {
        AbstractCodeBlock root = treeOf("""
                        if (true) {
                            int a = 1;
                        } else {
                            int b = 2;
                        }
                """);
        var ifBlock = find(root, com.botmaker.studio.blocks.flow.IfBlock.class);
        assertNotNull(ifBlock);
        List<BranchingBlock.Branch> branches = ifBlock.branches();
        BodyBlock thenBody = (BodyBlock) branches.get(0).target();
        BodyBlock elseBody = (BodyBlock) branches.get(1).target();

        InsertionCursor into = CursorNavigator.stepInto(on(root, ifBlock));
        assertSame(thenBody, into.body(), "step-into enters the then body");

        InsertionCursor next = CursorNavigator.stepIntoNext(into, root);
        assertSame(elseBody, next.body(), "the else branch has to be reachable from the then branch");
        assertTrue(CursorNavigator.canStepIntoNext(into, root));

        // And it wraps, so cycling with one key never strands the caret in the last branch.
        assertSame(thenBody, CursorNavigator.stepIntoNext(next, root).body());
    }

    @Test
    void stepIntoEntersASwitchCaseBody() {
        AbstractCodeBlock root = treeOf("""
                        int x = 1;
                        switch (x) {
                            case 1:
                                int a = 1;
                                break;
                            default:
                                int c = 3;
                        }
                """);
        var switchBlock = find(root, com.botmaker.studio.blocks.flow.SwitchBlock.class);
        assertNotNull(switchBlock);
        List<BranchingBlock.Branch> branches = switchBlock.branches();
        assertTrue(branches.size() >= 2, "the fixture has a case and a default");

        InsertionCursor onSwitch = on(root, switchBlock);
        InsertionCursor into = CursorNavigator.stepInto(onSwitch);
        // Previously this returned the cursor unchanged: a switch has no direct BodyBlock child at all.
        assertNotSame(onSwitch.body(), into.body(), "step-into must enter the first case's body");
        assertSame(branches.getFirst().target(), into.body());

        assertSame(branches.get(1).target(), CursorNavigator.stepIntoNext(into, root).body(),
                "the next case has to be reachable");
    }

    @Test
    void stepOutOfACaseBodyLandsAfterTheSwitch() {
        AbstractCodeBlock root = treeOf("""
                        int x = 1;
                        switch (x) {
                            case 1:
                                int a = 1;
                                break;
                        }
                """);
        var switchBlock = find(root, com.botmaker.studio.blocks.flow.SwitchBlock.class);
        assertNotNull(switchBlock);
        InsertionCursor inCase = CursorNavigator.stepInto(on(root, switchBlock));
        InsertionCursor out = CursorNavigator.stepOut(inCase, root);

        // The owner of a case body is the switch, not the case block — which is what made this a no-op.
        assertSame(on(root, switchBlock).body(), out.body(), "step-out returns to the switch's own body");
        assertTrue(CursorNavigator.canStepOut(inCase, root));
    }

    @Test
    void aPlainLoopStillStepsIntoItsOnlyBody() {
        AbstractCodeBlock root = treeOf("""
                        while (true) {
                            int a = 1;
                        }
                """);
        var loop = find(root, com.botmaker.studio.blocks.loop.WhileBlock.class);
        assertNotNull(loop);
        InsertionCursor into = CursorNavigator.stepInto(on(root, loop));
        assertNotSame(on(root, loop).body(), into.body());
        // One body means nothing to cycle to; the move must be a no-op rather than an exception or a wrap.
        assertSame(into.body(), CursorNavigator.stepIntoNext(into, root).body());
    }
}
