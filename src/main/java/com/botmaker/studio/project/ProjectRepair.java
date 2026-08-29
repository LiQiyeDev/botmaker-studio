package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.MavenService;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restores the project files that are <b>not</b> the user's source and have gone missing — typically deleted
 * outside the Studio (an {@code rm}, a bad merge, a sync conflict), which nothing else notices:
 * {@code ProjectManager.isValidProject} only checks that {@code src/main/java} and {@code pom.xml} exist.
 *
 * <p><b>Only ever creates what is absent, never overwrites what is there.</b> A file that exists is the
 * user's, whatever is in it.
 *
 * <h2>No {@code .java} is repaired, and none is reported</h2>
 *
 * <p>Since 2026-08-29 nothing writes a project's Java, so nothing knows what it should contain and nothing
 * may put it back. What can still go missing and be restored is everything <em>around</em> the source:
 * {@code pom.xml}, {@code botmaker-project.properties}, {@code settings.json}, {@code activities.json} and
 * the placeholder image template.
 *
 * <p>Two capabilities went with the generator, and both are worth knowing about rather than reinventing.
 * <b>Missing source</b> was restored by asking the project's own SDK to emit the file again — which needed a
 * generator that knew what a project must contain. <b>Damaged locked methods</b> ({@code findDamaged} /
 * {@code repairDamaged}) went further: the file existed, so the never-overwrite rule declared it fine, and a
 * {@code GoHome.run} renamed to {@code goHome} stayed renamed with nothing offering to fix it. That needed a
 * canonical text to diff a method against. Neither has one now, and the idea underneath both — that a file
 * can be partly the user's — is what the change actually removed.
 */
public final class ProjectRepair {

    private ProjectRepair() {}

    /**
     * A file that should exist but doesn't, plus what would restore it.
     *
     * <p>The restorer is nullable and, since 2026-08-26, is never actually null: every file this class
     * reports can be produced again. It stays nullable because {@link #recover} skipping a null is the
     * behaviour that made phase 0b's "report it, cannot restore it" state expressible at all, and the next
     * unrestorable file — a user's captured PNG, say — would want the same shape.
     *
     * <p>A {@link Restorer} rather than the source text it used to be, because not everything recoverable is a
     * string: {@code pom.xml} is built through the Maven Model API and the placeholder template is a generated
     * PNG. {@link #ofSource} keeps the common case a one-liner.
     */
    public record Missing(Path path, Restorer restorer, String reason) {
        public String fileName() { return path.getFileName().toString(); }

        /** The common case: a file whose whole content is known text. */
        public static Missing ofSource(Path path, String source, String reason) {
            return new Missing(path, target -> Files.writeString(target, source), reason);
        }
    }

    /** Writes one missing file. Called only after a re-check that it is still absent. */
    @FunctionalInterface
    public interface Restorer {
        void restore(Path target) throws IOException;
    }

    /**
     * Guesses whether {@code config}'s project is a game-bot project, from its sources: the entry point calls
     * {@code Bot.start}, or the scaffold's two co-generated files are both still present.
     *
     * <p><b>Prefer the persisted template</b> ({@link StudioProjectSettings#template()}, resolved once into
     * {@link ProjectState#getTemplate()} at open). This heuristic is the fallback for projects created before
     * the template was recorded, and it has a real cliff: a game-bot project with <em>every</em> scaffold file
     * deleted and a rewritten main is indistinguishable from an empty one — which is exactly the wrecked
     * project recovery most needs to fix.
     *
     * <p>A guess costs more than it used to: the answer feeds {@link FileRole}, so guessing GAME_BOT makes the
     * named files read-only. One stray file must therefore not be enough — a user's own {@code GameLoop.java}
     * in an empty project used to be sufficient here, and the reward was that their only file went read-only.
     * So the evidence is <em>every</em> file the generator claims, all present at once.
     *
     * <p><b>There is no file list to check any more, and the fallback that used one is gone.</b> It asked
     * the project's own SDK which {@code .java} a game bot must have and required all of them present —
     * itself a repair of an older version that named {@code FlowDriver.java} and {@code ActivityRegistry.java}
     * as literals. Nothing writes a project's Java now, so no list exists and no set of files is evidence of
     * anything. What is left is the entry point's own text, which is the evidence that was always the
     * strongest, plus {@code settings.json} where the user's choice is actually recorded.
     */
    public static boolean looksLikeGameBot(ProjectConfig config) {
        Path entry = config.entrySourceFile();
        if (!Files.exists(entry)) return false;
        try {
            // "Bot.start" is the current entry-point call; "Bot.supervise" recognises pre-rename projects.
            String main = Files.readString(entry);
            return main.contains("Bot.start") || main.contains("Bot.supervise");
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Everything that is missing and recoverable, in a stable order. Empty when the project is intact.
     *
     * <p>{@code template} says which scaffold the project is supposed to have; a null template falls back to
     * {@link #looksLikeGameBot}. Anything the Studio generates is fair game here — not just the template's
     * source files, but {@code Activities.java}, {@code ActivityRegistry.java} and {@code activities.json},
     * which are generated by {@code ActivityService} and were previously unchecked. The explorer's delete
     * dialog promises Recover can bring them back, so it has to actually be able to.
     */
    public static List<Missing> findMissing(ProjectConfig config, ProjectTemplate template,
                                            ActivitiesConfig activities) {
        List<Missing> missing = new ArrayList<>();
        Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return missing;

        ProjectTemplate resolved = template != null
                ? template
                : (looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY);

        // No .java is reported and none is restored. BotMaker writes a project's source once, when the
        // project is created, and never reads or rewrites it — so there is no list of files a project "must"
        // have, and a file that is gone is a file its owner deleted. Restoring it would be inventing a
        // starting point for code that has since been written and thrown away.
        //
        // What used to be here, in the order it appeared: two file names as string literals, then the
        // generator's own claimed list with Regeneration.restore behind each entry, then the seed plan. Each
        // was a better answer to a question that has stopped being asked.

        missing.addAll(missingResources(config, template, resolved));

        if (activities != null) {
            // activities.json holds every activity's configured value; losing it silently resets them all to
            // defaults, which no other check would notice. Only expected once there is something to store —
            // a project with no activities has never written one, and that is not a fault.
            //
            // This can only fire for a file deleted while the project is open: activities are read from this
            // very file at open, so if it was already gone the in-memory config is empty and there is nothing
            // left to restore it from. Recovery can't invent values it never saw.
            //
            Path json = config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME);
            if (!activities.allVariables().isEmpty() && !Files.exists(json)) {
                // Restored from the model already in memory — the only copy left, per the note above.
                ActivitiesConfig held = activities;
                missing.add(new Missing(json, target -> held.write(config.resourcesRoot()),
                        "activity settings"));
            }

            // Activities.java, Parameters.java, ActivityRegistry.java, FlowDriver.java and one stub per
            // activity were all asked about here. None of them exists to be missing: a tick, a value, a
            // picture and a wire are read from activities.json at run time, and an activity's behaviour is an
            // Activities.define call in a file the user owns. A project generated before that keeps those
            // files as ordinary source — theirs, so their absence is a deletion rather than damage.
        }
        return missing;
    }

    /**
     * The non-source files a project cannot do without, when they are gone.
     *
     * <p>These were unchecked until 2026-08-25, and each fails in its own quiet way: no {@code pom.xml} and
     * nothing builds; no {@code botmaker-project.properties} and the bot silently reverts to SDK defaults —
     * a game bot stops driving real input and nothing says so; no {@code settings.json} and the editor forgets
     * which template the project is, which is what {@link #looksLikeGameBot} then has to guess; no
     * {@code default_template.png} and every {@code new ImageTemplate(Templates.DEFAULT_TEMPLATE)} points at a
     * file that isn't there.
     *
     * <p><b>Only what can be restored honestly is listed.</b> A user's own captured template PNG is not here:
     * the pixels are gone and nothing can invent them — that case is the Resource Manager's, which offers to
     * forget the reference instead. And {@code settings.json} is restored <em>only when the template is
     * known</em> ({@code recorded} non-null): rebuilding it from a {@link #looksLikeGameBot} guess would write
     * that guess down as a recorded fact, which is worse than leaving the file absent and guessing again.
     */
    private static List<Missing> missingResources(ProjectConfig config, ProjectTemplate recorded,
                                                  ProjectTemplate resolved) {
        List<Missing> missing = new ArrayList<>();
        Path pom = config.projectPath().resolve("pom.xml");
        if (!Files.exists(pom)) {
            missing.add(new Missing(pom, target -> MavenService.writePom(config.projectPath(), config,
                    MavenService.SDK_FALLBACK_VERSION),
                    "build file (SDK pin reset to " + MavenService.SDK_FALLBACK_VERSION + ")"));
        }

        Path properties = config.resourcesRoot().resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(properties)) {
            BotSettings defaults = resolved == ProjectTemplate.GAME_BOT
                    ? BotSettings.GAME_DEFAULTS : BotSettings.DEFAULTS;
            missing.add(new Missing(properties, target -> BotSettings.write(config.resourcesRoot(), defaults),
                    "project properties"));
        }

        Path settings = config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME);
        if (recorded != null && !Files.exists(settings)) {
            missing.add(new Missing(settings,
                    target -> StudioProjectSettings.empty().withTemplate(recorded).write(config.resourcesRoot()),
                    "editor settings"));
        }

        Path placeholder = config.imagesRoot().resolve(ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE);
        if (!Files.exists(placeholder)) {
            missing.add(new Missing(placeholder, ImageTemplateLibrary::writePlaceholderAt, "image templates"));
        }
        return missing;
    }

    /**
     * Creates every file reported by {@link #findMissing}, and returns what was actually written.
     *
     * <p>An entry with no restorer is skipped silently, and so is one whose file has reappeared since the
     * scan: this pass never clobbers.
     */
    public static List<Path> recover(ProjectConfig config, List<Missing> missing) throws IOException {
        List<Path> written = new ArrayList<>();
        for (Missing m : missing) {
            if (m.restorer() == null) continue;
            if (Files.exists(m.path())) continue;      // re-check: never clobber
            Files.createDirectories(m.path().getParent());
            m.restorer().restore(m.path());
            written.add(m.path());
        }
        return written;
    }

    // `needsActivityRegeneration` was here, and is deliberately gone (2026-08-26). It answered "are any of
    // these files something only ActivityService can write?", and after phase 4 the answer is no for every
    // shape of entry: recovery restores each file directly from the generator. A predicate that is now
    // always false is not a cheap thing to keep — its callers each printed a line telling the user to run a
    // recovery that had, by then, already restored everything.

    /** Groups {@code missing} by reason, for a readable confirmation dialog. */
    public static Map<String, List<String>> summarise(List<Missing> missing) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (Missing m : missing) {
            byReason.computeIfAbsent(m.reason(), k -> new ArrayList<>()).add(m.fileName());
        }
        return byReason;
    }

    // `Damage`, `findDamaged`, `damageIn`, `repairSource`, `repairDamaged` and their AST helpers were
    // here, and went on 2026-08-29 with the generator they compared against. They asked whether a method
    // BotMaker owns inside a file the user owns had been renamed, re-signed or rewritten — which needed a
    // canonical text to diff against, and there is none: nothing generates a project's Java. The whole idea
    // that a file can be partly the user's is what went; see `FileRole` and `MethodLock`, which are the next
    // thing to follow it.
}
