package com.botmaker.studio.project;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.migration.ProjectSchema;
import com.botmaker.studio.services.ImageTemplateLibrary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a project may need done to it when it is opened: the numbered schema migrations, the two passes
 * that are not schema-versioned, and a best-effort restore of files that went missing outside the Studio.
 *
 * <p>Not a UI concern — it lived in {@code UIManager} only because that class happened to run at the right
 * moment. That moment is the constraint worth keeping: on open, after the project is known and <em>before</em>
 * the file explorer is built, since a migration can delete a file the tree would otherwise go on listing.
 *
 * <p>Everything here is best-effort and independent. A failure is reported to the console and the project
 * still opens — a bot that doesn't compile is a better outcome than a bot that won't load. The one thing that
 * is <b>not</b> best-effort is {@link ProjectSchema#check}, which refuses a project from the future outright;
 * it runs earlier still, before the project is opened for any audience, and so is not called from here.
 *
 * <p>The return value is what to tell the user, in the order it happened. It is returned rather than published
 * because at this point in the open nothing is subscribed to the status bar yet — the caller publishes it once
 * the shell is wired.
 */
public final class ProjectOpenMigrations {

    private ProjectOpenMigrations() { }

    /** Runs every open-time pass for {@code config}. Returns one line per thing actually done, possibly empty. */
    public static List<String> run(ProjectConfig config, ProjectState state, EventBus eventBus) {
        List<String> report = new ArrayList<>();

        // 1. The numbered steps: each of the three data files advanced to the shape this Studio writes. The
        //    click/vision tuning moving into botmaker-project.properties, and the archived-activity attic
        //    being emptied, both live here now — they used to run on every open and sniff at the project to
        //    decide whether they were needed.
        report.addAll(ProjectSchema.migrate(config,
                updated -> refreshCachedSource(state, eventBus, config.mainSourceFile(), updated)));

        // 2. Restore what was deleted outside the Studio.
        //
        //    Two passes used to sit above this one and both are gone with the generator they served.
        //    ScaffoldMigration rewrote the entry point of a project created before GameLoop.java and
        //    Startup.java were retired, so it would bind the Bot.start the SDK still has; and an idempotent
        //    re-emit of Templates.java ran on every open, because a hand-edited copy of a generated file is
        //    a file with two authors. Neither has a subject any more: an entry point is the user's, written
        //    once, and a picture is named by its file rather than by a constant. A project that will not
        //    compile against the SDK it pins is what the upgrade path is for, and it edits the user's code
        //    only when they ask it to.
        report.addAll(restoreMissingFiles(config, state));
        return report;
    }

    /**
     * Recreates the project files that are simply gone, and says which.
     *
     * <p>The same {@link ProjectRepair} the <i>Project ▸ Recover Project Files</i> menu action uses, run
     * quietly. The two paths are deliberately different in tone and identical in effect: the menu action is
     * the loud one — it lists everything, asks first, and also repairs <em>damaged</em> locked methods, which
     * is a judgement about someone's edit and must never happen behind their back. This one restores only what
     * is <b>absent</b>, where there is no edit to judge and nothing to overwrite: {@link ProjectRepair#recover}
     * re-checks each path and never clobbers a file that exists.
     *
     * <p><b>No {@code .java} is restored here, or anywhere, since 2026-08-29.</b> For three days this pass
     * could rebuild the generated activity classes, because {@code Regeneration} read the pom for the pin
     * and {@code activities.json} for the model and so needed no service the open had not yet wired. Both
     * the class and the capability are gone with the generator: restoring a source file means knowing what
     * it ought to contain, and nothing knows that about a file whose author is the user. What is restored
     * is the four files that are not source — the pom, the project properties, {@code settings.json} and
     * the placeholder image — plus {@code activities.json} itself.
     */
    private static List<String> restoreMissingFiles(ProjectConfig config, ProjectState state) {
        List<String> report = new ArrayList<>();
        try {
            ActivitiesConfig activities = ActivitiesConfig.read(config.resourcesRoot());
            List<ProjectRepair.Missing> missing =
                    ProjectRepair.findMissing(config, state.getTemplate(), activities);
            if (missing.isEmpty()) return report;

            List<Path> restored = ProjectRepair.recover(config, missing);
            if (!restored.isEmpty()) {
                report.add("Restored " + restored.size() + " missing project file"
                        + (restored.size() == 1 ? "" : "s") + ": "
                        + restored.stream().map(p -> p.getFileName().toString()).sorted()
                                  .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            // No "…and these could not be restored" line any more: since 2026-08-26 every file this pass
            // reports has a restorer, so anything still absent afterwards failed loudly and is in the catch.
        } catch (Exception ex) {
            System.err.println("Could not restore this project's missing files: " + ex.getMessage());
        }
        return report;
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
