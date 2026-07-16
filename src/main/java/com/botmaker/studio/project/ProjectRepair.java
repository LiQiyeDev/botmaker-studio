package com.botmaker.studio.project;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
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
 * only checks that {@code src/main/java} and {@code pom.xml} exist, so a project missing {@code GameLoop.java}
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
 * <p>Sources come from {@link ProjectCreator#sourcesFor} and {@code ActivityService}'s generators — this class
 * holds no templates of its own. (It briefly held a copy of the empty-project entry point, which promptly
 * drifted from the real one and lost an import, so a "recovered" project didn't compile. Hence the rule.)
 */
public final class ProjectRepair {

    private ProjectRepair() {}

    /** {@code ActivityRegistry.java}, which both the game-bot scaffold and {@code ActivityService} can produce. */
    private static final String REGISTRY_FILE = "ActivityRegistry.java";

    /**
     * A file that should exist but doesn't, plus the source that would restore it — or a {@code null} source for
     * the files only {@code ActivityService} can regenerate (see {@link #needsActivityRegeneration}).
     */
    public record Missing(Path path, String source, String reason) {
        public String fileName() { return path.getFileName().toString(); }
    }

    /**
     * Guesses whether {@code config}'s project is a game-bot project, from its sources: the entry point calls
     * {@code Bot.supervise}, or the scaffold's two co-generated files are both still present.
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
     * Requiring {@code GameLoop.java} <em>and</em> {@code ActivityRegistry.java} together (a pairing only the
     * generator produces) keeps the recovery case while dropping the false positive.
     */
    public static boolean looksLikeGameBot(ProjectConfig config) {
        Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return false;

        if (Files.exists(config.mainSourceFile())) {
            try {
                if (Files.readString(config.mainSourceFile()).contains("Bot.supervise")) return true;
            } catch (IOException ignored) {
                // Unreadable main: fall through to the file-presence check.
            }
        }
        return Files.exists(mainDir.resolve("GameLoop.java"))
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

        String reason = resolved == ProjectTemplate.GAME_BOT ? "game-bot scaffold" : "entry point";
        for (Map.Entry<String, String> e :
                ProjectCreator.sourcesFor(resolved, config.className(), config.packageName()).entrySet()) {
            if (hasActivities && REGISTRY_FILE.equals(e.getKey())) continue;
            Path path = mainDir.resolve(e.getKey());
            if (!Files.exists(path)) {
                missing.add(new Missing(path, e.getValue(), reason));
            }
        }

        if (activities != null) {
            // activities.json holds every activity's configured value; losing it silently resets them all to
            // defaults, which no other check would notice. Only expected once there is something to store —
            // a project with no activities has never written one, and that is not a fault.
            //
            // This can only fire for a file deleted while the project is open: activities are read from this
            // very file at open, so if it was already gone the in-memory config is empty and there is nothing
            // left to restore it from. Recovery can't invent values it never saw.
            Path json = config.resourcesRoot().resolve(ActivitiesConfig.FILE_NAME);
            if (!activities.allVariables().isEmpty() && !Files.exists(json)) {
                missing.add(new Missing(json, null, "activity settings"));
            }

            // Activities.java only exists when there is something to put in it — ActivityService deletes it
            // when there are no variables at all, so an absent-and-empty one is correct, not missing.
            if (!activities.allVariables().isEmpty() && !Files.exists(config.activitiesSourceFile())) {
                missing.add(new Missing(config.activitiesSourceFile(), null, "generated activity code"));
            }
            if (hasActivities && !Files.exists(config.activityRegistrySourceFile())) {
                missing.add(new Missing(config.activityRegistrySourceFile(), null, "generated activity code"));
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
     * Creates every file reported by {@link #findMissing}, and returns what was actually written.
     *
     * <p>Entries with a {@code null} source are activity stubs: they are left to {@code ActivityService}, whose
     * {@code update(...)} regenerates the registry as well — see {@code needsActivityRegeneration}.
     */
    public static List<Path> recover(ProjectConfig config, List<Missing> missing) throws IOException {
        List<Path> written = new ArrayList<>();
        for (Missing m : missing) {
            if (m.source() == null) continue;
            if (Files.exists(m.path())) continue;      // re-check: never clobber
            Files.createDirectories(m.path().getParent());
            Files.writeString(m.path(), m.source());
            written.add(m.path());
        }
        return written;
    }

    /** True when {@code missing} contains activity stubs, which only {@code ActivityService} can regenerate. */
    public static boolean needsActivityRegeneration(List<Missing> missing) {
        return missing.stream().anyMatch(m -> m.source() == null);
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
            /** A fully generated method whose body has been edited (an activity's {@code isEnabled()},
             *  {@code GameLoop.run}). */
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
     * today; the caller supplies it ({@link ProjectCreator#sourcesFor}, {@code ActivityService}'s stub
     * generator) so this class keeps holding no templates of its own. Files not in the map, and methods
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

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
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
        // static/visibility matter: Bot.supervise binds GoHome::run as a static method reference.
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
