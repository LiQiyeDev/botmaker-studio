package com.botmaker.studio.ui.dnd;

import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * Factory for creating drop zones used across different builders
 */
public class DropZoneFactory {

    /**
     * Creates a standard expression drop zone with drag-and-drop handlers
     */
    public static Node createExpressionDropZone(CodeEditorService context) {
        Region dropZone = new Region();
        dropZone.setMinHeight(25);
        dropZone.setMinWidth(50);
        dropZone.setStyle(
                "-fx-background-color: #f0f0f0; " +
                        "-fx-border-color: #c0c0c0; " +
                        "-fx-border-style: dashed;"
        );

        if (context != null && context.getDragAndDropManager() != null) {
            context.getDragAndDropManager().addExpressionDropHandlers(dropZone);
        }

        return dropZone;
    }

    /**
     * Creates a statement drop zone with different styling
     */
    public static Node createStatementDropZone(CodeEditorService context) {
        Region dropZone = new Region();
        dropZone.setMinHeight(30);
        dropZone.setStyle(
                "-fx-background-color: rgba(52, 73, 94, 0.15);" +
                        "-fx-border-color: rgba(52, 73, 94, 0.4);" +
                        "-fx-border-width: 2px 0 2px 0;" +
                        "-fx-border-style: dashed;"
        );

        // Statement drop zones handled differently
        // (usually managed by BodyBlock)

        return dropZone;
    }
}