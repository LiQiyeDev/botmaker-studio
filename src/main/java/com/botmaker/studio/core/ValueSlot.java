package com.botmaker.studio.core;

import org.eclipse.jdt.core.dom.Expression;

import java.util.function.Supplier;

/**
 * The one value a picker edits — <em>where</em> it lives, with no opinion about what drew it.
 *
 * <p>Every picker under {@code ui.render.components} did exactly two things with the {@link ExpressionBlock}
 * it was handed: read {@code (Expression) ((AbstractCodeBlock) arg).getAstNode()}, and pass that same node to
 * a {@code CodeEditor.replaceWith…} call. That is the whole of the coupling, and it was enough to make the
 * pickers unusable anywhere but the block canvas — which is why the Variables screen grew a second, poorer
 * family of editors ({@code ui.app.params.ValueEditors}) instead of reusing the real ones. This interface is
 * that coupling reduced to what it actually was.
 *
 * <p><b>Resolved on every call, never captured.</b> {@link #at(Supplier)} asks its supplier each time, so a
 * picker whose popup outlives the re-parse its own edit caused reads the <em>new</em> node rather than the
 * one it was built with. A stale node handed to {@code ASTRewrite} throws
 * {@code IllegalArgumentException: Node is not inside the AST} — this makes that class of bug unreachable
 * rather than guarded against. {@link #of(ExpressionBlock)} carries the same guarantee for free: a canvas
 * block is itself rebuilt from the fresh AST on every re-parse.
 *
 * <p>{@link #node()} may be {@code null} — an empty slot, or one whose declaration has since been deleted.
 * Every picker already falls back on an expression it cannot read, so that is not a new case for any of them.
 */
@FunctionalInterface
public interface ValueSlot {

    /** The expression this slot currently holds, or {@code null} when it holds nothing readable. */
    Expression node();

    /**
     * What the slot says, as source — {@code ""} for an empty one. Every picker draws a label off this when it
     * cannot read the value into its own control, and an empty slot is a real state now that a slot need not
     * be a block: {@code int x;} declares a variable with no initializer at all.
     */
    default String source() {
        Expression node = node();
        return node == null ? "" : node.toString();
    }

    /** The slot a block on the canvas draws. */
    static ValueSlot of(ExpressionBlock block) {
        if (block == null) return () -> null;
        return () -> ((AbstractCodeBlock) block).getAstNode() instanceof Expression e ? e : null;
    }

    /** A slot anchored in the AST rather than in a block — a variable initializer, a field, a return value. */
    static ValueSlot at(Supplier<Expression> live) {
        return live == null ? () -> null : live::get;
    }

    /** The empty slot: nothing to read, nothing to write back to. */
    static ValueSlot empty() {
        return () -> null;
    }
}
