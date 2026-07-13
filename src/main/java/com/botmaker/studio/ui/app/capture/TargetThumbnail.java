package com.botmaker.studio.ui.app.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.services.capture.DesktopGrab;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Off-thread live-preview + existence probe for a {@link CaptureTarget}, shared by the capture dialogs
 * (the visual chooser and the Capture Targets manager) so both show a thumbnail and an "exists / not found"
 * badge from one code path. Grabs are blocking (native enumeration / desktop capture) — call
 * {@link #grab(CaptureTarget)} off the FX thread.
 */
public final class TargetThumbnail {

    private TargetThumbnail() {}

    /** A probe result: the preview {@code image} (may be {@code null} if unavailable) and whether the target exists now. */
    public record Result(BufferedImage image, boolean exists) {}

    /**
     * Probes {@code target}: a window is resolved by title (existence = a matching window is open) and captured;
     * a monitor is cropped from the virtual desktop (existence = the index is still valid); the whole desktop
     * always exists. Never throws — failures yield {@code new Result(null, false/true)}.
     */
    public static Result grab(CaptureTarget target) {
        try {
            if (target instanceof WindowTarget(String titleSubstring)) {
                GenericWindow win = findWindow(titleSubstring);
                if (win == null) return new Result(null, false);
                BufferedImage img = null;
                try {
                    img = NativeControllerFactory.get().captureWindow(win);
                } catch (Throwable ignored) {
                }
                if (img == null || DesktopGrab.looksBlank(img)) {
                    BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                    if (desktop != null) img = DesktopGrab.cropToBounds(desktop, win.getRect());
                }
                return new Result(img, true);
            }

            List<Screen> screens = Screen.getScreens();
            if (target instanceof ScreenTarget(int index)) {
                if (index < 0 || index >= screens.size()) return new Result(null, false);
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                Rectangle2D b = screens.get(index).getBounds();
                BufferedImage crop = (desktop == null) ? null : DesktopGrab.cropToBounds(desktop, toAwt(b));
                return new Result(crop, true);
            }
            if (target instanceof DesktopTarget) {
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                return new Result(desktop, true);
            }
        } catch (Throwable ignored) {
        }
        return new Result(null, false);
    }

    /** First open window (case-insensitive) whose title contains {@code titleSubstring}, or {@code null}. */
    private static GenericWindow findWindow(String titleSubstring) {
        if (titleSubstring == null || titleSubstring.isBlank()) return null;
        String needle = titleSubstring.toLowerCase();
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows(true)) {
                String t = w.getTitle();
                if (t != null && t.toLowerCase().contains(needle)) return w;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Rectangle toAwt(Rectangle2D b) {
        return new Rectangle((int) Math.round(b.getMinX()), (int) Math.round(b.getMinY()),
                (int) Math.round(b.getWidth()), (int) Math.round(b.getHeight()));
    }
}
