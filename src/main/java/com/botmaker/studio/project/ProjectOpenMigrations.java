package com.botmaker.studio.project;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ImageTemplateLibrary;

import java.io.IOException;
import java.nio.file.Files;
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
        // Every project needs a Templates class before a block can write `new ImageTemplate(Templates.X)`,
        // including one whose templates were all captured before the class existed. Regenerating on open
        // (not just on capture) is also what repairs a hand-deleted or hand-edited copy.
        ImageTemplateLibrary.regenerateTemplatesClass(config);
        // Archiving an activity is gone. A project that used it has stubs parked outside the source tree with
        // no way left to bring them back, so bring them back here.
        restoreArchivedActivityStubs(config);
    }

    /**
     * Moves anything left in {@code .botmaker/archived-activities} back into the project's {@code activities}
     * package, then removes the directory.
     *
     * <p>Archiving used to move an activity's hand-written {@code <Name>.java} there while its definition
     * stayed in {@code activities.json} carrying {@code archived: true}. That flag is no longer read, so on
     * the next open the activity is simply live again — and its stub has to be live with it, or the project
     * has an activity whose class does not exist. A stub already present in the package wins — it is the one
     * the compiler has been seeing — and the parked copy is then left where it is rather than overwriting it;
     * the directory survives, holding only what could not be placed, so nothing the user wrote is destroyed.
     */
    private static void restoreArchivedActivityStubs(ProjectConfig config) {
        Path attic = config.archivedActivitiesDir();
        if (!Files.isDirectory(attic)) return;
        try {
            Path live = config.activitiesPackageDir();
            Files.createDirectories(live);
            try (var entries = Files.list(attic)) {
                for (Path parked : entries.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path target = live.resolve(parked.getFileName().toString());
                    if (!Files.exists(target)) Files.move(parked, target);
                }
            }
            try (var leftovers = Files.list(attic)) {
                if (leftovers.findAny().isEmpty()) Files.delete(attic);
            }
        } catch (IOException ex) {
            System.err.println("Could not restore this project's archived activities: " + ex.getMessage());
        }
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
