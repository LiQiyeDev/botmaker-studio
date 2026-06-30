package com.botmaker.core.render;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.theme.BlockTheme;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Reserves a left gutter on the block and, when the node can host children, adds the floating
 * breakpoint circle whose visibility tracks the block's breakpoint state.
 */
public final class GutterDecorator implements BlockDecorator {

    private static final double CIRCLE_RADIUS = 4.0;

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (!(node instanceof Region region)) return;

        double gutter = BlockTheme.current().spacing().gutter();
        Insets existing = region.getPadding();
        region.setPadding(new Insets(
                existing.getTop(),
                existing.getRight(),
                existing.getBottom(),
                existing.getLeft() + gutter
        ));

        if (node instanceof Pane pane) {
            Circle circle = new Circle(CIRCLE_RADIUS, Color.RED);
            circle.setManaged(false);
            circle.setLayoutX(gutter / 2 + existing.getLeft());
            circle.centerYProperty().bind(pane.heightProperty().divide(2));
            circle.visibleProperty().bind(block.breakpointActiveProperty());
            pane.getChildren().add(circle);
        }
    }
}
