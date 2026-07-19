package com.botmaker.studio.ui.app.capture;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.studio.emulator.EmulatorInstanceScanner;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.EmulatorTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;
import com.botmaker.studio.services.capture.DesktopGrab;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.Socket;
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
            if (target instanceof EmulatorTarget(String instanceName)) {
                return grabEmulator(instanceName);
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
        if (instanceName == null || instanceName.isBlank()) return new Result(null, false);
        EmulatorInstance instance = null;
        for (EmulatorInstance i : new EmulatorInstanceScanner().instances()) {
            if (instanceName.equals(i.name())) { instance = i; break; }
        }
        if (instance == null) return new Result(null, false);
        if (!emulatorRunning(instance)) return new Result(null, false);
        AdbDevice device = null;
        try {
            device = AdbDevice.connect(instance.host(), instance.adbPort());
            return new Result(device.screencap(), true);
        } catch (Throwable t) {
            return new Result(null, true); // configured + running, but the grab failed
        } finally {
            if (device != null) {
                try { device.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
    }

    /** A quick TCP liveness probe of the instance's ADB port. */
    private static boolean emulatorRunning(EmulatorInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.host(), instance.adbPort()), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
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
