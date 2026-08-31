package com.botmaker.studio.ui.app.overlay;

import com.botmaker.sdk.internal.plugin.capture.OverlayStage;
import javafx.scene.Node;
import javafx.stage.Stage;

/**
 * What Studio's remaining overlays still reach for: dragging a borderless HUD, and staying above a
 * fullscreen game. Both implementations are the SDK plugin's now; this is the two-line seam.
 *
 * <p>Its {@code show} — the mini-toolbar factory — went with {@code OverlayTemplateCapture} on 2026-08-31
 * and is {@link OverlayStage#bar}. Its one caller was the capture tool, which is a plugin's toolbar item
 * now; the {@link ProgramShapeOverlay} HUD builds its own stage and only ever wanted the drag.
 */
public final class OverlayToolbars {

    private OverlayToolbars() {}

    /**
     * Ask the window manager to stack {@code stage} <em>above fullscreen</em> windows — see
     * {@link OverlayStage#promoteAboveFullscreen(Stage)}, where the implementation now lives.
     *
     * <p>It moved to the SDK plugin on 2026-08-30 with the two capture surfaces that were its other callers,
     * and Studio's remaining overlays reach it here rather than carrying a second copy of an EWMH trick. The
     * import is temporary in the same sense the capture surfaces' was: {@link ProgramShapeOverlay} and its
     * argument popover are the last two callers, and both leave with the launch pickers.
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

    /**
     * Makes dragging on {@code handle} move {@code stage} — see {@link OverlayStage#installDrag}, where the
     * implementation went with the mini-toolbar that was its other caller.
     */
    public static void installDrag(Node handle, Stage stage) {
        OverlayStage.installDrag(handle, stage);
    }
}
