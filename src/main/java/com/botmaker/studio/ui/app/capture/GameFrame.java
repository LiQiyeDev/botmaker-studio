package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.app.ManageCaptureTargetsDialog;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * One frozen frame of what the bot will actually look at, plus the label of the target it came from.
 *
 * <p>This is the thing the {@code Pixel} argument editors were missing. A ΔE slider and a blob drawn on a grid
 * are abstractions because there is no real frame behind them; with one in hand every knob becomes answerable
 * by looking — <em>at this tolerance, three blobs match, the largest 812 px²</em>.
 *
 * <p>{@link #grab} is the single entry point, so the failure path is written once. It wraps
 * {@link ScreenCaptureService#captureDefaultTargetAsync}, which already grabs any target type off the FX
 * thread and hands back {@code null} on a failed or blank grab. On that {@code null} the user is told which
 * of the two things went wrong — <b>no target configured</b> or <b>the grab came back blank</b> — rather than
 * being shown one message that guesses. The distinction matters on Wayland, where a correctly configured
 * target can still produce nothing, and a message blaming the configuration sends the user to fix something
 * that is not broken.
 *
 * <p><b>Never a silent desktop fallback.</b> What you sample has to be a pixel of the thing the bot will look
 * at; quietly grabbing the whole desktop instead would hand back a colour from the wrong image and no
 * indication of it.
 *
 * <p>The frame is re-grabbed on every open rather than cached: it should show the game as it is now, and the
 * grab is off-thread anyway.
 */
public record GameFrame(BufferedImage image, String label) {

    /**
     * Grabs the project's default capture target and delivers it on the FX thread. {@code onFx} is called
     * exactly once with a frame, or not at all if the user dismisses the failure dialog — a caller opening a
     * sampler therefore just does nothing when there is no frame to sample.
     */
    public static void grab(CodeEditorService context, Window owner, Consumer<GameFrame> onFx) {
        ScreenCaptureService.forProject(context).captureDefaultTargetAsync(owner, shot -> {
            if (shot != null && shot.image() != null) {
                onFx.accept(new GameFrame(shot.image(), shot.label()));
                return;
            }
            offerToFixTarget(context, owner, () -> grab(context, owner, onFx));
        });
    }

    /**
     * Explains why there is no frame and offers the capture-targets dialog, running {@code retry} once the
     * user has been through it. Declining the offer ends the attempt silently — the caller's {@code onFx}
     * simply never fires.
     */
    private static void offerToFixTarget(CodeEditorService context, Window owner, Runnable retry) {
        ProjectSettingsService settings = new ProjectSettingsService(
                context.getConfig(), context.getState(), context.getEventBus());
        boolean configured = settings.defaultTarget() != null;

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("No frame to sample");
        alert.setHeaderText(configured
                ? "The capture target produced a blank frame"
                : "This project has no capture target");
        alert.setContentText(configured
                ? "The target is set, but grabbing it returned nothing. This usually means the window is "
                  + "minimised or on another workspace — or, on a Wayland session, that the screenshot tool "
                  + "could not reach it. Bring the game to the front and try again, or point the project at a "
                  + "different target."
                : "Sampling a colour needs a frame of the thing the bot will look at. Choose the game window, "
                  + "or a screen, as this project's capture target and try again.");
        if (owner != null) alert.initOwner(owner);

        ButtonType choose = new ButtonType("Choose target…", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(choose, ButtonType.CANCEL);

        if (alert.showAndWait().filter(bt -> bt == choose).isEmpty()) return;
        new ManageCaptureTargetsDialog(owner, settings, context.getConfig().resourcesRoot()).show(retry);
    }
}
