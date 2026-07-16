package com.botmaker.studio.project;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.StatementBlock;

/**
 * An <em>insertion caret</em> into the block tree: a position <em>inside a {@link BodyBlock}</em>, before the
 * statement at {@code index} (with {@code index == body.getStatements().size()} meaning "at the very end").
 *
 * <p>Unlike {@link ProjectState}'s {@code highlightedBlock} (which marks a <em>selected</em> block), the cursor
 * marks <em>where the next block will be inserted</em>. It is the anchor the overlay authoring toolbar's
 * step / step-into / step-out buttons move around, and where its "add below" / palette inserts write to
 * (see {@code CursorNavigator} and {@code ProgramShapeOverlay}).
 *
 * @param body  the body the caret sits in
 * @param index the slot before {@code statements[index]} ({@code == size} → end of body)
 */
public record InsertionCursor(BodyBlock body, int index) {

    /** The statement the caret currently sits <em>on</em> (the one at {@code index}), or {@code null} at the end. */
    public StatementBlock statementAt() {
        var stmts = body.getStatements();
        return index >= 0 && index < stmts.size() ? stmts.get(index) : null;
    }
}
