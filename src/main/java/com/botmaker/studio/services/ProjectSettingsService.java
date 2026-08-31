package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.SettingsChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectRepair;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.StudioProjectSettings;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the project's editor {@link StudioProjectSettings} — the remembered window titles, the
 * picker preferences, the originating template and the two window layouts. Persistence is a single
 * {@code settings.json} under {@code src/main/resources}. All I/O lives here at the service edge;
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

    /** The project this service is bound to — what a host-side plugin context reports as the open project. */
    public ProjectConfig projectConfig() {
        return config;
    }

    /** The current settings (from project state, loaded at open and refreshed on change). */
    public StudioProjectSettings current() {
        StudioProjectSettings s = state.getSettings();
        return s != null ? s : StudioProjectSettings.empty();
    }

    // defaultTarget() was here until 2026-08-31. It read capture.json, which is the SDK plugin's file, and
    // its last host callers went with the capture stack itself. Nothing in the editor now asks what a bot
    // looks at, which is the whole point of the phase that removed it.

    /**
     * Loads settings from disk into project state (called once at project open), and resolves the project's
     * {@link ProjectTemplate} into state alongside them.
     *
     * <p>Projects created before the template was persisted have none recorded, so the template is inferred from
     * the sources ({@link ProjectRepair#looksLikeGameBot}) — a guess, but only ever for legacy projects, and
     * only once per open rather than on every {@code FileRole} lookup.
     */
    public StudioProjectSettings load() {
        StudioProjectSettings loaded = StudioProjectSettings.read(config.resourcesRoot());
        state.setSettings(loaded);
        state.setTemplate(loaded.template() != null
                ? loaded.template()
                : (ProjectRepair.looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY));
        return loaded;
    }

    /**
     * Persists {@code newSettings} on the calling thread and refreshes project state, without publishing.
     *
     * <p>For teardown: the window is closing or the project is being swapped out, so there is no one left to
     * notify and — the reason this exists at all — no guarantee that an async write would outlive the caller.
     * Best-effort; a failure is reported and the close continues.
     */
    public void saveNow(StudioProjectSettings newSettings) {
        try {
            newSettings.write(config.resourcesRoot());
            state.setSettings(newSettings);
        } catch (IOException e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Persists {@code newSettings} ({@code settings.json}), refreshes project state and publishes
     * {@link SettingsChangedEvent}. Runs asynchronously; the returned future completes exceptionally if
     * writing fails.
     *
     * <p>One file and no mirror. The {@code capture.width}/{@code capture.height} write that used to follow
     * every save went with the reference resolution on 2026-09-01: those keys say what size the pictures
     * were taken at, which is the capturing plugin's answer and never the editor's.
     */
    public CompletableFuture<Void> update(StudioProjectSettings newSettings) {
        return CompletableFuture.runAsync(() -> {
            try {
                newSettings.write(config.resourcesRoot());
            } catch (IOException e) {
                throw new RuntimeException("Failed to save settings: " + e.getMessage(), e);
            }
            state.setSettings(newSettings);
            eventBus.publish(new SettingsChangedEvent(newSettings));
        });
    }
}
