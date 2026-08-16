package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.palette.BlockType;

/**
 * A block dropped onto an <em>expression slot</em> — the {@code ⟨expression⟩} of a print, an {@code if}
 * condition, a call argument. Exactly one of {@link #paletteType()} / {@link #sourceBlockId()} is set, mirroring
 * the two dragboard formats {@link BlockDragAndDropManager} carries.
 *
 * <p>Only ids and palette data cross this boundary, never AST nodes: the drag manager holds no reference to the
 * service layer, so resolving {@code targetBlockId} back to the expression being replaced (and
 * {@code sourceBlockId} to the statement being consumed) is {@code CodeEditorService}'s job — the same split
 * {@link MoveBlockInfo} already makes.
 *
 * @param targetBlockId the id of the {@code ExpressionBlock} occupying the slot; it is what gets replaced
 * @param paletteType   the palette block dropped, or null for an existing-block drop
 * @param sourceBlockId the id of the existing statement dropped, or null for a palette drop
 */
public record ExpressionDropInfo(String targetBlockId, BlockType paletteType, String sourceBlockId) {

    public static ExpressionDropInfo fromPalette(String targetBlockId, BlockType type) {
        return new ExpressionDropInfo(targetBlockId, type, null);
    }

    public static ExpressionDropInfo fromExistingBlock(String targetBlockId, String sourceBlockId) {
        return new ExpressionDropInfo(targetBlockId, null, sourceBlockId);
    }
}
