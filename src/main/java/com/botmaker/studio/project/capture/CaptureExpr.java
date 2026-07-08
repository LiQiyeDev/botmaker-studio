package com.botmaker.studio.project.capture;

import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;

/**
 * Maps a {@link CaptureTarget} to the inline Java expression a capture-source block emits. Expressions are
 * <b>fully qualified</b> against the SDK's {@code api.capture} facades so they compile with no import
 * management and no generated sidecar — the block source is self-contained.
 *
 * <p>This is the single source of truth shared by every capture-source picker (the in-block picker, the
 * expression menu) so they all agree on the emitted text. A {@code null} target (no default) maps to the
 * whole desktop.
 */
public final class CaptureExpr {

    private static final String PKG = "com.botmaker.sdk.api.capture.";

    private CaptureExpr() {}

    /** The inline expression for {@code target}, or the whole-desktop source when {@code target} is null. */
    public static String of(CaptureTarget target) {
        if (target instanceof ScreenTarget st) {
            return PKG + "Screen.at(" + st.index() + ")";
        }
        if (target instanceof WindowTarget wt && wt.titleSubstring() != null && !wt.titleSubstring().isBlank()) {
            return PKG + "CaptureSource.window(\"" + escape(wt.titleSubstring()) + "\")";
        }
        return PKG + "CaptureSource.screen()";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
