package com.botmaker.core.render;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.render.theme.BlockTheme;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

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
            // Transparent click target spanning the reserved gutter strip: single left-click toggles the
            // breakpoint IDE-style (works even when no circle is showing yet, so it can *add* one).
            Rectangle hitStrip = new Rectangle();
            hitStrip.setManaged(false);
            hitStrip.setFill(Color.TRANSPARENT);
            hitStrip.setLayoutX(existing.getLeft());
            hitStrip.setWidth(gutter);
            hitStrip.heightProperty().bind(pane.heightProperty());
            hitStrip.setCursor(Cursor.HAND);
            if (!block.isReadOnly()) {
                hitStrip.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        block.toggleBreakpoint();
                        e.consume();
                    }
                });
            }
            pane.getChildren().add(hitStrip);

            Circle circle = new Circle(CIRCLE_RADIUS, Color.RED);
            circle.setManaged(false);
            circle.setMouseTransparent(true); // clicks fall through to the hit strip below
            circle.setLayoutX(gutter / 2 + existing.getLeft());
            circle.centerYProperty().bind(pane.heightProperty().divide(2));
            circle.visibleProperty().bind(block.breakpointActiveProperty());
            pane.getChildren().add(circle);

            // Double-click anywhere on the block also toggles (per user request). Attached as a handler so
            // interactive child controls (combo boxes, text fields) that consume the event take priority.
            if (!block.isReadOnly()) {
                node.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        block.toggleBreakpoint();
                        e.consume();
                    }
                });
            }
        }
    }
}
