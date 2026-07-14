package com.botmaker.studio.ui.app.capture;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the edge-gated, hole-filling flood: a textured solid rectangle on random noise must be extracted
 * whole (interior holes filled, none of the surrounding noise leaked in), and the fill must stop at the
 * object's edge.
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
                // Gentle two-tone texture inside the object (within the wand's tolerance, no internal edges).
                int v = ((x + y) % 8 < 4) ? 0x808080 : 0x888888;
                img.setRGB(x, y, v);
            }
        return img;
    }

    @Test
    void extractsWholeObjectAndStopsAtEdge() {
        BufferedImage scene = sceneWithObject();
        int[] edges = MagicWand.sobel(scene);
        // Seed in the object centre; tolerance small enough that the noise edge blocks the fill.
        MagicWand.Result r = MagicWand.flood(scene, edges, OX + OW / 2, OY + OH / 2, 24, 400_000);
        assertNotNull(r);

        // The whole object (both tones + any interior gaps) is selected: count ≈ object area (± the 1px halo).
        int area = OW * OH;
        assertTrue(r.count() >= area, "expected the full object filled, got " + r.count() + " of " + area);
        assertTrue(r.count() < area * 3, "fill leaked far past the object: " + r.count());

        // Centre is in; a point well outside the object (in the noise) is not.
        assertTrue(r.mask()[(OY + OH / 2) * W + (OX + OW / 2)], "object centre must be selected");
        assertFalse(r.mask()[10 * W + 10], "far background must not be selected");

        // Almost every selected pixel lies inside the object (allowing the 1px dilation border) — the edge
        // gate kept the fill from leaking into the surrounding noise.
        int leaked = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (r.mask()[y * W + x]
                        && (x < OX - 1 || x > OX + OW || y < OY - 1 || y > OY + OH)) leaked++;
        assertTrue(leaked < area * 0.05, "fill leaked " + leaked + " px into the background (area " + area + ")");
    }
}
