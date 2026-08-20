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
 * <p>An <b>empty</b> slot is the same drop with a different target: there is no expression block to name, so
 * {@code targetBlockId} is the <em>owning statement</em>'s and {@code emptySlot} says which of the two the
 * service should look for. One event either way — the alternative was a second event carrying the same three
 * fields, and two paths that would have drifted the first time one of them learned something.
 *
 * @param targetBlockId the id of the {@code ExpressionBlock} occupying the slot — it is what gets replaced —
 *                      or, when {@code emptySlot}, of the statement whose slot is empty
 * @param paletteType   the palette block dropped, or null for an existing-block drop
 * @param sourceBlockId the id of the existing statement dropped, or null for a palette drop
 * @param emptySlot     whether the slot holds nothing, so the value is placed rather than substituted
 * @param sourceIsExpression whether {@code sourceBlockId} names a value dragged out of another slot rather
 *                      than a whole line — the two are consumed differently, and confusing them is expensive:
 *                      an argument's enclosing line is the whole call, so reading a slot-to-slot move as a line
 *                      move would carry away everything around the value the user picked up
 */
public record ExpressionDropInfo(String targetBlockId, BlockType paletteType, String sourceBlockId,
                                 boolean emptySlot, boolean sourceIsExpression) {

    public static ExpressionDropInfo fromPalette(String targetBlockId, BlockType type) {
        return new ExpressionDropInfo(targetBlockId, type, null, false, false);
    }

    public static ExpressionDropInfo fromExistingBlock(String targetBlockId, String sourceBlockId,
                                                       boolean sourceIsExpression) {
        return new ExpressionDropInfo(targetBlockId, null, sourceBlockId, false, sourceIsExpression);
    }

    /** The same drop onto a slot that holds nothing; {@code ownerBlockId} is the statement around it. */
    public static ExpressionDropInfo intoEmptySlot(String ownerBlockId, BlockType type, String sourceBlockId,
                                                   boolean sourceIsExpression) {
        return new ExpressionDropInfo(ownerBlockId, type, sourceBlockId, true, sourceIsExpression);
    }
}
