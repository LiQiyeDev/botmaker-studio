package com.botmaker.studio.validation;

import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.parser.factories.UnfilledSlot;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.NullLiteral;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A lightweight, <b>pre-compile</b> validator over the live block tree. Because every block edit already flows
 * through the type-aware pickers (so type-incompatible choices are never offered), the realistic set of
 * authoring errors collapses to a small, structurally-detectable set. This first check flags the most common
 * one — an <b>unfilled slot</b>: a position that needs the name of something in scope and hasn't got one,
 * represented in the AST as a {@link NullLiteral} placeholder (rendered by {@code blocks.expr.NullBlock}). It
 * is deliberately structured so further checks (stale references, missing returns) can be added as new
 * {@code has…}/{@code find…} methods without touching callers.
 *
 * <p><b>Which nulls count is {@link UnfilledSlot}'s question, not this class's.</b> Every {@code NullLiteral}
 * used to count, so a {@code null} written on purpose read as an error — most visibly in the generated
 * {@code FlowDriver.java}, whose {@code return null;} aborted every run from a file the user cannot edit.
 */
public final class BlockValidator {

    private BlockValidator() {}

    /**
     * True when {@code block}'s AST subtree contains an {@link UnfilledSlot} — a switch subject, assignment
     * target or iterated expression left as a {@link NullLiteral}. Used to mark a statement red before it is
     * ever compiled.
     */
    public static boolean hasEmptySlot(CodeBlock block) {
        if (block == null) return false;
        ASTNode node = block.getAstNode();
        if (node == null) return false;
        boolean[] found = {false};
        node.accept(new ASTVisitor() {
            @Override public boolean visit(NullLiteral literal) {
                if (UnfilledSlot.isUnfilled(literal)) found[0] = true;
                return false;
            }
            // Don't descend into a nested body ({ … }) — those statements own their own empty-slot state and
            // are marked on their own rows; a container (if/while/…) is only red for its own direct slots.
            @Override public boolean visit(Block nested) {
                return nested == node;
            }
        });
        return found[0];
    }

    /** Every block in {@code root}'s tree (depth-first) that {@link #hasEmptySlot has an unfilled slot}. */
    public static List<CodeBlock> blocksWithEmptySlots(CodeBlock root) {
        List<CodeBlock> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    /**
     * The blocks that are themselves an unfilled slot — i.e. their AST node is a {@link NullLiteral}
     * placeholder ({@code blocks.expr.NullBlock}) in a position {@link UnfilledSlot} calls unfilled — read from
     * a {@code {ASTNode -> CodeBlock}} registry (e.g. {@code ProjectState.getNodeToBlockMap()}). One entry per
     * slot, so the count is exact and non-duplicating (unlike scanning every enclosing statement, which would
     * match twice).
     */
    public static List<CodeBlock> emptySlots(Map<ASTNode, CodeBlock> registry) {
        if (registry == null) return List.of();
        return registry.entrySet().stream()
                .filter(e -> UnfilledSlot.isUnfilled(e.getKey()))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .toList();
    }

    private static void collect(CodeBlock block, List<CodeBlock> out) {
        if (block == null) return;
        if (hasEmptySlot(block)) out.add(block);
        if (block instanceof BlockWithChildren bwc) {
            for (CodeBlock child : bwc.getChildren()) collect(child, out);
        }
    }
}
