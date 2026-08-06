package com.botmaker.studio.project;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The one-time source rewrites a project may need when it is opened.
 *
 * <p>Not a UI concern — it lived in {@code UIManager} only because that class happened to run at the right
 * moment. That moment is the constraint worth keeping: on open, after the project is known and <em>before</em>
 * the file explorer is built, since a migration can delete a file the tree would otherwise go on listing.
 *
 * <p>Each migration is best-effort and independent. A failure is reported to the console and the project still
 * opens — a bot that doesn't compile is a better outcome than a bot that won't load.
 */
public final class ProjectOpenMigrations {

    private ProjectOpenMigrations() { }

    /** Runs every open-time migration for {@code config}, refreshing the editor's cache as each rewrites. */
    public static void run(ProjectConfig config, ProjectState state, EventBus eventBus) {
        // The click/vision tuning is a project setting the SDK reads before the first click. Older projects
        // carry it as a generated BotSettings.java (or, older still, an inline ClickConfig call in main).
        migrate(config, state, eventBus, BotSettings::migrate,
                "Could not move this project's input settings into its project properties: ");
        // A project created before GameLoop.java and Startup.java were retired binds a 3-arg Bot.start the SDK
        // no longer has, so it doesn't compile until this runs.
        migrate(config, state, eventBus, ScaffoldMigration::migrate,
                "Could not update this project's entry point to the current scaffold: ");
    }

    /** One migration: rewrite {@code Main.java} on disk, then tell the editor its cached copy is stale. */
    private static void migrate(ProjectConfig config, ProjectState state, EventBus eventBus,
                                Migration migration, String failureMessage) {
        try {
            String migratedMain = migration.apply(config);
            if (migratedMain != null) refreshCachedSource(state, eventBus, config.mainSourceFile(), migratedMain);
        } catch (IOException ex) {
            System.err.println(failureMessage + ex.getMessage());
        }
    }

    /** A rewrite of the project's entry point: returns the new source, or {@code null} if nothing was needed. */
    @FunctionalInterface
    private interface Migration {
        String apply(ProjectConfig config) throws IOException;
    }

    /**
     * Tells the editor that {@code file} was rewritten on disk behind its back.
     *
     * <p>The editor caches file contents in memory, so a disk-only write would be invisible — and would be
     * overwritten by the next edit that flushes the stale copy. Update the cached copy, and re-render when it
     * happens to be the file on screen. A file the editor hasn't loaded needs nothing.
     */
    private static void refreshCachedSource(ProjectState state, EventBus eventBus, Path file, String updated) {
        if (file == null || updated == null) return;
        state.getAllFiles().stream()
                .filter(f -> f.getPath().equals(file))
                .findFirst()
                .ifPresent(f -> {
                    String previous = f.getContent();
                    f.setContent(updated);
                    var active = state.getActiveFile();
                    if (active != null && active.getPath().equals(file)) {
                        eventBus.publish(new CoreApplicationEvents.CodeUpdatedEvent(updated, previous));
                    }
                });
    }
}
