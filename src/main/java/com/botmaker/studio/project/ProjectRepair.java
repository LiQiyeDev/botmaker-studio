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
 * Finds and regenerates project files that have gone missing — typically deleted outside the Studio (an
 * {@code rm}, a bad merge, a sync conflict), which nothing else notices: {@code ProjectManager.isValidProject}
 * only checks that {@code src/main/java} and {@code pom.xml} exist, so a project missing {@code FlowDriver.java}
 * opens happily and then fails to compile.
 *
 * <p>Recovery works at two granularities, because "broken" has two meanings:
 * <ul>
 *   <li><b>Missing files</b> ({@link #findMissing} / {@link #recover}) — <b>only ever creates what is absent,
 *       never overwrites what is there.</b> A file that exists is the user's, whatever is in it.</li>
 *   <li><b>Damaged locked methods</b> ({@link #findDamaged} / {@link #repairDamaged}) — the file exists, so the
 *       rule above declared it fine, and a {@code GoHome.run} renamed to {@code goHome} stayed renamed and the
 *       bot stayed uncompilable with nothing offering to fix it. Only methods {@link MethodLock} locks are
 *       touched, and for a {@code SIGNATURE} lock only the signature: the user's body is carried over. Their
 *       own methods are never touched at all.</li>
 * </ul>
 *
 * <p>Sources come from the project's own SDK ({@code Authoring}) and {@code ActivityService}'s generators —
 * this class holds no templates of its own. (It briefly held a copy of the empty-project entry point, which
 * promptly drifted from the real one and lost an import, so a "recovered" project didn't compile. Hence the
 * rule.) The version asked is the one the <b>pom pins</b>, not Studio's newest: a restored file has to compile
 * against the jar this bot actually resolves.
 */
public final class ProjectRepair {

    private ProjectRepair() {}

    /**
     * The generated files both the game-bot scaffold and {@code ActivityService} can produce. When the project
     * has activities, {@code ActivityService} is the authority (it knows the flow), so the scaffold's empty
     * template must not be used to "restore" them over the top.
     */
    private static final String REGISTRY_FILE = "ActivityRegistry.java";
    private static final String DRIVER_FILE = "FlowDriver.java";

    /**
     * Generated files the scaffold pass does <b>not</b> report, because a later pass in {@link #findMissing}
     * owns them and reports them on better evidence.
     *
     * <p>It exists because the file list is the generator's now (2026-08-25) rather than a hand-written five:
     * the SDK writes {@code Activities.java} and {@code Parameters.java} too, and a scaffold pass that
     * reported everything the generator emits would list them a second time — once with no evidence, once
     * with the right evidence — and would demand them from a project that has never had an activity to put in
     * them. {@code Templates.java} is the same shape of answer with a different owner: it is a function of the
     * project's images, and {@code ImageTemplateLibrary} is what regenerates it.
     */
    private static final java.util.Set<String> OWNED_BY_A_LATER_PASS =
            java.util.Set.of("Activities.java", "Parameters.java", "Templates.java");

    /**
     * A file that should exist but doesn't, plus what would restore it — or a {@code null} restorer for the
     * files only {@code ActivityService} can regenerate (see {@link #needsActivityRegeneration}).
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
     * Requiring {@code FlowDriver.java} <em>and</em> {@code ActivityRegistry.java} together (a pairing only the
     * generator produces) keeps the recovery case while dropping the false positive. It was
     * {@code GameLoop.java} + the registry until that file was retired; the driver is the same shape of
     * evidence — generated, game-bot-only, and never written by hand.
     */
    public static boolean looksLikeGameBot(ProjectConfig config) {
        Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return false;

        if (Files.exists(config.mainSourceFile())) {
            try {
                // "Bot.start" is the current entry-point call; "Bot.supervise" recognises pre-rename projects.
                String main = Files.readString(config.mainSourceFile());
                if (main.contains("Bot.start") || main.contains("Bot.supervise")) return true;
            } catch (IOException ignored) {
                // Unreadable main: fall through to the file-presence check.
            }
        }
        return Files.exists(mainDir.resolve(DRIVER_FILE))
                && Files.exists(mainDir.resolve(REGISTRY_FILE));
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

        // The registry's scaffold source is an *empty* List.of() — correct only while the project has no
        // activities. Once it has some, only ActivityService can rebuild it, so leave it to the pass below.
        boolean hasActivities = activities != null && !activities.activities().isEmpty();

        String sdkPin = MavenService.readSdkVersion(config.projectPath());
        if (resolved == ProjectTemplate.GAME_BOT) {
            // Reported by name, restored by nobody (2026-08-25, temporarily). *Which* files a game bot must
            // have is knowable from the generator's own file list; what it cannot yet do from outside a
            // creation is render one of them in isolation (inversion phase 4), so every entry here carries a
            // null restorer — the same shape this class already uses for the files only ActivityService can
            // produce. Saying nothing is missing would be the worse answer: a project that does not compile,
            // reported as healthy.
            for (String name : ProjectSpecs.generatedFileNames(config, resolved, sdkPin)) {
                if (OWNED_BY_A_LATER_PASS.contains(name)) continue;
                if (hasActivities && (REGISTRY_FILE.equals(name) || DRIVER_FILE.equals(name))) continue;
                Path path = mainDir.resolve(name);
                if (!Files.exists(path)) missing.add(new Missing(path, null, "game-bot scaffold"));
            }
        } else {
            // An empty project's entry point *can* be restored: it says nothing about the flow, so the
            // generator renders it from an empty model. Only that one file — `Templates.java` is a function
            // of the images actually in the project and belongs to ImageTemplateLibrary, not to a repair
            // that has not looked at them.
            String entryFile = config.className() + ".java";
            Path path = mainDir.resolve(entryFile);
            if (!Files.exists(path)) {
                String source = ProjectSpecs.generatedSource(config, resolved, sdkPin, entryFile);
                missing.add(source == null
                        ? new Missing(path, null, "entry point")
                        : Missing.ofSource(path, source, "entry point"));
            }
        }

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
                missing.add(new Missing(json, null, "activity settings"));
            }

            // Activities.java and Parameters.java exist as soon as the project has any activity or variable at
            // all, because ActivityService writes both even when they would be empty rather than deleting
            // them (something may still be importing them). Only a project that has never had an activity or
            // a variable is entitled not to have them.
            //
            // The pair is asked about together, on the same evidence, even though one holds the flags and the
            // other the values: they are written by one save and the file that has no fields of its own is
            // the one nothing would otherwise notice was gone.
            if (hasActivities || !activities.allVariables().isEmpty()) {
                if (!Files.exists(config.activitiesSourceFile())) {
                    missing.add(new Missing(config.activitiesSourceFile(), null, "generated activity code"));
                }
                if (!Files.exists(config.parametersSourceFile())) {
                    missing.add(new Missing(config.parametersSourceFile(), null, "generated activity code"));
                }
            }
            if (hasActivities && !Files.exists(config.activityRegistrySourceFile())) {
                missing.add(new Missing(config.activityRegistrySourceFile(), null, "generated activity code"));
            }
            if (hasActivities && !Files.exists(config.flowDriverSourceFile())) {
                missing.add(new Missing(config.flowDriverSourceFile(), null, "generated activity code"));
            }

            // Per-activity subclass stubs (the same set ActivityService.ensureStubs would create).
            for (ActivityDefinition a : activities.activities()) {
                Path stub = config.activitiesPackageDir().resolve(a.name() + ".java");
                if (!Files.exists(stub)) {
                    missing.add(new Missing(stub, null, "activity stub"));
                }
            }
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
     * <p>Entries with a {@code null} source are activity stubs: they are left to {@code ActivityService}, whose
     * {@code update(...)} regenerates the registry as well — see {@code needsActivityRegeneration}.
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

    /**
     * True when {@code missing} contains activity stubs, which only {@code ActivityService} can regenerate.
     *
     * <p>A null restorer used to mean exactly that. Since 2026-08-25 the game-bot scaffold has one too (its
     * text went with the SDK's templates), and that is <em>not</em> ActivityService's to write, so the reason
     * is checked as well — otherwise a project missing only {@code GoHome.java} would kick off a save that
     * cannot possibly restore it.
     */
    public static boolean needsActivityRegeneration(List<Missing> missing) {
        return missing.stream()
                .anyMatch(m -> m.restorer() == null && !"game-bot scaffold".equals(m.reason()));
    }

    /** Groups {@code missing} by reason, for a readable confirmation dialog. */
    public static Map<String, List<String>> summarise(List<Missing> missing) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (Missing m : missing) {
            byReason.computeIfAbsent(m.reason(), k -> new ArrayList<>()).add(m.fileName());
        }
        return byReason;
    }

    // =====================================================================================================
    // DAMAGED LOCKED METHODS — the file is present, but something BotMaker owns inside it has been changed.
    // =====================================================================================================

    /** One locked method that no longer matches what BotMaker generates. */
    public record Damage(Path file, String methodName, Kind kind) {
        public enum Kind {
            /** The method is gone entirely. */
            MISSING,
            /** Renamed, re-parameterised, or given a different return type — BotMaker can no longer call it. */
            SIGNATURE_CHANGED,
            /** A fully generated method whose body has been edited (an activity's {@code isEnabled()}). */
            BODY_CHANGED
        }

        /** A one-line description for the confirmation dialog. */
        public String describe() {
            String what = switch (kind) {
                case MISSING -> "missing";
                case SIGNATURE_CHANGED -> "signature changed";
                case BODY_CHANGED -> "body changed";
            };
            return file.getFileName() + "." + methodName + " — " + what;
        }
    }

    /**
     * Every locked method that no longer matches the generator's version.
     *
     * <p>{@code canonicalByPath} maps each scaffold file to the source the generator would produce for it
     * today; the caller supplies it ({@code Authoring}, {@code ActivityService}'s stub generator) so this
     * class keeps holding no templates of its own. Files not in the map, and methods
     * {@link MethodLock} doesn't lock, are never looked at.
     */
    public static List<Damage> findDamaged(ProjectConfig config, ProjectTemplate template,
                                           Map<Path, String> canonicalByPath) {
        List<Damage> damaged = new ArrayList<>();
        if (canonicalByPath == null) return damaged;

        for (Map.Entry<Path, String> entry : canonicalByPath.entrySet()) {
            Path file = entry.getKey();
            if (!Files.exists(file)) continue;   // a missing file is findMissing's job, not ours

            String current;
            try {
                current = Files.readString(file);
            } catch (IOException e) {
                continue;                        // unreadable: nothing useful to say
            }
            damaged.addAll(damageIn(config, template, file, current, entry.getValue()));
        }
        return damaged;
    }

    /** The damage in one file, comparing {@code current} against the generator's {@code canonical}. */
    private static List<Damage> damageIn(ProjectConfig config, ProjectTemplate template, Path file,
                                         String current, String canonical) {
        List<Damage> damaged = new ArrayList<>();
        CompilationUnit currentCu = parse(current);
        CompilationUnit canonicalCu = parse(canonical);

        for (MethodDeclaration expected : methodsOf(canonicalCu)) {
            MethodLock lock = MethodLock.of(config, template, file, expected);
            if (!lock.locksSignature()) continue;   // the user's method: not ours to have an opinion about

            String name = expected.getName().getIdentifier();
            MethodDeclaration actual = methodNamed(currentCu, name);

            if (actual == null) {
                damaged.add(new Damage(file, name, Damage.Kind.MISSING));
            } else if (!sameSignature(expected, actual)) {
                damaged.add(new Damage(file, name, Damage.Kind.SIGNATURE_CHANGED));
            } else if (lock.locksBody() && !sameBody(expected, actual)) {
                damaged.add(new Damage(file, name, Damage.Kind.BODY_CHANGED));
            }
        }
        return damaged;
    }

    /**
     * {@code current} with every damaged locked method restored, or {@code current} unchanged when there is
     * nothing to fix.
     *
     * <p>A {@link MethodLock#SIGNATURE} method keeps the user's body — only its declaration is replaced, which
     * is the whole distinction the lock draws. A {@link MethodLock#FULL} method is replaced outright, since all
     * of it is generated. Methods outside the locked set are never touched.
     */
    public static String repairSource(ProjectConfig config, ProjectTemplate template, Path file,
                                      String current, String canonical) {
        CompilationUnit currentCu = parse(current);
        CompilationUnit canonicalCu = parse(canonical);

        TypeDeclaration currentType = firstType(currentCu);
        if (currentType == null) return current;

        ASTRewrite rewrite = ASTRewrite.create(currentCu.getAST());
        ListRewrite members = rewrite.getListRewrite(currentType, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        boolean changed = false;

        for (MethodDeclaration expected : methodsOf(canonicalCu)) {
            MethodLock lock = MethodLock.of(config, template, file, expected);
            if (!lock.locksSignature()) continue;

            MethodDeclaration actual = methodNamed(currentCu, expected.getName().getIdentifier());
            MethodDeclaration replacement = (MethodDeclaration) ASTNode.copySubtree(currentCu.getAST(), expected);

            if (actual == null) {
                members.insertLast(replacement, null);
                changed = true;
            } else if (!sameSignature(expected, actual)) {
                // Carry the user's body across: a SIGNATURE lock says the name is BotMaker's and the body is
                // theirs, so restoring the one must not cost them the other.
                if (!lock.locksBody() && actual.getBody() != null) {
                    replacement.setBody((Block) ASTNode.copySubtree(currentCu.getAST(), actual.getBody()));
                }
                members.replace(actual, replacement, null);
                changed = true;
            } else if (lock.locksBody() && !sameBody(expected, actual)) {
                members.replace(actual, replacement, null);
                changed = true;
            }
        }

        if (!changed) return current;

        try {
            Document document = new Document(current);
            rewrite.rewriteAST(document, null).apply(document);
            return document.get();
        } catch (Exception e) {
            return current;   // a repair that can't be applied cleanly is not worth risking the file for
        }
    }

    /** Applies {@code damaged} to disk. Returns the files actually rewritten. */
    public static List<Path> repairDamaged(ProjectConfig config, ProjectTemplate template,
                                           Map<Path, String> canonicalByPath,
                                           List<Damage> damaged) throws IOException {
        List<Path> written = new ArrayList<>();
        for (Path file : damaged.stream().map(Damage::file).distinct().toList()) {
            String canonical = canonicalByPath.get(file);
            if (canonical == null || !Files.exists(file)) continue;

            String current = Files.readString(file);
            String repaired = repairSource(config, template, file, current, canonical);
            if (!repaired.equals(current)) {
                Files.writeString(file, repaired);
                written.add(file);
            }
        }
        return written;
    }

    // --- AST helpers -------------------------------------------------------------------------------------

    /**
     * Parses at the latest language level via {@link SourceParser}.
     *
     * <p>This used to build its own bare {@code ASTParser}, which defaults to source level 1.3 — so every
     * {@code @Override} in a scaffold file was a syntax error and the recovered tree had no methods on it.
     * Damage detection was reading that tree.
     */
    private static CompilationUnit parse(String source) {
        return SourceParser.parse(source);
    }

    private static TypeDeclaration firstType(CompilationUnit cu) {
        for (Object type : cu.types()) {
            if (type instanceof TypeDeclaration decl) return decl;
        }
        return null;
    }

    private static List<MethodDeclaration> methodsOf(CompilationUnit cu) {
        TypeDeclaration type = firstType(cu);
        return type == null ? List.of() : List.of(type.getMethods());
    }

    private static MethodDeclaration methodNamed(CompilationUnit cu, String name) {
        for (MethodDeclaration m : methodsOf(cu)) {
            if (m.getName().getIdentifier().equals(name)) return m;
        }
        return null;
    }

    /** Name, return type, modifiers and parameter types — everything a caller binds to. */
    private static boolean sameSignature(MethodDeclaration a, MethodDeclaration b) {
        if (!a.getName().getIdentifier().equals(b.getName().getIdentifier())) return false;
        if (!String.valueOf(a.getReturnType2()).equals(String.valueOf(b.getReturnType2()))) return false;
        if (a.parameters().size() != b.parameters().size()) return false;
        for (int i = 0; i < a.parameters().size(); i++) {
            SingleVariableDeclaration pa = (SingleVariableDeclaration) a.parameters().get(i);
            SingleVariableDeclaration pb = (SingleVariableDeclaration) b.parameters().get(i);
            if (!pa.getType().toString().equals(pb.getType().toString())) return false;
        }
        // static/visibility matter: Bot.start binds GoHome.INSTANCE::execute as a method reference.
        return modifiers(a).equals(modifiers(b));
    }

    private static String modifiers(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder();
        for (Object modifier : m.modifiers()) {
            if (modifier instanceof Modifier mod) sb.append(mod.getKeyword()).append(' ');
        }
        return sb.toString();
    }

    /** Compares printed bodies, so reindenting an untouched generated method isn't reported as damage. */
    private static boolean sameBody(MethodDeclaration a, MethodDeclaration b) {
        return String.valueOf(a.getBody()).equals(String.valueOf(b.getBody()));
    }
}
