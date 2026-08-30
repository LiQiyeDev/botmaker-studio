package com.botmaker.studio.services.capture;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The monitor geometry both halves of capture need.
 *
 * <p>It is here rather than in {@link ScreenOverlay} or {@link TargetCapture} because it is genuinely used by
 * both and belongs to neither: the target half crops a desktop grab to the monitor a project named, and the
 * overlay half crops the same grab to the monitor a user picked in the chooser. Duplicating it would be two
 * copies of the one piece of arithmetic in this package that is easy to get subtly wrong and impossible to
 * see wrong — an off-by-one in the scale factor produces a crop that looks right and matches nothing.
 *
 * <p>It names no capture target, so it stays behind when the target half leaves; whatever implements
 * {@link ShotSource} on the far side will need its own equivalent, which is a fair price for not sharing a
 * utility class across a module boundary.
 */
final class Screens {

    private Screens() {
    }

    /** The whole virtual-desktop bounds in JavaFX logical coordinates (union of every screen). */
    static Rectangle2D virtualScreenBounds(List<Screen> screens) {
        double minX = screens.stream().mapToDouble(s -> s.getBounds().getMinX()).min().orElse(0);
        double minY = screens.stream().mapToDouble(s -> s.getBounds().getMinY()).min().orElse(0);
        double maxX = screens.stream().mapToDouble(s -> s.getBounds().getMaxX()).max().orElse(0);
        double maxY = screens.stream().mapToDouble(s -> s.getBounds().getMaxY()).max().orElse(0);
        return new Rectangle2D(minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY));
    }

    /**
     * Crops the full-desktop {@code desktop} image to the pixel region of {@code target}. Maps JavaFX
     * logical screen bounds to source pixels via each screen's output scale; assumes monitors share a
     * scale factor (true for the common case — mixed-DPI layouts may be slightly off).
     */
    static BufferedImage cropToScreen(BufferedImage desktop, List<Screen> screens, Screen target) {
        double unionMinX = screens.stream().mapToDouble(s -> s.getBounds().getMinX()).min().orElse(0);
        double unionMinY = screens.stream().mapToDouble(s -> s.getBounds().getMinY()).min().orElse(0);
        javafx.geometry.Rectangle2D b = target.getBounds();
        int x = (int) Math.round((b.getMinX() - unionMinX) * target.getOutputScaleX());
        int y = (int) Math.round((b.getMinY() - unionMinY) * target.getOutputScaleY());
        int w = (int) Math.round(b.getWidth() * target.getOutputScaleX());
        int h = (int) Math.round(b.getHeight() * target.getOutputScaleY());
        x = Math.max(0, Math.min(x, desktop.getWidth() - 1));
        y = Math.max(0, Math.min(y, desktop.getHeight() - 1));
        w = Math.max(1, Math.min(w, desktop.getWidth() - x));
        h = Math.max(1, Math.min(h, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }
}
