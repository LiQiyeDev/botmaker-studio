package com.botmaker.studio.project.seed;

import com.botmaker.plugin.api.catalog.ScaffoldPlan;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.MavenService;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings a project's files in line with what every loaded plugin says it should have.
 *
 * <p>The plugin-agnostic replacement for {@code Regeneration.ensureStubs} plus
 * {@code services/ActivityStubSync} plus {@code ActivityService.deleteRemovedStubs} — three passes that each
 * knew the SDK's activities by name. This one knows nothing: it asks {@link PluginHost#seedPlan} what files
 * this project must have, compares that against what it has, and does one of three things per seed.
 *
 * <h2>The three outcomes, and the ledger that tells them apart</h2>
 *
 * <ul>
 *   <li><b>Create</b> — nothing has been written for this key. {@link SeedWriter} renders the plugin's own
 *       source into the planned path. A file already sitting there is never overwritten: it is somebody's,
 *       and whose is not a question this pass can answer.</li>
 *   <li><b>Reconcile</b> — the key's file is where the plan wants it. {@link SeedReconciler} updates the
 *       substituted enums and nothing else. This is almost every save, and it almost always changes nothing.</li>
 *   <li><b>Rename</b> — the key's file is somewhere else, because the thing it stands for was renamed. The
 *       file moves, its type is renamed inside it, and every other file in the bot that named it is
 *       rewritten. Without the ledger this case is indistinguishable from a deletion plus a creation, and the
 *       only safe response to <em>that</em> is to orphan the body the user wrote and hand them an empty one.</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 *
 * <p><b>It deletes nothing.</b> A seed is written once and is the user's from that moment, so a key that has
 * gone leaves its file where it is and drops only the claim that BotMaker put it there. That is a deliberate
 * change from what Studio did: {@code deleteRemovedStubs} removed a dropped activity's {@code .java}, and had
 * to, because the file read a field on a generated {@code Activities} class that stopped existing. Nothing is
 * generated any more — a seed's {@code isEnabled()} asks the project's own configuration at run time — so a
 * file whose key is gone still compiles, and deleting the user's code because they unchecked something on a
 * canvas is not a trade worth making.
 *
 * <p><b>It never fails a save.</b> Every step is best-effort and reports rather than throws: a project must
 * open, and a plugin misbehaving must not be why a user cannot press save.
 */
public final class SeedSync {

    private SeedSync() {}

    /** What one pass did, for a caller that wants to say so. */
    public record Result(List<Path> created, List<Path> updated, List<Path> renamed, List<String> problems) {

        public Result {
            created = List.copyOf(created);
            updated = List.copyOf(updated);
            renamed = List.copyOf(renamed);
            problems = List.copyOf(problems);
        }

        public boolean isEmpty() {
            return created.isEmpty() && updated.isEmpty() && renamed.isEmpty();
        }
    }

    /**
     * Reconciles every seed of every plugin against {@code config}'s project.
     *
     * <p>{@code state} may be null. When it is not, a rename's reference rewriting goes through the editor's
     * open buffers as well as the files — see {@link BotSources}, whose whole reason for existing is that a
     * sweep reading only the disk misses the user's last ten minutes of work.
     */
    public static Result sync(ProjectConfig config, ProjectState state) {
        List<Path> created = new ArrayList<>();
        List<Path> updated = new ArrayList<>();
        List<Path> renamed = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        Path projectDir = config.projectPath();
        String pin = MavenService.readSdkVersion(projectDir);
        PluginHost.SeedPlan plan = PluginHost.seedPlan(pin, projectDir, config.mainPackage());
        problems.addAll(plan.problems());
        if (plan.seeds().isEmpty()) return new Result(created, updated, renamed, problems);

        SeedLedger ledger = SeedLedger.read(projectDir);
        boolean ledgerChanged = false;

        for (PluginHost.PlannedSeed planned : plan.seeds()) {
            ScaffoldPlan.PlannedFile file = planned.file();
            Path wanted = projectDir.resolve(file.path());
            String remembered = ledger.pathFor(planned.pluginId(), file.seeding().key());
            Path previous = remembered == null ? null : projectDir.resolve(remembered);

            try {
                if (previous != null && !previous.equals(wanted) && Files.isRegularFile(previous)) {
                    rename(config, state, previous, wanted, file);
                    renamed.add(wanted);
                } else if (Files.isRegularFile(wanted)) {
                    if (reconcile(wanted, file)) updated.add(wanted);
                } else {
                    if (create(wanted, file)) created.add(wanted);
                    else problems.add("could not render " + file.path());
                }
            } catch (IOException | RuntimeException e) {
                problems.add(file.path() + ": " + e);
                continue;
            }

            if (!file.path().equals(remembered)) {
                ledger.put(planned.pluginId(), file.seeding().key(), file.path());
                ledgerChanged = true;
            }
        }

        if (ledgerChanged) {
            try {
                ledger.write(projectDir);
            } catch (IOException e) {
                // A lost ledger makes every seed look new next time, which creates nothing and overwrites
                // nothing. Worth a line, never worth failing the save that just succeeded.
                problems.add("could not record where the seeds went: " + e.getMessage());
            }
        }
        return new Result(created, updated, renamed, problems);
    }

    private static boolean create(Path target, ScaffoldPlan.PlannedFile file) throws IOException {
        String source = SeedWriter.render(file);
        if (source == null) return false;
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
        return true;
    }

    private static boolean reconcile(Path target, ScaffoldPlan.PlannedFile file) throws IOException {
        String current = Files.readString(target);
        String next = SeedReconciler.reconcile(current, file);
        if (next.equals(current)) return false;
        Files.writeString(target, next);
        return true;
    }

    /**
     * Moves a seed's file to where the plan now wants it, renames the type inside it, and repoints every
     * reference in the bot.
     *
     * <p>Order matters and is the opposite of the obvious one: <b>the references are rewritten first, while
     * the file is still where the ledger says</b>. {@link BotSources} walks the source root and the open
     * buffers, so moving first would make the file itself one of the things being swept under its new name
     * and its old contents at once.
     *
     * <p><b>The two halves use two different primitives, and swapping them silently half-works.</b> Every
     * other file goes through {@link CallMigrator#renameTypeIn}, the same primitive an SDK migration uses, so
     * a type written as a field, a cast or a type argument moves too — the places no call scan records. The
     * moved file itself goes through {@link SeedWriter#renameType}, because {@code renameTypeIn} does not
     * rewrite a type's own <em>declaration</em>: it was built for SDK types, which a bot never declares. Using
     * it here leaves {@code class Mining extends Activity<Smelting.Outcome>}, which compiles nowhere.
     */
    private static void rename(ProjectConfig config, ProjectState state, Path from, Path to,
                               ScaffoldPlan.PlannedFile file) throws IOException {
        String fromFqn = fqnOf(config, from);
        String toFqn = SeedWriter.packageOf(file.path()) + "." + file.typeName();
        if (fromFqn == null || fromFqn.equals(toFqn)) return;

        Path normalizedFrom = from.toAbsolutePath().normalize();
        BotSources.forEach(config, state, (path, source) ->
                path.equals(normalizedFrom) ? null : renameIn(source, fromFqn, toFqn));

        String current = Files.readString(from);
        String moved = SeedWriter.renameType(current, simpleNameOf(fromFqn), file.typeName());
        Files.createDirectories(to.getParent());
        Files.writeString(to, moved == null ? current : moved);
        Files.deleteIfExists(from);
    }

    /** {@code source} with {@code fromFqn} renamed to {@code toFqn}, or null when nothing changed. */
    private static String renameIn(String source, String fromFqn, String toFqn) {
        if (source == null || !source.contains(simpleNameOf(fromFqn))) return null;
        CompilationUnit cu = SourceParser.parse(source);
        if (SourceParser.hasSyntaxErrors(cu)) return null;   // the compiler's business, not ours
        EditContext ctx = EditContext.of(cu, null, null);
        CallMigrator.renameTypeIn(ctx, fromFqn, toFqn);
        String rewritten = AstRewriteHelper.applyRewrite(ctx.rewriter(), source);
        return rewritten == null || rewritten.equals(source) ? null : rewritten;
    }

    /** The fully-qualified name a file under the source root declares, or null when it is not under it. */
    private static String fqnOf(ProjectConfig config, Path file) {
        Path root = config.sourceRoot();
        if (root == null) return null;
        Path relative = root.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        String path = relative.toString().replace('\\', '/');
        if (!path.endsWith(".java") || path.startsWith("..")) return null;
        return path.substring(0, path.length() - ".java".length()).replace('/', '.');
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** The paths every plugin's plan claims, for a caller asking whether a project has what it needs. */
    public static List<String> plannedPaths(ProjectConfig config) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (PluginHost.PlannedSeed planned : PluginHost.seedFiles(
                MavenService.readSdkVersion(config.projectPath()),
                config.projectPath(), config.mainPackage())) {
            ordered.put(planned.file().path(), planned.pluginId());
        }
        return List.copyOf(ordered.keySet());
    }
}
