package com.botmaker.studio.project.migration;

import com.botmaker.studio.project.ProjectConfig;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * One numbered step in a {@link SchemaFile}'s history: it takes a project from version <i>n</i> to <i>n+1</i>
 * of that file.
 *
 * <p>The number is the step's <b>position</b> in {@link SchemaMigrations#stepsFor}, not a field it declares —
 * so a step cannot claim a version its neighbours disagree with, and a gap in the numbering is not
 * expressible. That is the difference between this and the sniffing it replaces: a sniffing migration asks the
 * project "do you look old?", which can only ever guess and has to keep guessing forever; a numbered step asks
 * a recorded number, applies once, and is then done.
 *
 * <p>A step is <b>best-effort and independent</b>, inheriting the rule the open-time migrations already had: a
 * failure is reported and the project still opens, because a bot that does not compile is a better outcome
 * than a bot that will not load. It must also be <b>safe to re-run</b> — a project whose file is absent never
 * gets stamped, so its steps are offered again on the next open.
 */
@FunctionalInterface
public interface SchemaMigration {

    /**
     * Applies this step to the project, returning what it actually did — or {@code null} when the project
     * turned out to need nothing, which is the ordinary case for a project already on the current shape.
     *
     * <p>The distinction matters because a version bump is not news: a project opened for the first time by
     * this Studio advances every file whether or not anything was there to change, and reporting "updated to
     * version 1" for a no-op is a status line that means nothing. Only a step that names a change is reported.
     */
    String apply(Context ctx) throws IOException;

    /**
     * What a step is given: the project, and a way to say the entry point was rewritten on disk.
     *
     * <p>The second half exists because the editor caches file contents in memory, so a disk-only write to
     * {@code Main.java} would be invisible — and would be overwritten by the next edit that flushes the stale
     * copy. Passing {@code null} means "nothing changed" and is a no-op.
     *
     * @param config       the project being migrated
     * @param mainRewritten accepts the new entry-point source, or {@code null} when the step did not touch it
     */
    record Context(ProjectConfig config, Consumer<String> mainRewritten) {}
}
