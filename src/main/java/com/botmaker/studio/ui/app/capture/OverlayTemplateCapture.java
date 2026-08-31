package com.botmaker.studio.ui.app.capture;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.sdk.authoring.CaptureModel;
import com.botmaker.sdk.authoring.CaptureTargetModel;
// The two drawing surfaces moved to the SDK plugin on 2026-08-30 — they are the overlay's own, and the
// overlay is a feature of the SDK rather than of Studio. This class follows them when "Capture Templates"
// becomes a ToolbarItem; until then it names them where they now live.
import com.botmaker.sdk.internal.plugin.capture.CaptureSurface;
import com.botmaker.sdk.internal.plugin.capture.CaptureSurface.Region;
import com.botmaker.sdk.internal.plugin.capture.ObjectCaptureSurface;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.services.capture.TargetCapture;
import com.botmaker.studio.services.capture.TargetCapture.TargetShot;
import com.botmaker.studio.services.capture.TargetCapture.WindowShot;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.sdk.internal.plugin.capture.TemplateNaming;
import com.botmaker.sdk.internal.plugin.capture.TemplateNaming.NamedTemplate;
import com.botmaker.studio.ui.app.overlay.OverlayStyles;
import com.botmaker.studio.ui.app.overlay.OverlayToolbars;
import com.botmaker.studio.ui.render.components.ImageTemplatePicker;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *       ({@link TemplateNaming#showBatch}).</li>
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
    /**
     * The host capabilities the two surfaces take, since they are the SDK plugin's and reach the host only
     * through the contract. Built here from the project rather than passed in, because every caller of
     * {@link #open} already hands over the {@link ProjectConfig} it would be built from.
     */
    private final StudioServices services;
    private final ScreenCaptureService capture;
    private final ProjectSettingsService settings;
    /** The default capture target: a window, a monitor, or the whole desktop. */
    private final CaptureTargetModel target;

    /** The same target as a window to raise, snap and grab, or {@code null} when it names no window. */
    private final TargetCapture.WindowRef window;

    private Stage toolbarStage;
    private CaptureSurface surface;
    private ObjectCaptureSurface objectSurface;
    /** The shape the ▢/⬭ toggle currently selects for Capture one/many (object mode ignores it). */
    private CaptureSurface.Shape shape = CaptureSurface.Shape.RECT;
    /** The full captured frame's size for the in-progress object capture (for the template's capture-resolution sidecar). */
    private int objectFrameW, objectFrameH;
    /** The canonical window size to snap to before each capture (project reference resolution), or null.
     *  Only used for a window target — a screen/desktop is never resized. */
    private CaptureModel.Resolution referenceResolution;

    /**
     * The tag a "Capture many" batch is pre-filled with — the activity that was open when the overlay was
     * opened, or null. Fixed at open time on purpose: the overlay is long-lived and deliberately keeps the
     * editor out of the way, so a tag that changed underneath the user mid-session would be a worse default
     * than the one they started from.
     */
    private final String suggestedTag;

    /**
     * Run once when the overlay is finished with the screen, however it ends — closed, or never opened because
     * there was no target. A caller that got out of the way to make room for it (the resource manager hides
     * itself: it is application-modal, so the overlay's toolbar would take no clicks otherwise) uses this to
     * come back. Never null internally; {@link #open(Window, ProjectConfig, ProjectSettingsService,
     * ScreenCaptureService, String)} passes a no-op.
     */
    private final Runnable onClosed;

    private OverlayTemplateCapture(Window owner, ProjectConfig config, ScreenCaptureService capture,
                                   ProjectSettingsService settings,
                                   CaptureTargetModel target, String suggestedTag, Runnable onClosed) {
        this.owner = owner;
        this.config = config;
        this.services = HostServices.forProject(config);
        this.capture = capture;
        this.settings = settings;
        this.target = target;
        this.window = TargetCapture.WindowRef.of(target);
        this.suggestedTag = suggestedTag;
        this.onClosed = onClosed;
    }

    /**
     * Opens the tool for the project's default window target. Shows an explanatory alert (and does nothing
     * else) when the default target isn't a window, or the window can't be found. Must be called on the FX thread.
     */
    public static void open(Window owner, ProjectConfig config, ProjectSettingsService settings,
                            ScreenCaptureService capture, String suggestedTag) {
        open(owner, config, settings, capture, suggestedTag, () -> {});
    }

    /**
     * As {@link #open(Window, ProjectConfig, ProjectSettingsService, ScreenCaptureService, String)},
     * running {@code onClosed} once the overlay is done with the screen — including the two paths where it
     * never opens (no capture target, or one is already up), so a caller that hid itself always comes back.
     */
    public static void open(Window owner, ProjectConfig config, ProjectSettingsService settings,
                            ScreenCaptureService capture, String suggestedTag,
                            Runnable onClosed) {
        Runnable done = onClosed == null ? () -> {} : onClosed;
        CaptureTargetModel target = null;
        try {
            target = settings.defaultTarget();
        } catch (Exception ignored) {
            // no default configured
        }
        if (target == null) {
            warn(owner, "Capture templates needs a capture target.\n\nOpen \"Capture Targets\" and set a window, "
                    + "monitor or the desktop as the default first.");
            done.run();
            return;
        }
        // Single-instance: focus the live overlay instead of stacking another one.
        if (active != null && active.toolbarStage != null && active.toolbarStage.isShowing()) {
            active.toolbarStage.toFront();
            done.run();
            return;
        }
        new OverlayTemplateCapture(owner, config, capture, settings, target, suggestedTag, done).start();
    }

    private void start() {
        // The project reference resolution: the canonical window size every template is captured at (window
        // targets only — a screen/desktop is captured at its native size). Seed it from the window's current
        // size the first time so later captures snap back to this exact size.
        referenceResolution = window != null ? settings.current().referenceResolution() : null;
        // Probe the target once up front so we can fail fast (and place the toolbar near it) before showing
        // anything. Sessions re-resolve the bounds again so the surface tracks a moved window.
        captureTargetAsync(shot -> {
            if (shot == null) {
                warn(owner, "Couldn't capture the target \"" + CaptureTargetModel.shortLabelOf(target) + "\". "
                        + "Is it open / on screen?");
                if (active == this) active = null;
                closed = true;
                onClosed.run();
                return;
            }
            if (window != null && referenceResolution == null) {
                referenceResolution = new CaptureModel.Resolution(shot.bounds().width, shot.bounds().height);
                settings.update(settings.current().withReferenceResolution(referenceResolution));
            }
            showToolbar(shot.bounds());
        });
    }

    private void showToolbar(java.awt.Rectangle windowBounds) {
        HBox shapeToggle = buildShapeToggle();

        Button one = new Button("▢ Capture one");
        one.setOnAction(e -> beginSingle());
        Button many = new Button("▦ Capture many");
        many.setOnAction(e -> beginMany());
        Button object = new Button("◎ Capture object");
        object.setTooltip(new javafx.scene.control.Tooltip(
                "Drag a box around an object to extract it with a transparent background; "
                        + "drag to add, right-drag to remove, Ctrl+Z/Y to undo/redo"));
        object.setOnAction(e -> beginObject());
        Button close = new Button("✕ Close");
        close.setOnAction(e -> closeTool());

        Label hint = new Label("Capture Templates");
        hint.setTextFill(Color.web("#c9d4e6"));
        // Current resolution readout so the user always knows the window/screen size they're capturing at.
        boolean isWindow = window != null;
        Label resLabel = new Label(com.botmaker.studio.ui.app.ResolutionChoices.readout(
                isWindow ? windowBounds : null));
        resLabel.setTextFill(Color.web("#8fa3bf"));
        resLabel.setStyle("-fx-font-size: 11px;");

        HBox bar = new HBox(8, hint, shapeToggle, one, many, object, close, resLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));
        bar.setStyle(OverlayStyles.PANEL);

        // Shared: draggable, always-on-top, and deliberately NOT owned by the Studio window (so Studio can
        // be minimized without hiding the overlay). Positioned just above the target window.
        toolbarStage = OverlayToolbars.show(bar, windowBounds);
        toolbarStage.getScene().setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) closeTool(); });
        active = this;
    }

    /**
     * The ▢/⬭ shape switch that sets {@link #shape} for Capture one/many. A rectangle captures a plain crop;
     * an ellipse captures the inscribed oval/circle with a transparent background. Object capture ignores it.
     */
    private HBox buildShapeToggle() {
        ToggleGroup group = new ToggleGroup();
        ToggleButton rect = new ToggleButton("▢");
        rect.setTooltip(new Tooltip("Rectangle crop"));
        rect.setToggleGroup(group);
        rect.setSelected(shape == CaptureSurface.Shape.RECT);
        ToggleButton ellipse = new ToggleButton("⬭");
        ellipse.setTooltip(new Tooltip("Ellipse crop (transparent background outside the oval/circle; hold Shift to draw a circle)"));
        ellipse.setToggleGroup(group);
        ellipse.setSelected(shape == CaptureSurface.Shape.ELLIPSE);

        rect.setOnAction(e -> { rect.setSelected(true); shape = CaptureSurface.Shape.RECT; });
        ellipse.setOnAction(e -> { ellipse.setSelected(true); shape = CaptureSurface.Shape.ELLIPSE; });

        HBox box = new HBox(0, rect, ellipse);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Set the first time this overlay finishes, so ESC pressed twice doesn't reopen the caller twice. */
    private boolean closed;

    /** Closes the toolbar (and any live surface) and clears the single-instance reference. */
    private void closeTool() {
        if (closed) return;
        closed = true;
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
        onClosed.run();
    }

    // ── Capture one ────────────────────────────────────────────────────────────────────────────────────

    private void beginSingle() {
        toolbarStage.hide();
        captureTargetAsync(shot -> {
            if (shot == null) { warnClosed(); endSession(); return; }
            surface = CaptureSurface.single(services, shot.bounds(), backdropFor(shot), shape,
                    this::onSingleRegion, this::endSession);
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
                Optional<TemplateNaming.NamedCapture> named =
                        ImageTemplatePicker.promptNewTemplate(owner, config, cropped, suggestedTag);
                if (named.isEmpty()) return;
                ImageTemplateLibrary.saveTemplate(config, cropped, named.get().name(),
                        full.getWidth(), full.getHeight(), windowTitleOrNull());
                ImageTemplateLibrary.applyTags(config, Map.of(named.get().name(), named.get().tags()));
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
            surface = CaptureSurface.many(services, shot.bounds(), backdropFor(shot), shape,
                    this::onManyDone, this::endSession);
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
                TemplateNaming.Batch batch =
                        TemplateNaming.showBatch(services, owner, crops, suggestedTag);
                List<String> saved = new ArrayList<>();
                for (NamedTemplate t : batch.templates()) {
                    ImageTemplateLibrary.saveTemplate(config, t.image(), t.name(),
                            full.getWidth(), full.getHeight(), windowTitleOrNull());
                    saved.add(t.name());
                }
                ImageTemplateLibrary.applyTags(config, batch.tagsFor(saved));
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
            objectSurface = ObjectCaptureSurface.open(services, shot.bounds(), shot.image(),
                    this::onObjectExtracted, this::endSession);
        });
    }

    /** Saves the extracted transparent-background object as a template (named like the other modes). */
    private void onObjectExtracted(BufferedImage cut) {
        if (objectSurface != null) objectSurface.hide();
        try {
            Optional<TemplateNaming.NamedCapture> named =
                    ImageTemplatePicker.promptNewTemplate(owner, config, cut, suggestedTag);
            if (named.isEmpty()) { endSession(); return; }
            // The sidecar's capture resolution is the full frame the object was cut from (drives runtime scaling),
            // not the crop's own size.
            ImageTemplateLibrary.saveTemplate(config, cut, named.get().name(),
                    objectFrameW, objectFrameH, windowTitleOrNull());
            ImageTemplateLibrary.applyTags(config, Map.of(named.get().name(), named.get().tags()));
        } catch (Exception ex) {
            warn(owner, "Failed to save object: " + ex.getMessage());
        } finally {
            endSession();
        }
    }

    // ── Shared plumbing ────────────────────────────────────────────────────────────────────────────────

    /**
     * The frame the rubber-band surface must paint itself, or {@code null} when the pixels are really on the
     * desktop behind it and it can stay transparent. Non-null for an emulator target, whose frame comes over
     * ADB and is nowhere on screen — see {@link TargetShot#onScreen()}.
     */
    private static BufferedImage backdropFor(TargetShot shot) {
        return shot.onScreen() ? null : shot.image();
    }

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
    private void captureTargetAsync(Consumer<TargetShot> onFx) {
        if (window != null) {
            TargetCapture.WindowRef wt = window;
            Thread t = new Thread(() -> {
                // Snap the window to the project's canonical resolution first, so the drawn surface and the saved
                // template share one resolution regardless of the window's current size.
                if (referenceResolution != null) {
                    capture.resizeTarget(wt, referenceResolution.width(), referenceResolution.height());
                }
                WindowShot shot = capture.captureWindow(wt);
                TargetShot ts = (shot == null) ? null
                        : new TargetShot(shot.image(), shot.bounds(),
                        target.shortLabel(), true, true);
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
        return window == null ? null : window.titleSubstring();
    }

    private void warnClosed() {
        warn(owner, "Capture failed — the target may have closed.");
    }

    /**
     * Maps a drawn {@link Region} (overlay-logical pixels) onto {@code full} (physical pixels) and crops it.
     * A {@link CaptureSurface.Shape#RECT} region is a plain subimage; an ellipse region is cropped to its
     * bounding box and masked to the inscribed oval, transparent outside it (ARGB, like the object cut).
     */
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
        BufferedImage sub = full.getSubimage(x, y, w, h);
        if (r.shape() != CaptureSurface.Shape.ELLIPSE) return sub;

        // Clip the box to its inscribed ellipse: everything outside the oval stays fully transparent.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new Ellipse2D.Double(0, 0, w, h));
        g.drawImage(sub, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void warn(Window owner, String message) {
        OverlayStyles.warn(owner, message);
    }
}
