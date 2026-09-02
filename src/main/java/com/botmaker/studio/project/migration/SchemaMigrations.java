package com.botmaker.studio.project.migration;

import com.botmaker.studio.project.BotSettings;
import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The numbered history of each {@link SchemaFile}: step <i>i</i> takes that file's version <i>i</i> to
 * <i>i+1</i>, and the list's length <em>is</em> the current version.
 *
 * <p>Every step here is one of the migrations that already ran on open, re-expressed against a number instead
 * of a sniff. Nothing was deleted, so first-open behaviour on an existing project is unchanged — what changes
 * is that the second open does not run them again.
 *
 * <p><b>What used to be deliberately not here, and no longer exists.</b> Two passes were excluded on the
 * grounds that they versioned the generated <em>scaffold</em> rather than one of these three files, and so
 * ran unconditionally on every open: {@code ScaffoldMigration} (retiring {@code GameLoop.java} and
 * {@code Startup.java}) and {@code ImageTemplateLibrary.regenerateTemplatesClass} (idempotent re-emission
 * of a file with two authors). Both are deleted with the generator, on 2026-08-29. Nothing outside this
 * class rewrites a project's Java now, which is why the *"a project's structure belongs to the user"* step
 * below can be a report and nothing more.
 */
public final class SchemaMigrations {

    private SchemaMigrations() {}

    /**
     * <b>0 → 1: the archived-activity attic is emptied.</b> Archiving used to move an activity's hand-written
     * {@code <Name>.java} into {@code .botmaker/archived-activities} while its definition stayed in
     * {@code activities.json} carrying {@code archived: true}. That flag is no longer read, so the activity is
     * simply live again on the next open — and its stub has to be live with it, or the project has an activity
     * whose class does not exist. Dropping the flag from the model is the shape change; this is its fallout.
     *
     * <p><b>1 → 2: nothing, since 2026-08-29.</b> It used to split the values out of a generated
     * {@code Activities} into a generated {@code Parameters} and repoint every {@code Activities.<value>} in
     * the bot's own source. Neither class is generated any more — a value is read at run time
     * ({@code Wire.whole("minHealth")}) — so the step's destination no longer exists, and running it would
     * rewrite a user's working source into a shape nothing produces.
     *
     * <p>A project still holding an {@code Activities.java} keeps it, as an ordinary source file it owns.
     * That is the standing answer for everything the old generator wrote: it stops being rewritten, and
     * nobody deletes it.
     *
     * <p>The step stays in the list rather than being removed, because the position <em>is</em> the schema
     * version: dropping it would renumber every step after it and re-run them on projects that have already
     * had them.
     *
     * <p><b>There is deliberately no step for activity ids.</b> {@code ActivityDefinition.id} arrived on
     * 2026-08-29 and an existing project has none — and needs none: an absent id reads as the activity's
     * name, which is stable, is what the identity has always effectively been, and makes a rename behave
     * exactly as it did before. An activity created from that point on gets a real id, so the projects that
     * gain the better behaviour are the ones being worked on. A step that rewrote every stored activity to
     * add a field nobody can see would be a write to a user's file bought with nothing.
     *
     * <p><b>2 → 3: the files the old generator wrote become the user's, and are said to be (2026-08-30).</b>
     * The step writes nothing at all — it reads the project's source directory and reports what it finds.
     * That is the entire migration, because the change it announces already happened everywhere else: with
     * the generator deleted, nothing rewrites {@code Activities.java}, {@code Parameters.java},
     * {@code Templates.java}, {@code ActivityRegistry.java}, {@code FlowDriver.java} or the
     * {@code activities} package, and {@code FileRole} classes every one of them {@code EDITABLE}.
     *
     * <p>It is a numbered step rather than a check on every open for the one property a number buys:
     * <b>the sentence is said once</b>. A project a user has already been told about is a project where the
     * message is noise, and a message that reappears every open is one that stops being read.
     *
     * <p><b>Deleting them was considered and refused.</b> They compile — every SDK type they name is kept by
     * never-delete — and one of them, {@code Templates.java}, is the only place a bot's picture names are
     * written down. A migration that deleted working code the user can read would be the generator's last
     * act of ownership, taken on the way out.
     */
    private static final List<SchemaMigration> ACTIVITIES_STEPS = List.of(
            ctx -> {
                int restored = restoreArchivedActivityStubs(ctx.config());
                return restored == 0 ? null
                        : "Restored " + restored + " archived activity stub" + (restored == 1 ? "" : "s")
                          + " into the project.";
            },
            ctx -> null,
            ctx -> {
                List<String> inherited = generatedFilesLeftBehind(ctx.config());
                return inherited.isEmpty() ? null
                        : inherited.size() + " file" + (inherited.size() == 1 ? " that BotMaker" : "s that BotMaker")
                          + " used to rewrite " + (inherited.size() == 1 ? "is" : "are") + " yours now — "
                          + String.join(", ", inherited)
                          + ". Nothing regenerates them; rename, edit or delete them as you like.";
            });

    /**
     * The files the old generator wrote that are still in this project, by name, in a stable order.
     *
     * <p>Named rather than detected by a marker comment: the marker was the generator's and a user who
     * edited one of these files may well have removed it, which would make the very projects most worth
     * telling the silent ones. A user's own class that happens to be called {@code Templates.java} is
     * reported too, and that is the right trade — the sentence says the file is theirs, which was already
     * true.
     */
    private static List<String> generatedFilesLeftBehind(ProjectConfig config) {
        Path pkg = config.mainSourceFile().getParent();
        if (pkg == null || !Files.isDirectory(pkg)) return List.of();

        List<String> found = new ArrayList<>();
        for (String name : List.of("Activities.java", "ActivityRegistry.java", "FlowDriver.java",
                "Parameters.java", "Templates.java")) {
            if (Files.isRegularFile(pkg.resolve(name))) found.add(name);
        }
        Path activities = config.activitiesPackageDir();
        if (Files.isDirectory(activities)) {
            try (var entries = Files.list(activities)) {
                long stubs = entries.filter(p -> p.toString().endsWith(".java")).count();
                if (stubs > 0) found.add(stubs + " in activities/");
            } catch (IOException unreadable) {
                // A directory that cannot be listed is not worth failing an open over: the other names
                // still report, and nothing here is about to write anything.
            }
        }
        return found;
    }

    /**
     * No steps yet — {@code settings.json} is still at version 0. Listed explicitly rather than left to a
     * default, so "this file has never changed shape" is a statement and not an omission.
     */
    private static final List<SchemaMigration> SETTINGS_STEPS = List.of();

    /**
     * <b>0 → 1 stood here and is a no-op since 2026-09-02.</b> It moved a bot's runtime tuning out of a
     * generated {@code BotSettings.java} — click delays, vision confidence, real input, the retry count —
     * by matching one plugin's facade calls with regexes over the user's own source. That is not a schema
     * migration the editor can own: it knew what a {@code ClickConfig} was, and the editor does not.
     *
     * <p>The step is kept as a no-op rather than removed so the version numbering does not shift under
     * projects that already record themselves as being at 1. A project still at 0 is simply moved to 1 with
     * nothing rewritten; if it carries a generated {@code BotSettings.java}, that file stays and will not
     * compile against a current SDK.
     */
    private static final List<SchemaMigration> PROPERTIES_STEPS = List.of(ctx -> null);

    /** The ordered steps for {@code file}. Index <i>i</i> migrates version <i>i</i> to <i>i+1</i>. */
    public static List<SchemaMigration> stepsFor(SchemaFile file) {
        return switch (file) {
            case ACTIVITIES -> ACTIVITIES_STEPS;
            case SETTINGS -> SETTINGS_STEPS;
            case PROPERTIES -> PROPERTIES_STEPS;
        };
    }

    /**
     * Moves anything left in {@code .botmaker/archived-activities} back into the project's {@code activities}
     * package, then removes the directory.
     *
     * <p>A stub already present in the package wins — it is the one the compiler has been seeing — and the
     * parked copy is then left where it is rather than overwriting it; the directory survives, holding only
     * what could not be placed, so nothing the user wrote is destroyed.
     *
     * @return how many stubs were actually moved back, so the step can stay quiet when there were none
     */
    private static int restoreArchivedActivityStubs(ProjectConfig config) throws IOException {
        Path attic = config.archivedActivitiesDir();
        if (!Files.isDirectory(attic)) return 0;
        Path live = config.activitiesPackageDir();
        Files.createDirectories(live);
        int moved = 0;
        try (var entries = Files.list(attic)) {
            for (Path parked : entries.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path target = live.resolve(parked.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.move(parked, target);
                    moved++;
                }
            }
        }
        try (var leftovers = Files.list(attic)) {
            if (leftovers.findAny().isEmpty()) Files.delete(attic);
        }
        return moved;
    }
}
