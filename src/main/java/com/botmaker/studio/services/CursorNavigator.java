package com.botmaker.studio.services;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.project.InsertionCursor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure navigation for the {@link InsertionCursor} — the "step / step-into / step-out" moves that drive the
 * overlay authoring toolbar (Phase 2B). Every method takes the current cursor plus the block tree {@code root}
 * and returns the <em>new</em> cursor (or the same one when the move isn't possible), leaving all mutation to
 * the caller. Blocks carry no parent pointers, so ascent is resolved by scanning the tree (mirrors
 * {@code CodeEditorService.findParentBody}).
 */
public final class CursorNavigator {

    private CursorNavigator() {}

    /** A sensible starting caret: the first body in the tree that has statements, else the first body, else null. */
    public static InsertionCursor defaultCursor(CodeBlock root) {
        BodyBlock firstNonEmpty = null, firstAny = null;
        for (CodeBlock b : collectAll(root)) {
            if (b instanceof BodyBlock bb) {
                if (firstAny == null) firstAny = bb;
                if (firstNonEmpty == null && !bb.getStatements().isEmpty()) firstNonEmpty = bb;
            }
        }
        BodyBlock body = firstNonEmpty != null ? firstNonEmpty : firstAny;
        return body == null ? null : new InsertionCursor(body, 0);
    }

    /** Moves the caret down one slot within its own body (clamped to the end). */
    public static InsertionCursor stepOver(InsertionCursor c) {
        if (c == null) return null;
        int max = c.body().getStatements().size();
        return new InsertionCursor(c.body(), Math.min(c.index() + 1, max));
    }

    /** Moves the caret up one slot within its own body (clamped to the start). */
    public static InsertionCursor stepBack(InsertionCursor c) {
        if (c == null) return null;
        return new InsertionCursor(c.body(), Math.max(c.index() - 1, 0));
    }

    /** Descends into the body of the block the caret sits on, if that block carries one (if/while/for/lambda). */
    public static InsertionCursor stepInto(InsertionCursor c) {
        if (c == null) return null;
        StatementBlock at = c.statementAt();
        BodyBlock childBody = firstChildBody(at);
        return childBody != null ? new InsertionCursor(childBody, 0) : c;
    }

    /** Ascends to the slot just after the block that owns the current body; no-op at the top-level body. */
    public static InsertionCursor stepOut(InsertionCursor c, CodeBlock root) {
        if (c == null) return null;
        List<CodeBlock> all = collectAll(root);
        StatementBlock owner = findOwner(c.body(), all);
        if (owner == null) return c;
        BodyBlock parent = findParentBody(owner, all);
        if (parent == null) return c;
        int idx = parent.getStatements().indexOf(owner);
        return new InsertionCursor(parent, idx < 0 ? parent.getStatements().size() : idx);
    }

    /** True when the caret sits on a block that can be entered (has a child body). */
    public static boolean canStepInto(InsertionCursor c) {
        return c != null && firstChildBody(c.statementAt()) != null;
    }

    /** True when step-out actually lands somewhere (the current body is nested inside a parent body). */
    public static boolean canStepOut(InsertionCursor c, CodeBlock root) {
        return c != null && !stepOut(c, root).equals(c);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────

    private static BodyBlock firstChildBody(CodeBlock block) {
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                if (child instanceof BodyBlock bb) return bb;
            }
        }
        return null;
    }

    /** The statement block whose direct children include {@code body}. */
    private static StatementBlock findOwner(BodyBlock body, List<CodeBlock> all) {
        for (CodeBlock b : all) {
            if (b == body) continue;
            if (b instanceof StatementBlock sb && b instanceof BlockWithChildren bwc
                    && bwc.getChildren().contains(body)) {
                return sb;
            }
        }
        return null;
    }

    private static BodyBlock findParentBody(StatementBlock target, List<CodeBlock> all) {
        for (CodeBlock b : all) {
            if (b instanceof BodyBlock bb && bb.getStatements().contains(target)) return bb;
        }
        return null;
    }

    /** Depth-first collection of every block reachable from {@code root} (including {@code root}). */
    public static List<CodeBlock> collectAll(CodeBlock root) {
        List<CodeBlock> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(CodeBlock block, List<CodeBlock> out) {
        if (block == null) return;
        out.add(block);
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) collect(child, out);
        }
    }
}
