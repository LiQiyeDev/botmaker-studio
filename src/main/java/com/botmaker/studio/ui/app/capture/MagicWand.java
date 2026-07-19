package com.botmaker.studio.ui.app.capture;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The object-extraction engine behind {@link ObjectCaptureSurface}, backed by OpenCV's <b>GrabCut</b>
 * (iterated graph-cut segmentation seeded from a rectangle, then refined with foreground/background strokes).
 * No training, no model files — GrabCut estimates its own colour models per image.
 *
 * <p>Usage is a {@link Session} over one frozen frame:
 * <ol>
 *   <li>{@link Session#initFromRect} — the user drags a box around the object; GrabCut labels everything
 *       outside it definite-background and iterates.</li>
 *   <li>{@link Session#paint} + {@link Session#refine} — optional correction strokes mark definite
 *       foreground/background where GrabCut guessed wrong; the retained GMM models mean each refinement
 *       <em>continues</em> the previous solve rather than restarting it.</li>
 *   <li>{@link #extract} — cuts the result out as a bbox-cropped ARGB image with a transparent background.</li>
 * </ol>
 *
 * <p><b>Output contract (unchanged):</b> {@link #extract} returns a {@code TYPE_INT_ARGB} crop whose alpha
 * channel is the object mask. That alpha is what the SDK later turns into an OpenCV {@code matchTemplate}
 * mask, so the shape of what this class emits is load-bearing for runtime matching — see the SDK's
 * {@code OpencvManager.extractAlphaMask}. Unlike the previous flood-fill implementation, the boundary alpha
 * is <em>feathered</em> rather than forced opaque: a hard rim would bake background-blended pixels into the
 * template and guarantee a mismatch exactly where correlation matters most.
 *
 * <p>GrabCut is far too slow to run per mouse-move; callers must drive it off the FX thread on
 * drag-release / stroke-release only.
 */
public final class MagicWand {

    static { OpenCvNative.ensureLoaded(); }

    /** Default GrabCut iterations per solve. 5 is the canonical value — more buys little on UI-sized frames. */
    public static final int DEFAULT_ITERATIONS = 5;

    private MagicWand() {}

    /**
     * The extracted region: a boolean {@code mask} over the whole image, a parallel per-pixel {@code alpha}
     * (0–255, feathered at the boundary), and the tight bounding box of the selection.
     */
    public record Result(boolean[] mask, byte[] alpha, int imgWidth, int imgHeight,
                         int minX, int minY, int maxX, int maxY, int count) {
        public int boxWidth() { return maxX - minX + 1; }
        public int boxHeight() { return maxY - minY + 1; }
        public boolean isEmpty() { return count == 0; }
    }

    /**
     * A GrabCut segmentation over one frozen frame. Retains the image, the label mask and the background /
     * foreground GMM models across calls so refinement is incremental. Native memory — {@link #close()} it.
     */
    public static final class Session implements AutoCloseable {

        // Session is a *nested* class: instantiating it does not run MagicWand's static initializer, and it is
        // the first thing to touch a Mat. The loader must be triggered here or every ctor dies on
        // UnsatisfiedLinkError.
        static { OpenCvNative.ensureLoaded(); }

        /** Slack (image px) added around the working box when cropping the refine ROI. */
        private static final int ROI_MARGIN = 16;
        /** How many mask snapshots to retain for undo before dropping the oldest. */
        private static final int MAX_HISTORY = 24;

        private final Mat image;      // CV_8UC3, BGR
        private final Mat mask;       // CV_8UC1 of GC_* labels
        private final Mat bgModel = new Mat();
        private final Mat fgModel = new Mat();
        private final int width, height;
        private boolean initialised = false;

        // The tight working box (image px, x0/y0 inclusive, x1/y1 exclusive) that bounds the refine ROI: the
        // initial rect, grown to cover every refinement stroke so ROI-cropped refinement never drops a
        // stroke placed outside the original box.
        private int wx0, wy0, wx1, wy1;

        // Mask-snapshot undo/redo. Each entry is a clone of the label mask before a solve; undo swaps the
        // current mask with the top of one stack onto the other. Native memory — released on close().
        private final Deque<Mat> undoStack = new ArrayDeque<>();
        private final Deque<Mat> redoStack = new ArrayDeque<>();

        public Session(BufferedImage frame) {
            this.image = toMat(frame);
            this.width = frame.getWidth();
            this.height = frame.getHeight();
            this.mask = new Mat(height, width, CvType.CV_8UC1, new Scalar(Imgproc.GC_BGD));
        }

        /**
         * Seeds the segmentation from {@code box} (surface coordinates already mapped to image pixels):
         * everything outside is definite background, everything inside is probable foreground. Returns
         * {@code null} for a degenerate box.
         */
        public Result initFromRect(int x, int y, int w, int h, int iterations) {
            Rect rect = clampRect(x, y, w, h);
            if (rect == null) return null;
            pushHistory();
            Imgproc.grabCut(image, mask, rect, bgModel, fgModel, iterations, Imgproc.GC_INIT_WITH_RECT);
            initialised = true;
            wx0 = rect.x; wy0 = rect.y;
            wx1 = rect.x + rect.width; wy1 = rect.y + rect.height;
            return current();
        }

        /**
         * Marks a disc of radius {@code radius} around ({@code x},{@code y}) as definite foreground or
         * definite background. Takes effect on the next {@link #refine}.
         */
        public void paint(int x, int y, int radius, boolean foreground) {
            double label = foreground ? Imgproc.GC_FGD : Imgproc.GC_BGD;
            int r = Math.max(1, radius);
            Imgproc.circle(mask, new Point(x, y), r, new Scalar(label), -1);
            // Grow the working box so a stroke placed outside the original rect stays inside the refine ROI —
            // otherwise ROI-cropped refinement would silently drop the newly-painted foreground/background.
            if (initialised) {
                wx0 = Math.min(wx0, Math.max(0, x - r));
                wy0 = Math.min(wy0, Math.max(0, y - r));
                wx1 = Math.max(wx1, Math.min(width, x + r + 1));
                wy1 = Math.max(wy1, Math.min(height, y + r + 1));
            }
        }

        /**
         * Re-solves from the current label mask, continuing from the retained models. GrabCut runs on a
         * <em>cropped ROI</em> around the working box (not the whole frame): {@code GC_INIT_WITH_MASK} ignores
         * the rect argument, and an OpenCV sub-{@link Mat} shares its parent's pixels, so the solve writes
         * straight back into the full mask while only paying for the pixels that can actually change.
         */
        public Result refine(int iterations) {
            if (!initialised) return null;
            pushHistory();
            Rect roi = roiRect();
            Mat imgRoi = new Mat(image, roi);
            Mat maskRoi = new Mat(mask, roi);
            Imgproc.grabCut(imgRoi, maskRoi, new Rect(), bgModel, fgModel, iterations, Imgproc.GC_INIT_WITH_MASK);
            imgRoi.release();
            maskRoi.release();
            return current();
        }

        /** The working box grown by {@link #ROI_MARGIN} and clamped to the frame (falls back to the full frame). */
        private Rect roiRect() {
            int x0 = Math.max(0, wx0 - ROI_MARGIN);
            int y0 = Math.max(0, wy0 - ROI_MARGIN);
            int x1 = Math.min(width, wx1 + ROI_MARGIN);
            int y1 = Math.min(height, wy1 + ROI_MARGIN);
            if (x1 - x0 < 3 || y1 - y0 < 3) return new Rect(0, 0, width, height);
            return new Rect(x0, y0, x1 - x0, y1 - y0);
        }

        /** True when an earlier mask state is on the undo stack. */
        public boolean canUndo() { return !undoStack.isEmpty(); }

        /** True when an undone mask state can be re-applied. */
        public boolean canRedo() { return !redoStack.isEmpty(); }

        /** Reverts the mask to the state before the last solve; returns the restored selection (may be empty). */
        public Result undo() {
            if (undoStack.isEmpty()) return null;
            redoStack.push(mask.clone());
            Mat prev = undoStack.pop();
            prev.copyTo(mask);
            prev.release();
            return current();
        }

        /** Re-applies a solve reverted by {@link #undo()}; returns the restored selection (may be empty). */
        public Result redo() {
            if (redoStack.isEmpty()) return null;
            undoStack.push(mask.clone());
            Mat next = redoStack.pop();
            next.copyTo(mask);
            next.release();
            return current();
        }

        /** Snapshots the current mask onto the undo stack (bounded) and invalidates the redo history. */
        private void pushHistory() {
            undoStack.push(mask.clone());
            while (undoStack.size() > MAX_HISTORY) undoStack.removeLast().release();
            releaseAll(redoStack);
        }

        private static void releaseAll(Deque<Mat> stack) {
            for (Mat m : stack) m.release();
            stack.clear();
        }

        /** Reads the current label mask out as a {@link Result} without re-solving. */
        public Result current() {
            if (!initialised) return null;
            return readResult(mask, width, height);
        }

        private Rect clampRect(int x, int y, int w, int h) {
            int x0 = Math.max(0, Math.min(x, width - 1));
            int y0 = Math.max(0, Math.min(y, height - 1));
            int x1 = Math.max(0, Math.min(x + w, width));
            int y1 = Math.max(0, Math.min(y + h, height));
            // GrabCut needs real area inside the box, and a 1px box is a user mis-drag, not a selection.
            if (x1 - x0 < 3 || y1 - y0 < 3) return null;
            return new Rect(x0, y0, x1 - x0, y1 - y0);
        }

        @Override
        public void close() {
            image.release();
            mask.release();
            bgModel.release();
            fgModel.release();
            releaseAll(undoStack);
            releaseAll(redoStack);
        }
    }

    /**
     * Turns a GrabCut label mask into a {@link Result}: foreground is {@code GC_FGD}/{@code GC_PR_FGD}, and
     * the alpha channel is that binary mask blurred slightly so anti-aliased object edges ramp out instead of
     * ending on a hard, background-contaminated rim.
     */
    private static Result readResult(Mat labels, int width, int height) {
        // foreground <=> label is odd (GC_FGD=1, GC_PR_FGD=3; GC_BGD=0, GC_PR_BGD=2)
        Mat ones = new Mat(labels.size(), labels.type(), new Scalar(1));
        Mat fg = new Mat();
        Core.bitwise_and(labels, ones, fg);
        ones.release();
        Mat alphaMat = new Mat();
        fg.convertTo(alphaMat, CvType.CV_8UC1, 255.0);

        Mat feathered = new Mat();
        Imgproc.GaussianBlur(alphaMat, feathered, new Size(3, 3), 0);

        byte[] fgBytes = new byte[width * height];
        byte[] alphaBytes = new byte[width * height];
        fg.get(0, 0, fgBytes);
        feathered.get(0, 0, alphaBytes);

        fg.release();
        alphaMat.release();
        feathered.release();

        boolean[] mask = new boolean[width * height];
        int count = 0, minX = width, minY = height, maxX = -1, maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (fgBytes[i] == 0) continue;
                mask[i] = true;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (count == 0) return new Result(mask, alphaBytes, width, height, 0, 0, 0, 0, 0);
        return new Result(mask, alphaBytes, width, height, minX, minY, maxX, maxY, count);
    }

    /**
     * Cuts {@code result}'s region out of {@code img} into a new ARGB image the size of the bounding box:
     * selected pixels keep their colour at the result's (feathered) alpha, everything else is transparent.
     */
    public static BufferedImage extract(BufferedImage img, Result result) {
        int bw = result.boxWidth();
        int bh = result.boxHeight();
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int w = result.imgWidth();
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int ix = result.minX() + x, iy = result.minY() + y;
                int i = iy * w + ix;
                int a = result.alpha()[i] & 0xFF;
                if (a == 0) continue;
                out.setRGB(x, y, (a << 24) | (img.getRGB(ix, iy) & 0x00FFFFFF));
            }
        }
        return out;
    }

    /** Converts to the {@code CV_8UC3} BGR Mat GrabCut expects, redrawing when the source type differs. */
    private static Mat toMat(BufferedImage src) {
        BufferedImage img = src;
        if (img.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage conv = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = conv.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = conv;
        }
        byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(img.getHeight(), img.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }
}
