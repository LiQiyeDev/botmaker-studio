package com.botmaker.studio.project.migration;

import com.botmaker.studio.project.BotSettings;
import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The numbered history of each {@link SchemaFile}: step <i>i</i> takes that file's version <i>i</i> to
 * <i>i+1</i>, and the list's length <em>is</em> the current version.
 *
 * <p>Every step here is one of the migrations that already ran on open, re-expressed against a number instead
 * of a sniff. Nothing was deleted, so first-open behaviour on an existing project is unchanged — what changes
 * is that the second open does not run them again.
 *
 * <p><b>What is deliberately not here.</b> {@code ScaffoldMigration} — retiring {@code GameLoop.java} and
 * {@code Startup.java}, installing the popup guard — is not a step, because the thing it versions is the
 * generated <em>scaffold</em>, which is not one of these three files and is the SDK generator's to version.
 * It stays an unconditional, self-gated pass in {@code ProjectOpenMigrations}. So does
 * {@code ImageTemplateLibrary.regenerateTemplatesClass}, which is not a migration at all: it is idempotent
 * regeneration that also happens to repair a hand-deleted copy, and it must run on every open, not once.
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
     */
    private static final List<SchemaMigration> ACTIVITIES_STEPS = List.of(
            ctx -> {
                int restored = restoreArchivedActivityStubs(ctx.config());
                return restored == 0 ? null
                        : "Restored " + restored + " archived activity stub" + (restored == 1 ? "" : "s")
                          + " into the project.";
            },
            ctx -> null);

    /**
     * No steps yet — {@code settings.json} is still at version 0. Listed explicitly rather than left to a
     * default, so "this file has never changed shape" is a statement and not an omission.
     */
    private static final List<SchemaMigration> SETTINGS_STEPS = List.of();

    /**
     * <b>0 → 1: the bot's runtime tuning moved into this file.</b> Click delays, vision confidence, real input
     * and the retry count used to be a generated {@code BotSettings.java} calling the SDK facade (or, older
     * still, an inline {@code ClickConfig} call in the entry point). {@link BotSettings#migrate} reads
     * whichever form it finds, writes the eight keys here, and rewrites the entry point.
     */
    private static final List<SchemaMigration> PROPERTIES_STEPS = List.of(
            ctx -> {
                String rewrittenMain = BotSettings.migrate(ctx.config());
                ctx.mainRewritten().accept(rewrittenMain);
                return rewrittenMain == null ? null
                        : "Moved this project's input and vision tuning into its project properties.";
            });

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
