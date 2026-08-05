package com.botmaker.studio.core;

import java.util.List;

/**
 * A statement whose control flow splits into several <b>named</b> branches: {@code if/else if/else}, a
 * {@code switch}'s cases, a matches-switch's guards. Implemented by the owner so the branch's <em>label</em>
 * lives with the block that knows it, instead of every consumer re-deriving it with an {@code instanceof} chain.
 *
 * <p>This exists because {@link BlockWithChildren#getChildren()} is not enough to draw a branch. It answers
 * "what is inside this block" in structural order, but not "which of these is the else" — and for two of the
 * three shapes it does not even return the branch bodies directly: an {@code else if} is a nested
 * {@link com.botmaker.studio.blocks.flow.IfBlock}, and a {@code switch}'s children are case blocks. A consumer
 * that walks only the direct {@link BodyBlock} children therefore draws the {@code else} body as if it were a
 * second {@code then}, and misses {@code else if} and {@code case} bodies entirely — which is exactly what the
 * overlay editor's tree did.
 *
 * <p>The main block editor does not use this: it renders each branch through the owner's own
 * {@code createUINode}, where the labels are inline chrome. It is the <em>compact</em> renderers — the overlay
 * HUD's one-line rows, and anything else that flattens the tree to a list — that need the branches as data.
 */
public interface BranchingBlock {

    /**
     * The branches, in the order they execute.
     *
     * @param caption the branch's label ({@code "else"}, {@code "case A:"}, {@code "otherwise"}), or
     *                {@code null} for a branch that needs none because it reads as the owner's own body — the
     *                {@code then} of an {@code if}, which directly follows the condition.
     * @param target  the branch's contents: usually a {@link BodyBlock}, but a {@link BranchingBlock} for an
     *                {@code else if}, whose own branches continue the chain at the same level.
     */
    record Branch(String caption, CodeBlock target) {}

    /** This block's branches. Never null; a branch whose target is absent is simply not listed. */
    List<Branch> branches();
}
