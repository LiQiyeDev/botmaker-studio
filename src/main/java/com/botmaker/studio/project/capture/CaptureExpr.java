package com.botmaker.studio.project.capture;

import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;

/**
 * Maps a {@link CaptureTarget} (+ optional {@link CaptureRegion}) to the inline Java expression a
 * capture-source block emits. Expressions are <b>fully qualified</b> against the SDK's {@code api.capture}
 * facades so they compile with no import management and no generated sidecar — the block source is
 * self-contained.
 *
 * <p>A capture source is exactly one of three things — the whole {@code CaptureSource.desktop()}, a
 * {@code CaptureSource.monitor(i)}, or a {@code CaptureSource.window("t")} — optionally narrowed to a
 * rectangle of that source with a trailing {@code .region(new Rect(x, y, w, h))}. This is the single source
 * of truth shared by every capture-source picker (the in-block picker, the expression menu) so they all agree
 * on the emitted text. A {@code null} target (no default) maps to the whole desktop.
 */
public final class CaptureExpr {

    private static final String PKG = "com.botmaker.sdk.api.capture.";
    private static final String RECT = "com.botmaker.sdk.api.Rect";

    private CaptureExpr() {}

    /** The inline expression for {@code target}, or the whole-desktop source when {@code target} is null. */
    public static String of(CaptureTarget target) {
        return of(target, null);
    }

    /**
     * The inline expression for {@code target}, narrowed to {@code region} when it is a valid (positive-area)
     * rectangle of that source. {@code region} is in the source's own pixel coordinates.
     */
    public static String of(CaptureTarget target, CaptureRegion region) {
        String base = baseOf(target);
        if (region != null && region.isValid()) {
            return base + ".region(new " + RECT + "(" + region.x() + ", " + region.y() + ", "
                    + region.width() + ", " + region.height() + "))";
        }
        return base;
    }

    private static String baseOf(CaptureTarget target) {
        if (target instanceof ScreenTarget st) {
            return PKG + "CaptureSource.monitor(" + st.index() + ")";
        }
        if (target instanceof WindowTarget wt && wt.titleSubstring() != null && !wt.titleSubstring().isBlank()) {
            return PKG + "CaptureSource.window(\"" + escape(wt.titleSubstring()) + "\")";
        }
        // DesktopTarget and null both map to the whole virtual desktop.
        return PKG + "CaptureSource.desktop()";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
