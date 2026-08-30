package com.botmaker.studio.ui.app.capture;

import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.config.CaptureSourceKind;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorProbe;
import com.botmaker.studio.services.capture.DesktopGrab;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Off-thread live-preview + existence probe for a {@link CaptureTargetModel}, shared by the capture dialogs
 * (the visual chooser and the Capture Targets manager) so both show a thumbnail and an "exists / not found"
 * badge from one code path. Grabs are blocking (native enumeration / desktop capture) — call
 * {@link #grab(CaptureTargetModel)} off the FX thread.
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
    public static Result grab(CaptureTargetModel target) {
        try {
            if (target == null) return new Result(null, false);
            if (target.windowTitle() != null) {
                GenericWindow win = findWindow(target.windowTitle());
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
            if (target.is(CaptureSourceKind.MONITOR)) {
                int index = target.monitorIndex();
                if (index >= screens.size()) return new Result(null, false);
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                Rectangle2D b = screens.get(index).getBounds();
                BufferedImage crop = (desktop == null) ? null : DesktopGrab.cropToBounds(desktop, toAwt(b));
                return new Result(crop, true);
            }
            if (target.emulatorName() != null) {
                return grabEmulator(target.emulatorName());
            }
            if (target.isDesktop()) {
                BufferedImage desktop = DesktopGrab.grabVirtualDesktop();
                return new Result(desktop, true);
            }
        } catch (Throwable ignored) {
        }
        return new Result(null, false);
    }

    /**
     * Resolves an emulator instance by name (existence = it is configured and its ADB port answers) and, when
     * running, grabs one {@code screencap} over a short-lived ADB connection.
     */
    private static Result grabEmulator(String instanceName) {
        EmulatorInstance instance = EmulatorInstances.byName(instanceName).orElse(null);
        if (instance == null || !EmulatorProbe.isRunning(instance)) return new Result(null, false);
        // A null image here means configured + running but the grab failed — a different answer from "not
        // configured", which is why the flag is separate from the image.
        return new Result(EmulatorProbe.screencap(instance), true);
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
