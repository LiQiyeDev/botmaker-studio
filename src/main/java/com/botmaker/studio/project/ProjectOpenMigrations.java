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

        // 2. Not schema-versioned, and deliberately so. A project created before GameLoop.java and Startup.java
        //    were retired binds a 3-arg Bot.start the SDK no longer has, so it doesn't compile until this runs
        //    — but what it versions is the generated scaffold, not one of the three data files, and the
        //    scaffold is versioned per hole by the SDK's own templates. It is self-gated (it looks for the
        //    legacy call) and therefore free to run every time.
        try {
            String migratedMain = ScaffoldMigration.migrate(config);
            if (migratedMain != null) {
                refreshCachedSource(state, eventBus, config.mainSourceFile(), migratedMain);
                report.add("Updated this project's entry point to the current scaffold.");
            }
        } catch (IOException ex) {
            System.err.println("Could not update this project's entry point to the current scaffold: "
                    + ex.getMessage());
        }

        // 3. Not a migration at all: idempotent regeneration. Every project needs a Templates class before a
        //    block can write `new ImageTemplate(Templates.X)`, including one whose templates were all captured
        //    before the class existed. Doing it on open (not just on capture) is also what repairs a
        //    hand-deleted or hand-edited copy, which is why it must run every time rather than once.
        ImageTemplateLibrary.regenerateTemplatesClass(config);

        // 4. Restore what was deleted outside the Studio.
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
     * <p><b>Stubs and the generated activity classes come back here too, since 2026-08-26.</b> They used to
     * be skipped, because rebuilding them meant an {@code ActivityService} that is not wired yet at this
     * point in the open; {@link Regeneration} needs no service — it reads the pom for the pin and
     * {@code activities.json} for the model, both of which are files — so the open path can restore
     * everything the menu action can.
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
