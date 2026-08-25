package com.botmaker.studio.project.migration;

import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Reads, refuses and advances the schema versions of a project's three data files.
 *
 * <p>Two operations, deliberately separate and run at different moments:
 * <ul>
 *   <li>{@link #check} — <b>refuse a project from the future</b>. Called before the project is opened at all,
 *       for every audience (the editor and the Runner both), because a file whose shape this Studio does not
 *       know is not more readable to one of them than the other.</li>
 *   <li>{@link #migrate} — <b>bring an old project forward</b>, once. Called on the editor's open path, at
 *       the same moment the sniffing migrations it replaces used to run: after the project is known and
 *       before the file explorer is built, since a step can delete a file the tree would otherwise list.</li>
 * </ul>
 *
 * <p>Migration is best-effort and per step: a step that throws is reported and the rest still run, and the
 * file is stamped only up to the last step that succeeded — so the next open resumes at the one that failed
 * rather than skipping it.
 */
public final class ProjectSchema {

    private ProjectSchema() {}

    /**
     * Refuses the project when any of its data files records a version newer than this Studio understands.
     *
     * <p>The message names the file, both numbers and the way out, and is shown verbatim — see
     * {@link ProjectSchemaTooNew}. An absent file, an unparseable one and an unstamped one are all fine: the
     * first two have no claim to check and the third means 0, which is the oldest shape, not a newer one.
     */
    public static void check(ProjectConfig config) throws ProjectSchemaTooNew {
        Path resources = config.resourcesRoot();
        for (SchemaFile file : SchemaFile.values()) {
            OptionalInt found = file.versionIn(resources);
            if (found.isEmpty()) continue;
            int current = file.current();
            if (found.getAsInt() > current) {
                throw new ProjectSchemaTooNew(
                        "This project's " + file.description() + " (" + file.fileName() + ") is version "
                        + found.getAsInt() + ", and this Studio understands version " + current
                        + ". It was saved by a newer BotMaker Studio — update Studio to open it. "
                        + "Nothing in the project was changed.");
            }
        }
    }

    /**
     * Runs every migration step each file still owes, in order, and stamps what succeeded. Returns one line
     * per step applied, for the status bar; empty when the project was already current.
     *
     * <p>A file that is <b>not on disk</b> counts as version 0 and its steps run anyway — the "absent means 0"
     * rule applies to the whole file as much as to the key inside it. A very old project may have no
     * {@code botmaker-project.properties} at all and still carry the generated {@code BotSettings.java} that
     * the 0 → 1 step exists to absorb; skipping it because the destination file is missing would strand
     * exactly the project the step was written for. What <em>is</em> conditional is the stamp: nothing is
     * created merely to hold a number (see {@link SchemaFile#stampIfPresent}), so a project with no such file
     * is offered its steps again on the next open — which is why every step has to be safe to re-run.
     *
     * @param mainRewritten told the new entry-point source when a step rewrote it ({@code null} = untouched)
     */
    public static List<String> migrate(ProjectConfig config, Consumer<String> mainRewritten) {
        Path resources = config.resourcesRoot();
        List<String> applied = new ArrayList<>();
        for (SchemaFile file : SchemaFile.values()) {
            int from = file.versionIn(resources).orElse(0);
            List<SchemaMigration> steps = SchemaMigrations.stepsFor(file);
            if (from >= steps.size()) continue;

            var ctx = new SchemaMigration.Context(config, mainRewritten);
            int reached = from;
            for (int v = from; v < steps.size(); v++) {
                try {
                    String did = steps.get(v).apply(ctx);
                    reached = v + 1;
                    if (did != null) applied.add(did);
                } catch (Exception ex) {
                    System.err.println("Could not update " + file.fileName() + " from version " + v
                            + " to " + (v + 1) + ": " + ex.getMessage());
                    break;
                }
            }
            if (reached > from) stamp(file, resources, reached, steps.size());
        }
        return applied;
    }

    /**
     * Records the version actually reached. {@link SchemaFile#stampIfPresent} writes {@link SchemaFile#current}
     * and nothing else, which is the right thing for the ordinary case and wrong for a partial run — so a run
     * that stopped short says so in the log and leaves the file where it is, to be finished next open.
     */
    private static void stamp(SchemaFile file, Path resources, int reached, int current) {
        if (reached < current) {
            System.err.println(file.fileName() + " reached version " + reached + " of " + current
                    + "; it stays unstamped so the remaining steps are retried on the next open.");
            return;
        }
        try {
            file.stampIfPresent(resources);
        } catch (IOException ex) {
            System.err.println("Could not record " + file.fileName() + "'s version: " + ex.getMessage());
        }
    }
}
