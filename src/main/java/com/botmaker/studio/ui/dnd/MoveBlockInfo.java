package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.core.BodyBlock;

/**
 * Information about moving an existing block to a new position.
 * @param blockId The ID of the block being moved
 * @param targetBody The BodyBlock where the block should be moved to (null if moving to class root)
 * @param targetClass The ClassBlock where the block should be moved to (null if moving inside a body)
 * @param insertionIndex The index where the block should be inserted
 * @param expressionPayload Whether {@code blockId} names a value being dragged out of a slot, which becomes a
 *                       line of its own here, rather than a line being reordered. The distinction is the
 *                       dragboard's to make — a call line and the call inside a slot are drawn by the same
 *                       block class, so the block alone cannot say which of the two the user grabbed.
 */
public record MoveBlockInfo(String blockId, BodyBlock targetBody, ClassBlock targetClass, int insertionIndex,
                            boolean expressionPayload) {
    // Convenience constructor for backward compatibility (statements)
    public MoveBlockInfo(String blockId, BodyBlock targetBody, int insertionIndex) {
        this(blockId, targetBody, null, insertionIndex, false);
    }

    public MoveBlockInfo(String blockId, BodyBlock targetBody, ClassBlock targetClass, int insertionIndex) {
        this(blockId, targetBody, targetClass, insertionIndex, false);
    }

    /** A value lifted out of an expression slot, landing in {@code targetBody} as {@code value;}. */
    public static MoveBlockInfo expression(String blockId, BodyBlock targetBody, int insertionIndex) {
        return new MoveBlockInfo(blockId, targetBody, null, insertionIndex, true);
    }
}
