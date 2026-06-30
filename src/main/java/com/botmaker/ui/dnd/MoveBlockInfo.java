package com.botmaker.ui.dnd;

import com.botmaker.blocks.ClassBlock;
import com.botmaker.core.BodyBlock;

/**
 * Information about moving an existing block to a new position.
 * @param blockId The ID of the block being moved
 * @param targetBody The BodyBlock where the block should be moved to (null if moving to class root)
 * @param targetClass The ClassBlock where the block should be moved to (null if moving inside a body)
 * @param insertionIndex The index where the block should be inserted
 */
public record MoveBlockInfo(String blockId, BodyBlock targetBody, ClassBlock targetClass, int insertionIndex) {
    // Convenience constructor for backward compatibility (statements)
    public MoveBlockInfo(String blockId, BodyBlock targetBody, int insertionIndex) {
        this(blockId, targetBody, null, insertionIndex);
    }
}