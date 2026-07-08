package com.botmaker.studio.services.capture;

import javax.imageio.ImageIO;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Prompt-free full-desktop screenshot, shared by editor-time capture paths (the image-crop overlay and the
 * capture-source picker's monitor thumbnails).
 *
 * <p>The key property on Fedora/GNOME-Wayland: AWT {@link Robot} screen grabs re-trigger the
 * xdg-desktop-portal share picker on <em>every</em> call, so using {@code Robot} for N monitor thumbnails
 * means N OS confirmation dialogs. Under Wayland we instead shell out to an installed screenshot CLI
 * ({@code grim} / {@code gnome-screenshot} / {@code spectacle}), which grabs the whole desktop in one shot
 * with no picker. On X11/Windows {@code Robot} is fine and needs no external tool.
 */
public final class DesktopGrab {

    private DesktopGrab() {}

    /** True if we appear to be running under a Wayland session (delegates to {@link SessionEnvironment}). */
    public static boolean isWayland() {
        return com.botmaker.studio.services.platform.SessionEnvironment.isWayland();
    }

    /**
     * Grabs the whole virtual desktop (all monitors) into one image, without ever showing a share picker:
     * a screenshot CLI under Wayland, {@link Robot} otherwise. Returns {@code null} if no capture path works.
     */
    public static BufferedImage grabVirtualDesktop() {
        if (isWayland()) return grabViaCli();
        try {
            return new Robot().createScreenCapture(virtualBounds());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The AWT virtual-screen bounds (union of every device); origin can be negative on multi-monitor. */
    public static Rectangle virtualBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }
        return bounds;
    }

    /**
     * Crops {@code desktop} (a {@link #grabVirtualDesktop()} image) to absolute-screen {@code bounds}, mapping
     * absolute coordinates to image pixels via the virtual-screen origin. Assumes scale 1.0.
     */
    public static BufferedImage cropToBounds(BufferedImage desktop, Rectangle bounds) {
        if (desktop == null) return null;
        Rectangle virtual = virtualBounds();
        int x = Math.max(0, Math.min(bounds.x - virtual.x, desktop.getWidth() - 1));
        int y = Math.max(0, Math.min(bounds.y - virtual.y, desktop.getHeight() - 1));
        int w = Math.max(1, Math.min(bounds.width, desktop.getWidth() - x));
        int h = Math.max(1, Math.min(bounds.height, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }

    /** True if every sampled pixel is pure black (heuristic for a failed/Wayland Robot capture). */
    public static boolean looksBlank(BufferedImage img) {
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 20);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    /**
     * Full-screen grab via an external screenshot tool (Wayland path). Tries each known tool in order and
     * returns the first successful capture; returns {@code null} (with a logged hint) if none work.
     */
    public static BufferedImage grabViaCli() {
        // Each entry is the argv with a trailing placeholder for the output PNG path.
        String[][] tools = {
                {"grim"},                                // wlroots / Sway / Hyprland
                {"gnome-screenshot", "-f"},              // GNOME
                {"spectacle", "-b", "-n", "-f", "-o"},   // KDE Plasma
        };
        try {
            Path tmp = Files.createTempFile("botmaker-shot-", ".png");
            try {
                for (String[] tool : tools) {
                    if (!toolExists(tool[0])) continue;
                    String[] argv = new String[tool.length + 1];
                    System.arraycopy(tool, 0, argv, 0, tool.length);
                    argv[tool.length] = tmp.toString();
                    if (runQuietly(argv) && Files.size(tmp) > 0) {
                        BufferedImage img = ImageIO.read(tmp.toFile());
                        if (img != null) return img;
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            return null;
        }
        System.err.println("No working Wayland screenshot tool found. Install one of: grim "
                + "(wlroots/Sway/Hyprland), gnome-screenshot (GNOME), or spectacle (KDE).");
        return null;
    }

    /** True if {@code name} resolves on PATH (via {@code which}). */
    private static boolean toolExists(String name) {
        try {
            return new ProcessBuilder("which", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs {@code argv}, discarding its output, and returns true if it exits 0 within the timeout. */
    private static boolean runQuietly(String[] argv) {
        try {
            Process p = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
