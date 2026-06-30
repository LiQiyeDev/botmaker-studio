package com.botmaker.ui.dnd;

import com.botmaker.palette.BlockType;
import com.botmaker.blocks.ClassBlock;
import com.botmaker.core.BodyBlock;

public record DropInfo(
        BlockType type,
        BodyBlock targetBody,
        int insertionIndex,
        ClassBlock targetClass // set for class-member drops (method / enum)
) {
    // Convenience constructor for statement drops (targetClass == null)
    public DropInfo(BlockType type, BodyBlock targetBody, int insertionIndex) {
        this(type, targetBody, insertionIndex, null);
    }
}
