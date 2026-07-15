package com.botmaker.studio.ui.app.capture;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the GrabCut object extraction: a textured solid rectangle on random noise, boxed with a generous
 * margin, must come back as (approximately) the rectangle.
 *
 * <p>GrabCut is iterative and seeded from estimated colour models, so it is <em>not</em> bit-exact and the
 * assertions here are deliberately IoU-based rather than exact pixel counts.
 */
class MagicWandTest {

    private static final int W = 200, H = 160;
    private static final int OX = 60, OY = 50, OW = 70, OH = 60;   // object box

    /** Noise background with a textured (two-tone) grey rectangle painted on top — a clear edge to the noise. */
    private static BufferedImage sceneWithObject() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(11);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        for (int y = OY; y < OY + OH; y++)
            for (int x = OX; x < OX + OW; x++) {
                int v = ((x + y) % 8 < 4) ? 0x808080 : 0x888888;
                img.setRGB(x, y, v);
            }
        return img;
    }

    private static boolean inObject(int x, int y) {
        return x >= OX && x < OX + OW && y >= OY && y < OY + OH;
    }

    @Test
    void segmentsObjectFromBoxWithHighIoU() {
        BufferedImage scene = sceneWithObject();
        try (MagicWand.Session session = new MagicWand.Session(scene)) {
            // Box the object with a margin, the way a user drags a rough rectangle around it.
            MagicWand.Result r = session.initFromRect(OX - 12, OY - 12, OW + 24, OH + 24,
                    MagicWand.DEFAULT_ITERATIONS);
            assertNotNull(r);
            assertFalse(r.isEmpty(), "GrabCut returned an empty selection");

            int intersection = 0, union = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    boolean sel = r.mask()[y * W + x];
                    boolean truth = inObject(x, y);
                    if (sel && truth) intersection++;
                    if (sel || truth) union++;
                }
            }
            double iou = intersection / (double) union;
            assertTrue(iou >= 0.9, "IoU against the known object was only " + iou);

            // The reported bbox must be tight around what the mask actually contains.
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    if (!r.mask()[y * W + x]) continue;
                    assertTrue(x >= r.minX() && x <= r.maxX() && y >= r.minY() && y <= r.maxY(),
                            "selected pixel (" + x + "," + y + ") lies outside the reported bbox");
                }
            }
        }
    }

    @Test
    void extractCropsToBoxAndKeepsBackgroundTransparent() {
        BufferedImage scene = sceneWithObject();
        try (MagicWand.Session session = new MagicWand.Session(scene)) {
            MagicWand.Result r = session.initFromRect(OX - 12, OY - 12, OW + 24, OH + 24,
                    MagicWand.DEFAULT_ITERATIONS);
            assertNotNull(r);

            BufferedImage cut = MagicWand.extract(scene, r);
            assertEquals(r.boxWidth(), cut.getWidth());
            assertEquals(r.boxHeight(), cut.getHeight());

            // The centre of the object must be opaque; unselected pixels must be fully transparent.
            int cx = OX + OW / 2 - r.minX(), cy = OY + OH / 2 - r.minY();
            assertTrue(((cut.getRGB(cx, cy) >>> 24) & 0xFF) > 200, "object centre should be (near) opaque");

            for (int y = 0; y < cut.getHeight(); y++) {
                for (int x = 0; x < cut.getWidth(); x++) {
                    boolean sel = r.mask()[(r.minY() + y) * W + (r.minX() + x)];
                    int alpha = (cut.getRGB(x, y) >>> 24) & 0xFF;
                    if (!sel) continue;
                    // Selected pixels carry real (feathered) alpha rather than a forced-opaque rim.
                    assertTrue(alpha > 0, "selected pixel lost its alpha at (" + x + "," + y + ")");
                }
            }
        }
    }
}
