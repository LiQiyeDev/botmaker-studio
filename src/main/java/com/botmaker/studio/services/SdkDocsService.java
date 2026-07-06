package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.SdkDocsParser;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.project.ProjectConfig;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Per-project owner of the SDK method documentation ({@link SdkDocs}). The Studio does not depend on the
 * SDK, so descriptions/param-docs come from the resolved {@code botmaker-sdk:<version>:sources} jar:
 * this service resolves it via {@link MavenService#resolveSdkSourcesJar} and parses it with
 * {@link SdkDocsParser} off the FX thread, then serves the cached result to blocks via
 * {@link #current()} (blocks reach it through {@code CodeEditorService.getSdkDocs()}).
 *
 * <p>Non-blocking for callers: {@link #current()} returns {@link SdkDocs#EMPTY} until the background
 * parse finishes. Re-parses when the project's SDK version changes (on {@code LibrariesChangedEvent}).
 */
public final class SdkDocsService {

    private final ProjectConfig config;
    private final ExecutorService loader =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sdk-docs-loader");
                t.setDaemon(true);
                return t;
            });

    private volatile SdkDocs docs = SdkDocs.EMPTY;
    private volatile String loadedVersion = null;

    public SdkDocsService(ProjectConfig config, EventBus eventBus) {
        this.config = config;
        // The SDK version can change when the user edits libraries / picks a version.
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, e -> refresh(), false);
        refresh();
    }

    /** The current SDK docs, or {@link SdkDocs#EMPTY} while loading / when sources can't be resolved. */
    public SdkDocs current() {
        return docs;
    }

    /** Kick off a background (re)load if the declared SDK version differs from what's loaded. */
    public void refresh() {
        String version = MavenService.readSdkVersion(config.projectPath());
        if (version.equals(loadedVersion) && docs != SdkDocs.EMPTY) {
            return;
        }
        loader.submit(() -> load(version));
    }

    private void load(String version) {
        try {
            Optional<Path> sources = MavenService.resolveSdkSourcesJar(config.projectPath());
            SdkDocs parsed = sources.map(SdkDocsParser::fromSourcesJar).orElse(SdkDocs.EMPTY);
            this.docs = parsed;
            this.loadedVersion = version;
        } catch (Exception e) {
            System.err.println("SdkDocsService: failed to load SDK docs: " + e.getMessage());
        }
    }
}
