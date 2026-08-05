package com.botmaker.studio.services;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
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

    /**
     * A sensible starting caret: the first <em>editable</em> body in the tree that has statements, else the
     * first editable body, else null.
     *
     * <p>Read-only bodies are skipped rather than merely refused later. A generated file's first body is its
     * scaffolding, so without this the caret opens parked somewhere nothing can be inserted, and the authoring
     * toolbar looks broken before the user has done anything.
     */
    public static InsertionCursor defaultCursor(CodeBlock root) {
        BodyBlock firstNonEmpty = null, firstAny = null;
        for (CodeBlock b : collectAll(root)) {
            if (b instanceof BodyBlock bb && !bb.isReadOnly()) {
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

    /** Descends into the first body of the block the caret sits on, if it carries one (if/while/switch/lambda). */
    public static InsertionCursor stepInto(InsertionCursor c) {
        if (c == null) return null;
        List<BodyBlock> bodies = bodiesOf(c.statementAt());
        return bodies.isEmpty() ? c : new InsertionCursor(bodies.getFirst(), 0);
    }

    /**
     * Moves the caret to the <em>next branch</em> of the block whose branch it currently sits in, wrapping at
     * the last — {@code then} → {@code else}, {@code case A} → {@code case B} → {@code default}.
     *
     * <p>Without this a branch other than the first is keyboard-unreachable: {@link #stepInto} enters the first
     * body and there is no move that crosses to a sibling one, so an {@code else} could only be reached by
     * clicking it. Which branch is "current" is resolved by finding the owner that lists the caret's body.
     */
    public static InsertionCursor stepIntoNext(InsertionCursor c, CodeBlock root) {
        if (c == null) return null;
        for (CodeBlock b : collectAll(root)) {
            List<BodyBlock> bodies = bodiesOf(b);
            int i = indexOfIdentity(bodies, c.body());
            if (i < 0 || bodies.size() < 2) continue;
            return new InsertionCursor(bodies.get((i + 1) % bodies.size()), 0);
        }
        return c;
    }

    /** True when the caret's body is one of several branches of the same owner, so cycling goes somewhere. */
    public static boolean canStepIntoNext(InsertionCursor c, CodeBlock root) {
        return c != null && !stepIntoNext(c, root).equals(c);
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
        return c != null && !bodiesOf(c.statementAt()).isEmpty();
    }

    /** True when step-out actually lands somewhere (the current body is nested inside a parent body). */
    public static boolean canStepOut(InsertionCursor c, CodeBlock root) {
        return c != null && !stepOut(c, root).equals(c);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every body {@code block} owns, in execution order.
     *
     * <p>Not just its direct {@link BodyBlock} children: a {@code switch}'s bodies sit inside its case blocks
     * and an {@code else if}'s inside the chained {@code if}, so scanning children alone found <em>one</em>
     * body for an {@code if} and <em>none</em> for a {@code switch} — which is why step-into could never reach
     * an {@code else} or a {@code case}. {@link BranchingBlock} is the block's own answer to this.
     */
    private static List<BodyBlock> bodiesOf(CodeBlock block) {
        List<BodyBlock> out = new ArrayList<>();
        collectBodies(block, out);
        return out;
    }

    private static void collectBodies(CodeBlock block, List<BodyBlock> out) {
        if (block instanceof BranchingBlock branching) {
            for (BranchingBlock.Branch branch : branching.branches()) {
                if (branch.target() instanceof BodyBlock bb) out.add(bb);
                else collectBodies(branch.target(), out);   // an `else if` continues the same chain of branches
            }
            return;
        }
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) {
                if (child instanceof BodyBlock bb) out.add(bb);
            }
        }
    }

    /** {@code list}'s position holding exactly {@code target} — blocks have no value equality. */
    private static int indexOfIdentity(List<BodyBlock> list, BodyBlock target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    /**
     * The statement block {@code body} belongs to — resolved through {@link #bodiesOf}, so stepping out of a
     * {@code case} body lands after the whole {@code switch} rather than nowhere: the case body's structural
     * parent is a case block, which is not itself a statement of any body, so a direct-children scan found no
     * owner and step-out silently did nothing.
     */
    private static StatementBlock findOwner(BodyBlock body, List<CodeBlock> all) {
        for (CodeBlock b : all) {
            if (b == body || !(b instanceof StatementBlock sb)) continue;
            if (indexOfIdentity(bodiesOf(b), body) >= 0) return sb;
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
