package com.botmaker.studio.ui.app.overlay;

import com.botmaker.shared.capture.NativeControllerFactory;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;

/**
 * What Studio's remaining overlays need of a borderless HUD: keeping it above a fullscreen game, and dragging
 * it by a handle.
 *
 * <h2>Why this is a second copy, and why that is the right answer</h2>
 *
 * <p>The same two methods exist in the SDK plugin, as {@code OverlayStage}, and from 2026-08-30 to 2026-09-01
 * this class was a three-line delegation to them — the last Studio source that named a
 * {@code com.botmaker.sdk.internal} type, which is the editor reaching into a plugin's private package and is
 * worse than any duplication. So the implementation came back.
 *
 * <p>There is no third home for it, and the reason is worth stating so nobody spends an afternoon looking for
 * one. The promotion is {@code botmaker-shared}'s {@link NativeControllerFactory} plus a JavaFX {@link Stage},
 * and <b>shared has no JavaFX</b>; the plugin toolkit has JavaFX but <b>may name no BotMaker upstream except
 * the contract</b>, so it cannot see shared; and the contract itself fails its own host-only test, because any
 * plugin can depend on shared and do this for itself. Two modules own overlays, so two modules own ~40 lines
 * of EWMH.
 *
 * <p>What that costs is a fix applied twice, and the mitigation is that this is finished code: an X11 window
 * hint that has not changed since it was written. If it ever does change, both copies say so in a comment.
 */
public final class OverlayToolbars {

    private OverlayToolbars() {}

    /**
     * Ask the window manager to stack {@code stage} <em>above fullscreen</em> windows.
     *
     * <p>A JavaFX {@code setAlwaysOnTop} stage still hides behind a fullscreen game — its
     * {@code _NET_WM_STATE_ABOVE} loses to {@code _NET_WM_STATE_FULLSCREEN} — so the native layer promotes it
     * with a notification window-type plus a raise.
     *
     * <p>It bridges JavaFX to native by a unique window <b>title</b>, invisible on a transparent stage: the
     * stage is tagged, and the native controller finds the matching X11 client window by that title. It is
     * best-effort — a no-op on Windows, on Wayland, and on any WM that ignores the hints — and it is
     * re-asserted both on focus and on a low-frequency timer, so it survives the game re-fullscreening or
     * re-raising itself. The first promotion remaps the window once; later ticks take the cheap raise path and
     * do not flicker.
     */
    public static void promoteAboveFullscreen(Stage stage) {
        promoteAboveFullscreen(stage, () -> true);
    }

    /**
     * As {@link #promoteAboveFullscreen(Stage)}, but the periodic re-raise is skipped while {@code enabled}
     * returns false.
     *
     * <p>This exists because the re-assert is what makes two promoted overlays unstackable: the overlay
     * editor's HUD raises itself every 750 ms, so a second window opened <em>from</em> it — its
     * argument-config popover — was shoved back underneath within the second, no matter where it was placed or
     * how it was promoted. The owner it should logically have is not an option either: JavaFX hides owned
     * windows with their owner, and the HUD is deliberately hidden while a capture surface is up, with the
     * popover kept alive to host it. So the HUD stands down instead, for as long as the popover is open.
     */
    public static void promoteAboveFullscreen(Stage stage, BooleanSupplier enabled) {
        String existing = stage.getTitle();
        final String title = (existing == null || existing.isEmpty())
                ? "__bm_overlay_" + Long.toHexString(System.nanoTime()) : existing;
        if (existing == null || existing.isEmpty()) stage.setTitle(title);
        Runnable promote = () -> {
            try {
                NativeControllerFactory.get().promoteOverlayAboveFullscreen(title);
            } catch (Throwable ignored) {
                // best-effort; the overlay still shows, just possibly under a fullscreen window
            }
        };
        // Deferred so the native window and its title exist, and re-asserted whenever the overlay regains focus.
        Platform.runLater(promote);
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now) promote.run();
        });
        // Continuously re-assert while shown — this is what defends against the game re-raising itself.
        Timeline keepOnTop = new Timeline(new KeyFrame(javafx.util.Duration.millis(750), e -> {
            if (enabled.getAsBoolean()) promote.run();
        }));
        keepOnTop.setCycleCount(Animation.INDEFINITE);
        keepOnTop.play();
        // Stop once the overlay is no longer showing. An additive listener, so a caller's own onHidden stands.
        stage.showingProperty().addListener((o, was, showing) -> {
            if (!showing) keepOnTop.stop();
        });
    }

    /** Makes dragging on {@code handle} move {@code stage}, tracking the press offset from the stage origin. */
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
