package com.botmaker.studio.project.capture;

/**
 * A sub-rectangle <em>within</em> a {@link CaptureTarget}'s own pixel space — the editor-side counterpart of
 * the SDK's {@code CaptureSource.region(...)}. It is not an absolute screen rectangle: {@code (0,0)} is the
 * source's top-left, so the same region survives the window/monitor moving. Emitted by {@link CaptureExpr} as
 * a trailing {@code .region(new Rect(x, y, width, height))} on the source expression.
 */
public record CaptureRegion(int x, int y, int width, int height) {

    /** Whether this region has a positive area (an empty region is treated as "no region"). */
    public boolean isValid() {
        return width > 0 && height > 0;
    }
}
