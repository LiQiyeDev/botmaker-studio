package com.botmaker.studio.project.capture;

import com.botmaker.studio.project.capture.CaptureTarget.DesktopTarget;
import com.botmaker.studio.project.capture.CaptureTarget.EmulatorTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;

/**
 * The single short-label for a {@link CaptureTarget}, shared by the toolbar Capture button, the in-block
 * capture-source button, and the debug dashboard so they always agree on how a target is named. A
 * {@code null} target (unset default) reads as the whole desktop — the same semantics as
 * {@link CaptureExpr} ({@code CaptureSource.desktop()}).
 */
public final class CaptureTargetNames {

    private CaptureTargetNames() {}

    /** e.g. {@code "Screen 2"}, a window title, or {@code "Whole desktop"} ({@code null} → whole desktop). */
    public static String shortLabel(CaptureTarget target) {
        return switch (target) {
            case null -> "Whole desktop";
            case ScreenTarget st -> "Screen " + (st.index() + 1);
            case WindowTarget wt -> (wt.titleSubstring() == null || wt.titleSubstring().isBlank())
                    ? "Window" : wt.titleSubstring();
            case EmulatorTarget et -> (et.instanceName() == null || et.instanceName().isBlank())
                    ? "Emulator" : et.instanceName();
            case DesktopTarget ignored -> "Whole desktop";
        };
    }
}
