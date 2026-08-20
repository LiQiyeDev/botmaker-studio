package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * Turns a rendered expression into a slot — a place a value can be dropped, and taken back out of. Every place
 * an expression is shown is one, whether or not it was built through {@link SentenceLayoutBuilder} — the
 * operands of a binary expression and a declaration's initialiser go straight into an {@code HBox}, and were
 * silently the only expressions in the editor that refused a drop.
 *
 * <p>Read-only slots are left alone, the same rule the rest of the block layer follows: a locked block offers
 * no interaction at all rather than one that is refused after the fact.
 */
public final class ExpressionSlots {

    private ExpressionSlots() {}

    public static void makeDroppable(Node slotNode, ExpressionBlock expression,
                                     CodeEditorService context, ResolvedType expectedType) {
        if (!(slotNode instanceof Region region)) return;
        if (expression == null || expression.isReadOnly()) return;
        if (context == null || context.getDragAndDropManager() == null) return;
        context.getDragAndDropManager().addExpressionDropHandlers(region, expression, expectedType);
        // The same node is the source as well as the target: a slot the user may drop into is a slot they may
        // change their mind about, and until now the only way back out of one was to delete the value.
        context.getDragAndDropManager().makeExpressionMovable(region, expression);
    }
}
