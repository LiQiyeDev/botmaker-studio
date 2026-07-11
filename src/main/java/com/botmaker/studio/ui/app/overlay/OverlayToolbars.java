package com.botmaker.studio.ui.app.overlay;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Shared factory for the small floating "mini-toolbars" used by the capture-template overlay
 * ({@code capture.OverlayTemplateCapture}) and the macro recorder ({@code record.MacroRecorderToolbar}).
 *
 * <p>Centralises three behaviours that both toolbars need identically:
 * <ul>
 *   <li><b>Draggable</b> — press-and-drag anywhere on the bar body moves the window (buttons still click,
 *       since {@code ButtonBase} consumes its own mouse-press so the drag only starts from the bar/label).</li>
 *   <li><b>Not owned by the main window</b> — the stage is deliberately <em>not</em> {@code initOwner}'d to the
 *       Studio primary stage, so the user can minimize Studio without the overlay disappearing with it.</li>
 *   <li><b>Always-on-top, transparent</b>, positioned just above the target window's top edge.</li>
 * </ul>
 */
public final class OverlayToolbars {

    private OverlayToolbars() {}

    /**
     * Wraps {@code bar} in a transparent, always-on-top, draggable stage positioned just above
     * {@code windowBounds} (or tucked inside the top when there's no room above), shows it, and returns it.
     * The returned stage is intentionally ownerless.
     */
    public static Stage show(HBox bar, java.awt.Rectangle windowBounds) {
        Scene scene = new Scene(bar, Color.TRANSPARENT);
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);
        installDrag(bar, stage);
        stage.show();
        stage.sizeToScene();
        double barHeight = stage.getHeight();
        stage.setX(windowBounds.x);
        stage.setY(windowBounds.y - barHeight - 4 >= 0 ? windowBounds.y - barHeight - 4 : windowBounds.y + 4);
        return stage;
    }

    /** Makes dragging on {@code handle} move {@code stage} (tracks the press offset from the stage origin). */
    public static void installDrag(Node handle, Stage stage) {
        final double[] offset = new double[2];
        handle.setOnMousePressed(e -> {
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
    }
}
