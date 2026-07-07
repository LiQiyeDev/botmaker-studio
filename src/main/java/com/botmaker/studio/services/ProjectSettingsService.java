package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.SettingsChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.capture.CaptureTarget;
import com.botmaker.studio.project.capture.CaptureTarget.ScreenTarget;
import com.botmaker.studio.project.capture.CaptureTarget.WindowTarget;

import java.io.IOException;
import java.nio.file.Files;
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

    /**
     * Loads settings from disk into project state (called once at project open). Does <em>not</em> create
     * {@code BotConfig.java} — that sidecar is materialized lazily by {@link #ensureBotConfig()} only when a
     * block first references it, so a project that uses no capture-source blocks never grows the file.
     */
    public StudioProjectSettings load() {
        StudioProjectSettings loaded = StudioProjectSettings.read(config.resourcesRoot());
        state.setSettings(loaded);
        return loaded;
    }

    /**
     * Persists {@code newSettings} ({@code settings.json}), refreshes project state and publishes
     * {@link SettingsChangedEvent}. Runs asynchronously; the returned future completes exceptionally if
     * writing fails.
     */
    public CompletableFuture<Void> update(StudioProjectSettings newSettings) {
        return CompletableFuture.runAsync(() -> {
            try {
                newSettings.write(config.resourcesRoot());
                // Only keep BotConfig.java in sync if it already exists (i.e. a block uses it). Never create
                // it here — a default change on a project with no capture-source blocks must not spawn a file.
                if (Files.exists(config.botConfigSourceFile())) writeBotConfig(newSettings);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save settings: " + e.getMessage(), e);
            }
            state.setSettings(newSettings);
            eventBus.publish(new SettingsChangedEvent(newSettings));
        });
    }

    /**
     * Materializes {@code BotConfig.java} for the current default if it does not already exist. Called by the
     * capture-source picker at the moment a block first emits a {@code BotConfig} helper call, so the sidecar
     * only appears when it is actually needed (and is thereafter kept in sync by {@link #update}).
     */
    public void ensureBotConfig() {
        try {
            if (!Files.exists(config.botConfigSourceFile())) writeBotConfig(current());
        } catch (IOException e) {
            System.err.println("Could not generate BotConfig.java: " + e.getMessage());
        }
    }

    /**
     * (Re)generates the bot project's {@code BotConfig.java} — the capture-source helpers that generated
     * blocks reference. {@code defaultSource()} tracks the project's default target so a default change
     * updates one place; {@code window(...)}/{@code screen(...)} back the concrete, frozen block picks.
     */
    private void writeBotConfig(StudioProjectSettings settings) throws IOException {
        java.nio.file.Path file = config.botConfigSourceFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, generateBotConfig(settings.defaultTarget()));
    }

    /** The Java expression {@code defaultSource()} returns for the current default target. */
    private static String defaultSourceExpr(CaptureTarget def) {
        if (def instanceof WindowTarget wt && wt.titleSubstring() != null && !wt.titleSubstring().isBlank()) {
            return "window(\"" + wt.titleSubstring().replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
        }
        if (def instanceof ScreenTarget st) {
            return "screen(" + st.index() + ")";
        }
        return "CaptureSource.screen()"; // no default → whole desktop
    }

    /** Source of the generated {@code BotConfig} class (mirrors {@code ActivityService}'s generated sidecar). */
    String generateBotConfig(CaptureTarget def) {
        return String.format("""
                package com.%s;

                import com.botmaker.sdk.api.capture.CaptureSource;
                import com.botmaker.sdk.api.capture.Screen;
                import com.botmaker.sdk.api.capture.Window;

                /**
                 * Capture-source helpers for this bot. GENERATED by BotMaker Studio — do not edit by hand;
                 * manage via the 🎯 Capture Targets picker. {@code defaultSource()} is kept in sync with the
                 * project's default capture target, so blocks that use the project default all track it.
                 */
                public final class BotConfig {

                    private BotConfig() {}

                    /** The project's default capture source. */
                    public static CaptureSource defaultSource() {
                        return %s;
                    }

                    /** The window whose title contains {@code titleSubstring}, or the whole screen if none matches. */
                    public static CaptureSource window(String titleSubstring) {
                        return Window.find(titleSubstring).map(w -> (CaptureSource) w).orElseGet(CaptureSource::screen);
                    }

                    /** Monitor {@code index} (0-based) as a capture source. */
                    public static CaptureSource screen(int index) {
                        return Screen.at(index);
                    }
                }
                """, config.packageName(), defaultSourceExpr(def));
    }
}
