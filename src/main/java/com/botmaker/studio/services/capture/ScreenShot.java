package com.botmaker.studio.services.capture;

import javafx.geometry.Rectangle2D;

import java.awt.image.BufferedImage;

/**
 * A captured frame ready to overlay — <b>the seam between the two halves of editor-time capture.</b>
 *
 * <p>{@link TargetCapture} produces one by resolving the project's capture target (a window it raises, a
 * monitor, the whole desktop, an emulator over ADB); {@link ScreenOverlay} consumes one and knows nothing
 * about where it came from. That is the whole split: everything downstream of this record — the rubber-band
 * surfaces, the magnifier, the screen chooser, the crop arithmetic — names no capture target at all, which
 * is what lets the target half leave for the SDK plugin while the overlay stays behind
 * {@code StudioServices.capture()}.
 *
 * @param image      the pixels
 * @param bounds     the logical bounds to place the overlay over and map coordinates against — a whole
 *                   screen, the virtual desktop, or a window's rectangle
 * @param fullScreen true to go true-fullscreen (a single-screen target), false to position the stage at
 *                   {@code bounds} (a window or the whole multi-monitor desktop)
 * @param blank      the grab looked blank — a Wayland black frame. Never overlay one: the user would be
 *                   trapped behind a black full-screen stage with nothing to aim at
 */
public record ScreenShot(BufferedImage image, Rectangle2D bounds, boolean fullScreen, boolean blank) {
}
