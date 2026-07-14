package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTargetNames;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.ScreenCaptureService.WindowShot;
import com.botmaker.studio.ui.app.capture.BatchTemplateNamingDialog.NamedTemplate;
import com.botmaker.studio.ui.app.capture.CaptureSurface.Region;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The "Capture Templates" tool for the project's default <b>window</b> target. It shows a small, always-on-top
 * <em>mini-toolbar</em> that stays out of the way — crucially, it never covers the window, so the app underneath
 * stays fully clickable (real OS clicks) and the user can navigate it to the screen they want to capture.
 *
 * <p>The rubber-band drawing surface ({@link CaptureSurface}) is shown only <em>during</em> an active capture
 * and dismissed afterwards, so mouse events are grabbed only while actually drawing. Two modes:
 * <ul>
 *   <li><b>Capture one</b> — draw a single region, name it (unique, non-blank), save.</li>
 *   <li><b>Capture many</b> — draw several regions in one pass, then name/discard them all at once
 *       ({@link BatchTemplateNamingDialog}).</li>
 * </ul>
 *
 * <p>At save time the window's pixels are re-captured fresh via {@link ScreenCaptureService#captureWindow}
 * (occlusion-safe, off the FX thread), so the overlay chrome never ends up in a saved template; the drawn
 * selection (overlay-logical pixels) is scaled onto the captured image (physical pixels) by the width/height
 * ratio, which keeps the crop correct under HiDPI scaling.
 */
public final class OverlayTemplateCapture {

    /** The single live overlay instance, so pressing the toolbar button again focuses it instead of opening another. */
    private static OverlayTemplateCapture active;

    private final Window owner;
    private final ProjectConfig config;
    private final ScreenCaptureService capture;
    private final ProjectSettingsService settings;
    private final EventBus eventBus;
    /** The default capture target: a window, a monitor, or the whole desktop. */
    private final CaptureTarget target;

    private Stage toolbarStage;
    private CaptureSurface surface;
    private ObjectCaptureSurface objectSurface;
    /** The full captured frame's size for the in-progress object capture (for the template's capture-resolution sidecar). */
    private int objectFrameW, objectFrameH;
    /** The canonical window size to snap to before each capture (project reference resolution), or null.
     *  Only used for a window target — a screen/desktop is never resized. */
    private StudioProjectSettings.Resolution referenceResolution;

    private OverlayTemplateCapture(Window owner, ProjectConfig config, ScreenCaptureService capture,
                                   ProjectSettingsService settings, EventBus eventBus,
                                   CaptureTarget target) {
        this.owner = owner;
        this.config = config;
        this.capture = capture;
        this.settings = settings;
        this.eventBus = eventBus;
        this.target = target;
    }

    /**
     * Opens the tool for the project's default window target. Shows an explanatory alert (and does nothing
     * else) when the default target isn't a window, or the window can't be found. Must be called on the FX thread.
     */
    public static void open(Window owner, ProjectConfig config, ProjectSettingsService settings,
                            ScreenCaptureService capture, EventBus eventBus) {
        CaptureTarget target = null;
        try {
            target = settings.defaultTarget();
        } catch (Exception ignored) {
            // no default configured
        }
        if (target == null) {
            warn(owner, "Capture templates needs a capture target.\n\nOpen \"Capture Targets\" and set a window, "
                    + "monitor or the desktop as the default first.");
            return;
        }
        // Single-instance: focus the live overlay instead of stacking another one.
        if (active != null && active.toolbarStage != null && active.toolbarStage.isShowing()) {
            active.toolbarStage.toFront();
            return;
        }
        new OverlayTemplateCapture(owner, config, capture, settings, eventBus, target).start();
    }

    private void start() {
        // The project reference resolution: the canonical window size every template is captured at (window
        // targets only — a screen/desktop is captured at its native size). Seed it from the window's current
        // size the first time so later captures snap back to this exact size.
        referenceResolution = (target instanceof CaptureTarget.WindowTarget)
                ? settings.current().referenceResolution() : null;
        // Probe the target once up front so we can fail fast (and place the toolbar near it) before showing
        // anything. Sessions re-resolve the bounds again so the surface tracks a moved window.
        captureTargetAsync(shot -> {
            if (shot == null) {
                warn(owner, "Couldn't capture the target \"" + CaptureTargetNames.shortLabel(target) + "\". "
                        + "Is it open / on screen?");
                if (active == this) active = null;
                return;
            }
            if (target instanceof CaptureTarget.WindowTarget && referenceResolution == null) {
                referenceResolution = new StudioProjectSettings.Resolution(shot.bounds().width, shot.bounds().height);
                settings.update(settings.current().withReferenceResolution(referenceResolution));
            }
            showToolbar(shot.bounds());
        });
    }

    private void showToolbar(java.awt.Rectangle windowBounds) {
        Button one = new Button("▢ Capture one");
        one.setOnAction(e -> beginSingle());
        Button many = new Button("▦ Capture many");
        many.setOnAction(e -> beginMany());
        Button object = new Button("◎ Capture object");
        object.setTooltip(new javafx.scene.control.Tooltip(
                "Point at an object to extract it with a transparent background (scroll to resize)"));
        object.setOnAction(e -> beginObject());
        Button close = new Button("✕ Close");
        close.setOnAction(e -> closeTool());

        Label hint = new Label("Capture Templates");
        hint.setTextFill(Color.web("#c9d4e6"));
        // Current resolution readout so the user always knows the window/screen size they're capturing at.
        boolean isWindow = target instanceof CaptureTarget.WindowTarget;
        Label resLabel = new Label(com.botmaker.studio.ui.app.ResolutionChoices.readout(
                isWindow ? windowBounds : null));
        resLabel.setTextFill(Color.web("#8fa3bf"));
        resLabel.setStyle("-fx-font-size: 11px;");

        HBox bar = new HBox(8, hint, one, many, object, close, resLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle("-fx-background-color: rgba(20,24,33,0.92); -fx-background-radius: 8;");

        // Shared: draggable, always-on-top, and deliberately NOT owned by the Studio window (so Studio can
        // be minimized without hiding the overlay). Positioned just above the target window.
        toolbarStage = OverlayToolbars.show(bar, windowBounds);
        toolbarStage.getScene().setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) closeTool(); });
        active = this;
    }

    /** Closes the toolbar (and any live surface) and clears the single-instance reference. */
    private void closeTool() {
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (objectSurface != null) {
            objectSurface.close();
            objectSurface = null;
        }
        if (toolbarStage != null) toolbarStage.close();
        if (active == this) active = null;
    }

    // ── Capture one ────────────────────────────────────────────────────────────────────────────────────

    private void beginSingle() {
        toolbarStage.hide();
        captureTargetAsync(shot -> {
            if (shot == null) { warnClosed(); endSession(); return; }
            surface = CaptureSurface.single(owner, shot.bounds(), this::onSingleRegion, this::endSession);
        });
    }

    private void onSingleRegion(Region region) {
        surface.hide();
        captureTargetAsync(shot -> {
            try {
                if (shot == null) { warnClosed(); return; }
                BufferedImage full = shot.image();
                BufferedImage cropped = cropToImage(full, region);
                if (cropped == null) return;
                Optional<String> name = ImageTemplatePicker.promptTemplateName(owner, config, null);
                if (name.isEmpty()) return;
                ImageTemplateLibrary.saveTemplate(config, cropped, name.get(),
                        full.getWidth(), full.getHeight(), windowTitleOrNull());
                eventBus.publish(new ResourcesChangedEvent());
            } catch (Exception ex) {
                warn(owner, "Failed to save template: " + ex.getMessage());
            } finally {
                endSession();
            }
        });
    }

    // ── Capture many ───────────────────────────────────────────────────────────────────────────────────

    private void beginMany() {
        toolbarStage.hide();
        captureTargetAsync(shot -> {
            if (shot == null) { warnClosed(); endSession(); return; }
            surface = CaptureSurface.many(owner, shot.bounds(), this::onManyDone, this::endSession);
        });
    }

    private void onManyDone(List<Region> regions) {
        surface.hide();
        if (regions.isEmpty()) { endSession(); return; }
        captureTargetAsync(shot -> {
            try {
                if (shot == null) { warnClosed(); return; }
                BufferedImage full = shot.image();
                List<BufferedImage> crops = new ArrayList<>();
                for (Region r : regions) {
                    BufferedImage c = cropToImage(full, r);
                    if (c != null) crops.add(c);
                }
                if (crops.isEmpty()) return;
                List<NamedTemplate> kept = BatchTemplateNamingDialog.show(owner, config, crops);
                int saved = 0;
                for (NamedTemplate t : kept) {
                    ImageTemplateLibrary.saveTemplate(config, t.image(), t.name(),
                            full.getWidth(), full.getHeight(), windowTitleOrNull());
                    saved++;
                }
                if (saved > 0) eventBus.publish(new ResourcesChangedEvent());
            } catch (Exception ex) {
                warn(owner, "Failed to save templates: " + ex.getMessage());
            } finally {
                endSession();
            }
        });
    }

    // ── Capture object (transparent-background extraction) ───────────────────────────────────────────────

    private void beginObject() {
        toolbarStage.hide();
        captureTargetAsync(shot -> {
            if (shot == null) { warnClosed(); endSession(); return; }
            objectFrameW = shot.image().getWidth();
            objectFrameH = shot.image().getHeight();
            objectSurface = ObjectCaptureSurface.open(owner, shot.bounds(), shot.image(),
                    this::onObjectExtracted, this::endSession);
        });
    }

    /** Saves the extracted transparent-background object as a template (named like the other modes). */
    private void onObjectExtracted(BufferedImage cut) {
        if (objectSurface != null) objectSurface.hide();
        try {
            Optional<String> name = ImageTemplatePicker.promptTemplateName(owner, config, null);
            if (name.isEmpty()) { endSession(); return; }
            // The sidecar's capture resolution is the full frame the object was cut from (drives runtime scaling),
            // not the crop's own size.
            ImageTemplateLibrary.saveTemplate(config, cut, name.get(),
                    objectFrameW, objectFrameH, windowTitleOrNull());
            eventBus.publish(new ResourcesChangedEvent());
        } catch (Exception ex) {
            warn(owner, "Failed to save object: " + ex.getMessage());
        } finally {
            endSession();
        }
    }

    // ── Shared plumbing ────────────────────────────────────────────────────────────────────────────────

    /** Disposes the active surface (if any) and returns to the mini-toolbar. */
    private void endSession() {
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (objectSurface != null) {
            objectSurface.close();
            objectSurface = null;
        }
        toolbarStage.show();
    }

    /**
     * Re-captures the live target off the FX thread (so focus + settle don't freeze the UI), then delivers the
     * shot (possibly {@code null} on failure) back on the FX thread. A window target is snapped to the project's
     * canonical resolution first and grabbed occlusion-safe via {@link ScreenCaptureService#captureWindow}; a
     * screen/desktop target is grabbed at its native size via {@link ScreenCaptureService#captureDefaultTargetAsync}.
     */
    private void captureTargetAsync(Consumer<ScreenCaptureService.TargetShot> onFx) {
        if (target instanceof CaptureTarget.WindowTarget wt) {
            Thread t = new Thread(() -> {
                // Snap the window to the project's canonical resolution first, so the drawn surface and the saved
                // template share one resolution regardless of the window's current size.
                if (referenceResolution != null) {
                    capture.resizeTarget(wt, referenceResolution.width(), referenceResolution.height());
                }
                WindowShot shot = capture.captureWindow(wt);
                ScreenCaptureService.TargetShot ts = (shot == null) ? null
                        : new ScreenCaptureService.TargetShot(shot.image(), shot.bounds(),
                        CaptureTargetNames.shortLabel(wt), true);
                Platform.runLater(() -> onFx.accept(ts));
            }, "overlay-template-capture");
            t.setDaemon(true);
            t.start();
        } else {
            capture.captureDefaultTargetAsync(owner, onFx);
        }
    }

    /** The associated window title for saved templates, or {@code null} for a screen/desktop target. */
    private String windowTitleOrNull() {
        return (target instanceof CaptureTarget.WindowTarget wt) ? wt.titleSubstring() : null;
    }

    private void warnClosed() {
        warn(owner, "Capture failed — the target may have closed.");
    }

    /** Maps a drawn {@link Region} (overlay-logical pixels) onto {@code full} (physical pixels) and crops it. */
    private static BufferedImage cropToImage(BufferedImage full, Region r) {
        if (r.paneW() <= 0 || r.paneH() <= 0) return null;
        double scaleX = full.getWidth() / r.paneW();
        double scaleY = full.getHeight() / r.paneH();
        int x = (int) Math.round(r.x() * scaleX);
        int y = (int) Math.round(r.y() * scaleY);
        int w = (int) Math.round(r.w() * scaleX);
        int h = (int) Math.round(r.h() * scaleY);
        x = Math.max(0, Math.min(x, full.getWidth() - 1));
        y = Math.max(0, Math.min(y, full.getHeight() - 1));
        w = Math.max(1, Math.min(w, full.getWidth() - x));
        h = Math.max(1, Math.min(h, full.getHeight() - y));
        return full.getSubimage(x, y, w, h);
    }

    private static void warn(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
    }
}
