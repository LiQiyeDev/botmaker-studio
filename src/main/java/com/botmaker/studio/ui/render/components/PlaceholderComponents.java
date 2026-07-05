package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;

import java.util.function.Supplier;

public class PlaceholderComponents {

    /**
     * Returns the UI node for an expression if it exists, otherwise creates a Drop Zone.
     *
     * @param expression The expression block (can be null)
     * @param context The completion context needed to render the block
     * @param dropZoneFactory A supplier to create the drop zone (usually provided by AbstractCodeBlock)
     * @return The Node to display
     */
    public static Node createExpressionOrDropZone(
            ExpressionBlock expression,
            CodeEditorService context,
            Supplier<Node> dropZoneFactory) {

        if (expression != null) {
            return expression.getUINode(context);
        } else {
            return dropZoneFactory.get();
        }
    }
}