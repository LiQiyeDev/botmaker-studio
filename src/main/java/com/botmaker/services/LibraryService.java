package com.botmaker.services;

import com.botmaker.events.CoreApplicationEvents.LibrariesChangedEvent;
import com.botmaker.events.EventBus;
import com.botmaker.index.TypeSummaryManager;
import com.botmaker.project.ProjectConfig;
import com.botmaker.project.ProjectState;
import com.botmaker.project.UserLibrary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates changes to the project's user libraries. The {@code pom.xml} is the source of truth;
 * a change rewrites it, re-resolves the classpath, refreshes the type index, updates project state and
 * announces a {@link LibrariesChangedEvent}.
 *
 * <p>All I/O lives here at the service edge. {@link #updateLibraries} runs the slow
 * resolve/re-index off the calling thread and returns a future the UI can attach to.
 */
public final class LibraryService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final TypeSummaryManager typeIndex;
    private final EventBus eventBus;

    public LibraryService(ProjectConfig config,
                          ProjectState state,
                          TypeSummaryManager typeIndex,
                          EventBus eventBus) {
        this.config = config;
        this.state = state;
        this.typeIndex = typeIndex;
        this.eventBus = eventBus;
    }

    /** The user libraries currently declared in the project pom. */
    public List<UserLibrary> currentLibraries() {
        return MavenService.readUserLibraries(config.projectPath());
    }

    /** The BotMaker SDK version currently declared in the project pom. */
    public String currentSdkVersion() {
        return MavenService.readSdkVersion(config.projectPath());
    }

    /**
     * Persists {@code userLibs} plus the BotMaker SDK version to the pom, then (asynchronously) re-resolves
     * the classpath, refreshes the type index and publishes {@link LibrariesChangedEvent}. The returned
     * future completes once the index is refreshed; it completes exceptionally if writing the pom fails.
     */
    public CompletableFuture<Void> updateLibraries(List<UserLibrary> userLibs, String sdkVersion) {
        return CompletableFuture.runAsync(() -> {
            try {
                MavenService.writeUserLibraries(config.projectPath(), userLibs, sdkVersion);
            } catch (Exception e) {
                throw new RuntimeException("Failed to update pom.xml: " + e.getMessage(), e);
            }

            List<String> classpath = MavenService.resolveClasspath(config.projectPath());
            state.setResolvedClasspath(classpath);
            typeIndex.refresh(classpath);

            eventBus.publish(new LibrariesChangedEvent(userLibs));
        });
    }
}
