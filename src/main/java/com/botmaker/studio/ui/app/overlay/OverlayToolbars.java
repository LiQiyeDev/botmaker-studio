package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.capture.NativeControllerFactory;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
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
     * Ask the window manager to stack {@code stage} <em>above fullscreen</em> windows. A JavaFX
     * {@code setAlwaysOnTop} stage still hides behind a fullscreen game (its {@code _NET_WM_STATE_ABOVE} loses
     * to {@code _NET_WM_STATE_FULLSCREEN}); the native layer promotes it via notification window-type + raise.
     *
     * <p>Bridges JavaFX→native by a unique window <b>title</b> (invisible on a transparent stage): we tag the
     * stage, then the native controller finds the matching X11 client window and applies the EWMH hints.
     * Best-effort — a no-op on Windows/Wayland or a WM that ignores the hints. Re-asserted on focus <em>and</em>
     * on a low-frequency timer so it survives a capture-surface toggle or the game re-fullscreening/re-raising
     * itself; the first promotion remaps the window once, later ticks are the cheap raise path (no flicker).
     * Safe to call on any {@link Stage}.
     */
    public static void promoteAboveFullscreen(Stage stage) {
        String existing = stage.getTitle();
        final String title = (existing == null || existing.isEmpty())
                ? "__bm_overlay_" + Long.toHexString(System.nanoTime()) : existing;
        if (existing == null || existing.isEmpty()) stage.setTitle(title);
        Runnable promote = () -> {
            try {
                NativeControllerFactory.get().promoteOverlayAboveFullscreen(title);
            } catch (Throwable ignored) {
                // best-effort; the overlay still shows (just possibly under a fullscreen window)
            }
        };
        // Defer so the native window/title exists, and re-assert whenever the overlay regains focus.
        Platform.runLater(promote);
        stage.focusedProperty().addListener((o, was, now) -> { if (now) promote.run(); });
        // Continuously re-assert while shown — defends against the fullscreen app re-raising itself.
        Timeline keepOnTop = new Timeline(new KeyFrame(javafx.util.Duration.millis(750), e -> promote.run()));
        keepOnTop.setCycleCount(Animation.INDEFINITE);
        keepOnTop.play();
        // Stop when the overlay is no longer showing (an additive listener — won't clobber a caller's onHidden).
        stage.showingProperty().addListener((o, was, showing) -> { if (!showing) keepOnTop.stop(); });
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
