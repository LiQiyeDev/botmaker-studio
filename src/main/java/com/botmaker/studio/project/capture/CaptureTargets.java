package com.botmaker.studio.project.capture;

import com.botmaker.sdk.authoring.CaptureTargetModel;
import com.botmaker.shared.config.CaptureSourceKind;

/**
 * The one conversion between a {@link CaptureTarget} — the editor's four shapes — and the stored spec text a
 * bot reads.
 *
 * <p>It exists because the project's capture targets moved into the SDK's own authoring data
 * ({@code capture.json}, through {@code Authoring.readCapture}), where a target is stored as the spec in
 * shared's {@link CaptureSourceKind} grammar rather than as four polymorphic JSON shapes. That was the point
 * of the move: the same list is now what a picker offers <em>and</em> what a running bot resolves, so the
 * editor and the bot can no longer disagree about which window to look at.
 *
 * <p>Both directions are total. A spec in no recognised form, or a monitor index that is not a number, reads
 * back as the whole desktop — the same fallback the SDK's own reader takes, and for the same reason: a
 * hand-edited project file must open.
 *
 * <p>{@link CaptureTarget.WindowTarget#windowId()} does not survive the round trip, and that is correct
 * rather than lossy: it is a live X handle whose javadoc already says a persisted one is meaningless.
 */
public final class CaptureTargets {

    private CaptureTargets() {
    }

    /** The spec text that names {@code target}, in the grammar a bot reads. */
    public static String spec(CaptureTarget target) {
        if (target == null) return CaptureSourceKind.DESKTOP.spec(null);
        return switch (target) {
            case CaptureTarget.DesktopTarget ignored -> CaptureSourceKind.DESKTOP.spec(null);
            case CaptureTarget.ScreenTarget screen -> CaptureSourceKind.MONITOR.spec(String.valueOf(screen.index()));
            case CaptureTarget.WindowTarget window -> CaptureSourceKind.WINDOW.spec(window.titleSubstring());
            case CaptureTarget.EmulatorTarget emulator -> CaptureSourceKind.EMULATOR.spec(emulator.instanceName());
        };
    }

    /** The target {@code model} names, never {@code null} — an unreadable spec is the whole desktop. */
    public static CaptureTarget target(CaptureTargetModel model) {
        if (model == null) return new CaptureTarget.DesktopTarget();
        CaptureSourceKind kind = model.kind();
        String argument = model.argument();
        if (kind == null || (kind.takesArgument() && argument == null)) {
            return new CaptureTarget.DesktopTarget();
        }
        return switch (kind) {
            case DESKTOP -> new CaptureTarget.DesktopTarget();
            case MONITOR -> new CaptureTarget.ScreenTarget(monitorIndex(argument));
            case WINDOW -> new CaptureTarget.WindowTarget(argument);
            case EMULATOR -> new CaptureTarget.EmulatorTarget(argument);
        };
    }

    /**
     * The stored form of {@code target}.
     *
     * <p>No label is written, deliberately: {@link CaptureTarget#label()} is derived from the target itself
     * and one of the four derives it from a live scan ({@code EmulatorInstances.captionFor}), so storing it
     * would be a second answer that goes stale the first time an emulator is renamed. The label field is for
     * a name the <em>user</em> gave a target, which nothing offers yet.
     */
    public static CaptureTargetModel model(CaptureTarget target) {
        return CaptureTargetModel.of(spec(target));
    }

    private static int monitorIndex(String argument) {
        try {
            int index = Integer.parseInt(argument.trim());
            return index < 0 ? 0 : index;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
