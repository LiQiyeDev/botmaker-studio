package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.SettingsChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's editor {@link StudioProjectSettings} — currently the saved
 * {@link CaptureTarget}s and which one is the default used by every on-screen picker. Persistence is a
 * single {@code settings.json} under {@code src/main/resources}. All I/O lives here at the service edge;
 * {@link #update} runs off the calling thread and publishes {@link SettingsChangedEvent} once state is
 * refreshed. Modeled on {@link ActivityService}.
 */
public final class ProjectSettingsService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    public ProjectSettingsService(ProjectConfig config, ProjectState state, EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
    }

    /** A settings service bound to {@code context}'s project — the single construction site for callers. */
    public static ProjectSettingsService forProject(CodeEditorService context) {
        return new ProjectSettingsService(context.getConfig(), context.getState(), context.getEventBus());
    }

    /** The current settings (from project state, loaded at open and refreshed on change). */
    public StudioProjectSettings current() {
        StudioProjectSettings s = state.getSettings();
        return s != null ? s : StudioProjectSettings.empty();
    }

    /** The default capture target, or {@code null} if none is set (pickers then show the chooser). */
    public CaptureTarget defaultTarget() {
        return current().defaultTarget();
    }

    /** Loads settings from disk into project state (called once at project open). */
    public StudioProjectSettings load() {
        StudioProjectSettings loaded = StudioProjectSettings.read(config.resourcesRoot());
        state.setSettings(loaded);
        return loaded;
    }

    /**
     * Persists {@code newSettings} ({@code settings.json}), refreshes project state and publishes
     * {@link SettingsChangedEvent}. Runs asynchronously; the returned future completes exceptionally if
     * writing fails. Capture-source blocks emit inline expressions (see
     * {@link com.botmaker.studio.project.capture.CaptureExpr}), so there is no generated sidecar to sync.
     */
    public CompletableFuture<Void> update(StudioProjectSettings newSettings) {
        return CompletableFuture.runAsync(() -> {
            try {
                newSettings.write(config.resourcesRoot());
                // Mirror the standard resolution into botmaker-project.properties so the generated bot's runtime
                // scaling default (ProjectDefaults/ResolutionScaler) tracks the editor's standard resolution.
                if (newSettings.referenceResolution() != null) {
                    com.botmaker.studio.project.ProjectCreator.writeCaptureProperties(
                            config.resourcesRoot(), newSettings.referenceResolution());
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to save settings: " + e.getMessage(), e);
            }
            state.setSettings(newSettings);
            eventBus.publish(new SettingsChangedEvent(newSettings));
        });
    }
}
