package com.botmaker.studio.ui.app.capture;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A dependency-free "magic wand": flood-fills the contiguous region of an image whose colour stays within a
 * tolerance of the clicked pixel, and cuts that region out onto a transparent background. Studio has no
 * OpenCV, so this pure-Java BFS is the pragmatic "point at an object → extract it with a transparent
 * background" engine behind {@link ObjectCaptureSurface}.
 *
 * <p>The fill is bounded ({@code maxPixels}) so a loose tolerance on a large frame can't spin forever; it
 * simply stops growing once the cap is hit (the partial region is still usable). Colour distance is the max
 * per-channel absolute difference from the seed — cheap and intuitive to steer with the mouse wheel.
 */
public final class MagicWand {

    private MagicWand() {}

    /** The filled region: a boolean {@code mask} over the whole image plus its tight bounding box. */
    public record Result(boolean[] mask, int imgWidth, int imgHeight,
                         int minX, int minY, int maxX, int maxY, int count) {
        public int boxWidth() { return maxX - minX + 1; }
        public int boxHeight() { return maxY - minY + 1; }
    }

    /**
     * Flood-fills from {@code (seedX, seedY)} outward over 4-neighbours while each pixel's colour is within
     * {@code tolerance} (max per-channel diff, 0–255) of the seed, stopping at {@code maxPixels}. Returns
     * {@code null} for an out-of-bounds seed.
     */
    public static Result flood(BufferedImage img, int seedX, int seedY, int tolerance, int maxPixels) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (seedX < 0 || seedY < 0 || seedX >= w || seedY >= h) return null;

        int seed = img.getRGB(seedX, seedY);
        int sr = (seed >> 16) & 0xFF, sg = (seed >> 8) & 0xFF, sb = seed & 0xFF;

        boolean[] mask = new boolean[w * h];
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{seedX, seedY});
        mask[seedY * w + seedX] = true;
        int count = 0;
        int minX = seedX, minY = seedY, maxX = seedX, maxY = seedY;

        while (!stack.isEmpty() && count < maxPixels) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            count++;
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;

            pushIf(img, mask, stack, x - 1, y, w, h, sr, sg, sb, tolerance);
            pushIf(img, mask, stack, x + 1, y, w, h, sr, sg, sb, tolerance);
            pushIf(img, mask, stack, x, y - 1, w, h, sr, sg, sb, tolerance);
            pushIf(img, mask, stack, x, y + 1, w, h, sr, sg, sb, tolerance);
        }
        return new Result(mask, w, h, minX, minY, maxX, maxY, count);
    }

    private static void pushIf(BufferedImage img, boolean[] mask, Deque<int[]> stack,
                               int x, int y, int w, int h, int sr, int sg, int sb, int tol) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int idx = y * w + x;
        if (mask[idx]) return;
        int rgb = img.getRGB(x, y);
        int dr = Math.abs(((rgb >> 16) & 0xFF) - sr);
        int dg = Math.abs(((rgb >> 8) & 0xFF) - sg);
        int db = Math.abs((rgb & 0xFF) - sb);
        if (Math.max(dr, Math.max(dg, db)) <= tol) {
            mask[idx] = true;
            stack.push(new int[]{x, y});
        }
    }

    /**
     * Cuts {@code result}'s masked region out of {@code img} into a new ARGB image the size of the bounding
     * box: masked pixels keep their colour (opaque), everything else is fully transparent.
     */
    public static BufferedImage extract(BufferedImage img, Result result) {
        int bw = result.boxWidth();
        int bh = result.boxHeight();
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int w = result.imgWidth();
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int ix = result.minX() + x, iy = result.minY() + y;
                if (result.mask()[iy * w + ix]) {
                    out.setRGB(x, y, 0xFF000000 | (img.getRGB(ix, iy) & 0x00FFFFFF));
                }
            }
        }
        return out;
    }
}
