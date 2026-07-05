package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.core.BodyBlock;

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
