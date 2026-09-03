package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents.LibrariesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.UserLibrary;

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

    /**
     * The BotMaker SDK version currently declared in the project pom, or {@code ""} when it declares none.
     *
     * <p>Blank rather than {@code Optional} because of where it goes: straight back into
     * {@link #updateLibraries}, whose {@code sdkVersion} parameter has always treated blank as <i>pin
     * nothing</i>. A blank project therefore edits its libraries without acquiring an SDK, which is the
     * behaviour that matters here and which the pom writer enforces rather than trusting — it only ever
     * re-versions a dependency the pom already declares.
     */
    public String currentSdkVersion() {
        return MavenService.readSdkVersion(config.projectPath()).orElse("");
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
            // The SDK pin may have just moved, so the plugins answering for this project have to move with
            // it — a palette built from the previous jar would offer members the new one may not have.
            PluginHost.bind(classpath);
            typeIndex.refresh(classpath);

            eventBus.publish(new LibrariesChangedEvent(userLibs));
        });
    }

    /**
     * Re-resolves the classpath and re-binds the plugins, without touching the pom.
     *
     * <p><b>What this is for:</b> a plugin author rebuilds their plugin into {@code ~/.m2} and wants Studio to
     * pick it up. The project's dependencies have not changed — the same coordinate resolves to the same jar
     * <em>path</em> — so there is nothing to write, and calling {@link #updateLibraries} with the libraries
     * that are already declared would rewrite the pom to say what it already says.
     *
     * <p>It works because the jar's <b>bytes</b> are what changed: {@link PluginHost#bind} closes the previous
     * loader and opens a fresh one over the same paths, rebuilding every memoised catalog behind it. No
     * restart, and no tag pushed — the same property the SDK has had all along through {@code ~/.m2}.
     *
     * <p>Publishes {@link LibrariesChangedEvent} for the same reason a real library change does: every
     * listener that reacts to the palette moving has to react to this too.
     */
    public CompletableFuture<Void> reloadPlugins() {
        return CompletableFuture.runAsync(() -> {
            List<String> classpath = MavenService.resolveClasspath(config.projectPath());
            state.setResolvedClasspath(classpath);
            PluginHost.bind(classpath);
            typeIndex.refresh(classpath);

            eventBus.publish(new LibrariesChangedEvent(currentLibraries()));
        });
    }
}
