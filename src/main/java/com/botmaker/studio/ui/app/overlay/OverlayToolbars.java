package com.botmaker.studio.ui.app.overlay;

import com.botmaker.sdk.internal.plugin.capture.OverlayStage;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Shared factory for the small floating "mini-toolbars" used by the capture-template overlay
 * ({@code capture.OverlayTemplateCapture}); its {@link #installDrag} is also reused to drag the borderless
 * {@link ProgramShapeOverlay} HUD.
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
        // Opted out of ThemedWindows.install(): the bar paints its own pill over the game, and a scene
        // background from the shell's theme would show up as an opaque rectangle around it.
        bar.getStyleClass().add(com.botmaker.studio.ui.render.theme.ThemedWindows.UNTHEMED);
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
        promoteAboveFullscreen(stage);
        return stage;
    }

    /**
     * Ask the window manager to stack {@code stage} <em>above fullscreen</em> windows — see
     * {@link OverlayStage#promoteAboveFullscreen(Stage)}, where the implementation now lives.
     *
     * <p>It moved to the SDK plugin on 2026-08-30 with the two capture surfaces that were its other callers,
     * and Studio's remaining overlays reach it here rather than carrying a second copy of an EWMH trick. The
     * import is temporary in the same sense the capture surfaces' is: {@link ProgramShapeOverlay} and this
     * class's own toolbar are the last two callers, and both leave with the launch pickers.
     */
    public static void promoteAboveFullscreen(Stage stage) {
        OverlayStage.promoteAboveFullscreen(stage);
    }

    /**
     * As {@link #promoteAboveFullscreen(Stage)}, but the periodic re-raise is skipped while {@code enabled}
     * returns false.
     *
     * <p>This exists because the re-assert is what makes two promoted overlays unstackable: the overlay editor's
     * HUD raises itself every 750 ms, so a second window opened <em>from</em> it — its argument-config popover —
     * was shoved back underneath within the second, no matter where it was placed or how it was promoted. The
     * owner it should logically have is not an option either: JavaFX hides owned windows with their owner, and
     * the HUD is deliberately hidden while a capture surface is up, with the popover kept alive to host it.
     * So the HUD stands down instead, for as long as the popover is open.
     */
    public static void promoteAboveFullscreen(Stage stage, java.util.function.BooleanSupplier enabled) {
        OverlayStage.promoteAboveFullscreen(stage, enabled);
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
