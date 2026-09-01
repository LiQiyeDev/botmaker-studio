package com.botmaker.studio.services.capture;

import javafx.stage.Window;

/**
 * Where {@link ScreenOverlay} gets its pixels — the one thing the overlay needs from whoever knows what a
 * capture target is.
 *
 * <p><b>Two methods, and that is the measured size of the coupling.</b> A survey of the 1,324-line service
 * this was split out of found exactly two places where the overlay flow touched a capture target: resolving
 * which pixels to grab, and naming the window a captured template came from. Everything else — the screen
 * chooser, the rubber-band surfaces, the magnifier, the crop arithmetic, the blank-frame warning — was
 * already target-free.
 *
 * <p>{@link TargetCapture} is the implementation today. It is also the whole of what has to be reimplemented
 * on the far side when the target half moves to the SDK plugin: the overlay stays in Studio behind
 * {@code StudioServices.capture()} and keeps consuming this.
 */
public interface ShotSource {

    /**
     * Resolves the capture target and grabs its pixels. <b>Blocking — call off the FX thread only.</b>
     *
     * @param owner the window a chooser or a warning would be owned by; may be {@code null}
     */
    Grab grab(Window owner);

    /**
     * The window title a template captured through this source records, or {@code null} for a screen or
     * desktop grab.
     *
     * <p>Not derivable from a {@link ScreenShot}: a shot carries pixels and bounds, and "which window was
     * this" is a fact about the target rather than about the frame. It is asked at capture time rather than
     * carried on the shot so that a source with no window says so by answering {@code null} once, instead of
     * every shot carrying a field that is usually empty.
     */
    String title();
}
