package com.botmaker.studio.services.capture;

import java.awt.image.BufferedImage;

/**
 * The result of one off-thread grab: either a finished {@link ScreenShot}, or a raw desktop image that still
 * needs the FX-thread screen chooser. Both {@code null} means the grab failed.
 *
 * <p>The two-state shape exists because the work splits across two threads and the split is not negotiable:
 * grabbing blocks (native focus, a sleep for the compositor, a Robot or CLI capture) and the chooser is a
 * modal dialog, so one has to happen off the FX thread and the other on it. Returning "I could not decide,
 * here are the pixels" is how the off-thread half hands that decision back.
 *
 * @param shot              the finished frame, or {@code null}
 * @param desktopForChooser the whole-desktop pixels the chooser previews from, when there is no recorded
 *                          default and more than one monitor; {@code null} otherwise
 */
public record Grab(ScreenShot shot, BufferedImage desktopForChooser) {

    /** The grab failed outright — no frame, and nothing to ask the user about. */
    public static Grab failed() {
        return new Grab(null, null);
    }
}
