package com.botmaker.studio.ui.app.capture;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A dependency-free "magic wand" with light shape awareness: it flood-fills the region around the clicked
 * pixel, but is <em>gated by object edges</em> so the fill stays inside a contour instead of leaking across
 * soft gradients into the background, then fills interior holes so a textured/multi-colour object comes out
 * as one solid shape. Studio has no OpenCV, so this pure-Java pipeline is the pragmatic "point at an object →
 * extract it with a transparent background" engine behind {@link ObjectCaptureSurface}.
 *
 * <p>Pipeline (all steps deterministic, {@code maxPixels}-bounded):
 * <ol>
 *   <li><b>Edge map</b> — a Sobel gradient magnitude ({@link #sobel}), precomputed once per frozen frame and
 *       passed back in so hover/wheel don't re-run it every mouse move.</li>
 *   <li><b>Edge-gated BFS</b> — grow to a 4-neighbour only when its colour is within {@code tolerance} of the
 *       pixel it grew from (neighbour-relative, so it follows gradients) <em>and</em> that neighbour isn't on a
 *       strong edge (magnitude below a threshold derived from {@code tolerance}). A contour blocks the fill.</li>
 *   <li><b>Hole fill</b> — any not-selected pixel the background can't reach from the box border is an interior
 *       hole and is added to the mask, turning the outline into a filled shape.</li>
 *   <li><b>1-px dilation</b> — recovers the anti-aliased boundary so the cut has no 1-px transparent halo.</li>
 * </ol>
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
     * Precomputed Sobel gradient magnitude of an image (one {@code int} per pixel, {@code |Gx|+|Gy|} of
     * luminance). Cheap to build once per frozen frame; reused across every hover/wheel flood.
     */
    public static int[] sobel(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] lum = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                lum[y * w + x] = (r * 77 + g * 150 + b * 29) >> 8;   // ~0.299/0.587/0.114
            }
        }
        int[] mag = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int tl = lum[(y - 1) * w + (x - 1)], tc = lum[(y - 1) * w + x], tr = lum[(y - 1) * w + (x + 1)];
                int ml = lum[y * w + (x - 1)],                                 mr = lum[y * w + (x + 1)];
                int bl = lum[(y + 1) * w + (x - 1)], bc = lum[(y + 1) * w + x], br = lum[(y + 1) * w + (x + 1)];
                int gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl);
                int gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr);
                mag[y * w + x] = Math.abs(gx) + Math.abs(gy);
            }
        }
        return mag;
    }

    /** Convenience overload that computes the Sobel map inline (used by tests / one-off calls). */
    public static Result flood(BufferedImage img, int seedX, int seedY, int tolerance, int maxPixels) {
        return flood(img, sobel(img), seedX, seedY, tolerance, maxPixels);
    }

    /**
     * Edge-gated flood from {@code (seedX, seedY)} against the precomputed {@code edges} (Sobel magnitude),
     * then interior-hole fill and a 1-px dilation. Returns {@code null} for an out-of-bounds seed.
     */
    public static Result flood(BufferedImage img, int[] edges, int seedX, int seedY, int tolerance, int maxPixels) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (seedX < 0 || seedY < 0 || seedX >= w || seedY >= h) return null;

        // Edge threshold scales with tolerance: a looser wand tolerates weaker edges before it stops.
        int edgeThreshold = 60 + tolerance * 3;

        boolean[] mask = new boolean[w * h];
        Deque<int[]> stack = new ArrayDeque<>();   // {x, y, fromRgb}
        stack.push(new int[]{seedX, seedY, img.getRGB(seedX, seedY)});
        mask[seedY * w + seedX] = true;
        int count = 0;
        int minX = seedX, minY = seedY, maxX = seedX, maxY = seedY;

        while (!stack.isEmpty() && count < maxPixels) {
            int[] p = stack.pop();
            int x = p[0], y = p[1], fromRgb = p[2];
            count++;
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;

            pushIf(img, edges, mask, stack, x - 1, y, w, h, fromRgb, tolerance, edgeThreshold);
            pushIf(img, edges, mask, stack, x + 1, y, w, h, fromRgb, tolerance, edgeThreshold);
            pushIf(img, edges, mask, stack, x, y - 1, w, h, fromRgb, tolerance, edgeThreshold);
            pushIf(img, edges, mask, stack, x, y + 1, w, h, fromRgb, tolerance, edgeThreshold);
        }

        fillHoles(mask, w, minX, minY, maxX, maxY);
        int[] box = {minX, minY, maxX, maxY};
        count = dilate(mask, w, h, box);
        return new Result(mask, w, h, box[0], box[1], box[2], box[3], count);
    }

    private static void pushIf(BufferedImage img, int[] edges, boolean[] mask, Deque<int[]> stack,
                               int x, int y, int w, int h, int fromRgb, int tol, int edgeThreshold) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int idx = y * w + x;
        if (mask[idx]) return;
        if (edges[idx] > edgeThreshold) return;   // a strong contour blocks the fill (shape boundary)
        int rgb = img.getRGB(x, y);
        int dr = Math.abs(((rgb >> 16) & 0xFF) - ((fromRgb >> 16) & 0xFF));
        int dg = Math.abs(((rgb >> 8) & 0xFF) - ((fromRgb >> 8) & 0xFF));
        int db = Math.abs((rgb & 0xFF) - (fromRgb & 0xFF));
        if (Math.max(dr, Math.max(dg, db)) <= tol) {
            mask[idx] = true;
            stack.push(new int[]{x, y, rgb});   // neighbour-relative: compare from this pixel next
        }
    }

    /**
     * Fills interior holes: flood the <em>background</em> inward from the bounding-box border over
     * not-selected pixels; any not-selected pixel the background flood never reaches is enclosed by the
     * selection, so it's an interior hole and gets added to the mask. Turns an outline into a solid shape.
     */
    private static void fillHoles(boolean[] mask, int w, int minX, int minY, int maxX, int maxY) {
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        if (bw <= 2 || bh <= 2) return;
        boolean[] reachedBg = new boolean[bw * bh];   // background pixels reachable from the border
        Deque<int[]> stack = new ArrayDeque<>();
        // Seed from every border cell that isn't selected.
        for (int lx = 0; lx < bw; lx++) {
            seedBg(mask, reachedBg, stack, w, minX, minY, lx, 0, bw, bh);
            seedBg(mask, reachedBg, stack, w, minX, minY, lx, bh - 1, bw, bh);
        }
        for (int ly = 0; ly < bh; ly++) {
            seedBg(mask, reachedBg, stack, w, minX, minY, 0, ly, bw, bh);
            seedBg(mask, reachedBg, stack, w, minX, minY, bw - 1, ly, bw, bh);
        }
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int lx = p[0], ly = p[1];
            seedBg(mask, reachedBg, stack, w, minX, minY, lx - 1, ly, bw, bh);
            seedBg(mask, reachedBg, stack, w, minX, minY, lx + 1, ly, bw, bh);
            seedBg(mask, reachedBg, stack, w, minX, minY, lx, ly - 1, bw, bh);
            seedBg(mask, reachedBg, stack, w, minX, minY, lx, ly + 1, bw, bh);
        }
        // Any unselected pixel not reached by the background flood is enclosed → fill it.
        for (int ly = 0; ly < bh; ly++) {
            for (int lx = 0; lx < bw; lx++) {
                int gi = (minY + ly) * w + (minX + lx);
                if (!mask[gi] && !reachedBg[ly * bw + lx]) mask[gi] = true;
            }
        }
    }

    private static void seedBg(boolean[] mask, boolean[] reachedBg, Deque<int[]> stack,
                               int w, int minX, int minY, int lx, int ly, int bw, int bh) {
        if (lx < 0 || ly < 0 || lx >= bw || ly >= bh) return;
        int li = ly * bw + lx;
        if (reachedBg[li]) return;
        if (mask[(minY + ly) * w + (minX + lx)]) return;   // selected pixels are walls, not background
        reachedBg[li] = true;
        stack.push(new int[]{lx, ly});
    }

    /**
     * One-pixel dilation of {@code mask} within (and one px past) its bounding box, updating {@code box}
     * ({@code {minX,minY,maxX,maxY}}) and returning the new selected-pixel count. Recovers the anti-aliased
     * boundary so the extracted crop has no 1-px transparent halo.
     */
    private static int dilate(boolean[] mask, int w, int h, int[] box) {
        int minX = Math.max(0, box[0] - 1), minY = Math.max(0, box[1] - 1);
        int maxX = Math.min(w - 1, box[2] + 1), maxY = Math.min(h - 1, box[3] + 1);
        boolean[] add = new boolean[w * h];
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (mask[y * w + x]) continue;
                boolean near = (x > 0 && mask[y * w + x - 1]) || (x < w - 1 && mask[y * w + x + 1])
                        || (y > 0 && mask[(y - 1) * w + x]) || (y < h - 1 && mask[(y + 1) * w + x]);
                if (near) add[y * w + x] = true;
            }
        }
        int count = 0, nMinX = w, nMinY = h, nMaxX = 0, nMaxY = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (add[y * w + x]) mask[y * w + x] = true;
                if (mask[y * w + x]) {
                    count++;
                    if (x < nMinX) nMinX = x; if (x > nMaxX) nMaxX = x;
                    if (y < nMinY) nMinY = y; if (y > nMaxY) nMaxY = y;
                }
            }
        }
        box[0] = nMinX; box[1] = nMinY; box[2] = nMaxX; box[3] = nMaxY;
        return count;
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
