package com.botmaker.studio.services;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.SemVer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * What changing this bot's SDK version would actually do to <em>this bot</em>.
 *
 * <p>Until now, changing the SDK version rewrote one line of the pom and nothing else — no warning, no list
 * of what breaks, no way back. That is a fine operation for a library nobody depends on and a terrible one
 * for a bot whose source is the model: {@code parser/BlockConverter} parses blocks <em>out of</em> Java on
 * every open, so a renamed SDK method does not become a red block, it becomes a file that no longer parses
 * into the shape the editor expects. The user finds out by opening their project.
 *
 * <p>This service answers the question first. It resolves the <b>target</b> version's jar (which need never
 * have been on this machine — the project pom's JitPack repository is used), ClassGraph-scans it beside the
 * one the project currently pins, and intersects the difference with the call sites in the project's own
 * source. The result is a {@link Report}: what is new, what the bot calls that is now deprecated, what the
 * bot calls that is <em>gone</em> (with file and line), and what the SDK itself says cannot be migrated
 * automatically.
 *
 * <h2>It rewrites no source, and deliberately owns no rewriting engine</h2>
 *
 * <p>The repair is {@code mvn rewrite:run} against the recipes the SDK jar ships at
 * {@code META-INF/rewrite/} — a Maven feature, not a Studio one, which is why it works on bots generated
 * long before any of this existed and needs no edit to the bot's pom. {@link #rewriteCommand} builds that
 * exact line for the user to paste. Studio applying the recipes itself is a separate, deferred decision.
 *
 * <h2>The order is not arbitrary</h2>
 *
 * <p>The recipes live in the <em>new</em> jar but must run against source that still parses and
 * type-attributes against the <em>old</em> one. So: snapshot → run the rewrite (pom still on the old
 * version) → <em>then</em> bump the pom. {@link #apply} is only the last step, and takes the VCS snapshot
 * before it so the whole thing is one revert away.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Call sites are judged from source alone, without bindings — the same constraint
 * {@code parser/refactor/MethodReferences} works under, for the same reason (half a mid-edit project is on
 * no classpath Studio owns). A call is attributed to the SDK when its receiver is written as the class name
 * ({@code Mouse.click(…)}, {@code new ImageTemplate(…)}), which is how every generated block writes them; a
 * call through a variable is not attributed and so is not reported. A file that does not parse is named in
 * {@link Report#problems()} rather than skipped silently: "nothing breaks" must never be the answer given by
 * a scan that could not read half the project.
 */
public final class SdkUpgradeService {

    /**
     * The rewrite plugin, pinned. Do not let this float to whatever {@code mvn} picks: 6.12.0 cannot read
     * {@code META-INF/rewrite/} at all on JDK 24+ (recipe discovery goes through ClassGraph's memory
     * mapping, which throws there), so an unpinned command fails on exactly the feature it is invoking.
     * See {@code botmaker-sdk/src/main/resources/META-INF/rewrite/botmaker-sdk.yml} for the full note.
     */
    public static final String REWRITE_PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:6.46.1";

    /** The aggregator recipe the SDK ships; it composes every per-release migration in order. */
    public static final String UPGRADE_RECIPE = "com.botmaker.sdk.UpgradeToLatest";

    private static final String RECIPE_ENTRY = "META-INF/rewrite/botmaker-sdk.yml";
    private static final String NOTES_ENTRY = "META-INF/botmaker/upgrade-notes.json";

    /** A constructor has no name of its own; this is how japicmp and {@code upgrade-notes.json} spell one. */
    private static final String CTOR = "<init>";

    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final JitPackSearch jitpack;

    public SdkUpgradeService(ProjectConfig config, ProjectState state,
                             LibraryService libraryService, JitPackSearch jitpack) {
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.jitpack = jitpack;
    }

    // =========================================================================
    // THE REPORT
    // =========================================================================

    /** One place in the bot's own source, as the user would find it: project-relative path and 1-based line. */
    public record CallSite(String file, int line) {
        @Override
        public String toString() {
            return file + ":" + line;
        }
    }

    /** Why a call the bot makes would stop compiling on the target version. */
    public enum BreakKind {
        /** The whole class is gone from the target SDK. */
        TYPE_REMOVED,
        /** The class is still there; no method or constructor of that name is. */
        MEMBER_REMOVED,
        /** The name survives, but no overload takes the number of arguments this bot passes. */
        SIGNATURE_CHANGED
    }

    /**
     * One API member this bot calls that the target SDK no longer offers in the shape the bot uses.
     * {@code detail} is display text — the old and new signatures — and is empty for a removal.
     */
    public record Break(String type, String member, BreakKind kind, String detail, List<CallSite> sites) {

        /** {@code Mouse.click} / {@code new ImageTemplate} — how the user reads it in their own code. */
        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }
    }

    /** A member this bot calls that is {@code @Deprecated} in the target SDK. Compiles; will not forever. */
    public record Deprecation(String type, String member, List<CallSite> sites) {
        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }
    }

    /**
     * A break the SDK itself declares unmigratable, read verbatim from the target jar's
     * {@code upgrade-notes.json}. Never paraphrased here: the SDK author wrote these sentences for the user.
     */
    public record Note(String version, String member, String summary, String action) {}

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * <p>{@code problems} is what the scan could <em>not</em> determine — an unresolvable jar, a file that
     * does not parse. It is separate from the four findings on purpose: an empty {@code breaks} list means
     * something quite different depending on whether this one is empty too.
     */
    public record Report(String from, String to,
                         List<String> added,
                         List<Deprecation> deprecated,
                         List<Break> breaks,
                         List<Note> notes,
                         String rewriteCommand,
                         List<String> problems) {

        /** True when the scan ran cleanly and found nothing that would stop this bot compiling. */
        public boolean nothingBreaks() {
            return breaks.isEmpty() && notes.isEmpty() && problems.isEmpty();
        }

        /** True when the scan could not answer the question, whatever the other lists say. */
        public boolean isIncomplete() {
            return !problems.isEmpty();
        }

        static Report unavailable(String from, String to, String problem) {
            return new Report(from, to, List.of(), List.of(), List.of(), List.of(), "", List.of(problem));
        }
    }

    // =========================================================================
    // ENTRY POINTS
    // =========================================================================

    /** The SDK version the project pom pins right now. */
    public String currentVersion() {
        return libraryService.currentSdkVersion();
    }

    /** Every SDK version JitPack can build, newest first. Best-effort: an empty list on any failure. */
    public CompletableFuture<List<String>> availableVersions() {
        return jitpack.fetchVersions(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID);
    }

    /**
     * Builds the report for moving this project to {@code targetVersion}.
     *
     * <p><b>Blocking</b> — resolves (and possibly downloads) two jars, scans both and parses every project
     * source file. Call it off the FX thread.
     */
    public Report compare(String targetVersion) {
        String from = currentVersion();
        String to = targetVersion == null ? "" : targetVersion.trim();
        if (to.isEmpty()) {
            return Report.unavailable(from, to, "No target version was chosen.");
        }

        Optional<Path> oldJar = MavenService.resolveSdkJar(config.projectPath(), from);
        Optional<Path> newJar = MavenService.resolveSdkJar(config.projectPath(), to);
        if (newJar.isEmpty()) {
            return Report.unavailable(from, to,
                    "SDK " + to + " could not be resolved. It may not be published yet, or you are offline.");
        }
        if (oldJar.isEmpty()) {
            return Report.unavailable(from, to,
                    "The SDK this project currently pins (" + from + ") could not be resolved, so there is "
                            + "nothing to compare the target against.");
        }

        return compare(oldJar.get(), newJar.get(), from, to);
    }

    /**
     * The comparison itself, given the two jars — everything except resolving them.
     *
     * <p>Split out so the diff can be tested against jars built on the spot rather than against whatever
     * happens to be published: the interesting cases (a method removed, an overload's arity changed, a class
     * that went away entirely) are exactly the ones no released pair of versions exhibits yet.
     */
    Report compare(Path oldJar, Path newJar, String from, String to) {
        Map<String, ApiClass> before = snapshot(oldJar);
        Map<String, ApiClass> after = snapshot(newJar);
        if (before.isEmpty() || after.isEmpty()) {
            return Report.unavailable(from, to,
                    "One of the two SDK jars scanned to no public API at all, which means the comparison "
                            + "would be meaningless rather than empty.");
        }

        List<String> problems = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        List<Call> calls = callsIn(known, problems);

        return new Report(from, to,
                additions(before, after),
                deprecations(after, calls),
                breaks(before, after, calls),
                notes(newJar, from, to),
                rewriteCommand(newJar, to),
                List.copyOf(problems));
    }

    /**
     * The last step of an upgrade: snapshot the project into its local history, then rewrite the pom.
     *
     * <p>Deliberately <em>not</em> the first step. The migration recipes are read from the new jar but must
     * run against source the old SDK still explains, so the user runs {@link #rewriteCommand} while the pom
     * still says the old version and calls this afterwards. The commit is what makes that ordering safe to
     * get wrong: everything up to here is one revert away in the VCS panel.
     */
    public CompletableFuture<Void> apply(String targetVersion) {
        return CompletableFuture
                .runAsync(() -> {
                    try {
                        ProjectVcs vcs = new ProjectVcs(config.projectPath());
                        vcs.commit("Before SDK upgrade to " + targetVersion);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "Could not snapshot the project before upgrading: " + e.getMessage(), e);
                    }
                })
                .thenCompose(v -> libraryService.updateLibraries(libraryService.currentLibraries(),
                        targetVersion));
    }

    /**
     * The exact command that migrates this bot's source, or {@code ""} when the target jar ships no recipes
     * (every SDK before the contract started, and any release that broke nothing).
     *
     * <p>Both the recipe classpath and the recipe name are {@code rewrite-maven-plugin} user properties, so
     * this adds nothing to the bot's pom and pins no plugin there.
     */
    public static String rewriteCommand(Path targetJar, String targetVersion) {
        if (readJarEntry(targetJar, RECIPE_ENTRY).isEmpty()) return "";
        return "mvn " + REWRITE_PLUGIN + ":run"
                + " -Drewrite.recipeArtifactCoordinates="
                + MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID + ":" + targetVersion
                + " -Drewrite.activeRecipes=" + UPGRADE_RECIPE;
    }

    // =========================================================================
    // THE TWO JARS
    // =========================================================================

    /** One public API class, reduced to what a compatibility question can be asked of. */
    private record ApiClass(String simpleName, boolean deprecated,
                            Map<String, List<ApiMember>> byName, Set<String> deprecatedNames) {}

    /** One public method or constructor: its name and its parameter types, as the user would read them. */
    private record ApiMember(String name, List<String> params) {
        String signature() {
            return (CTOR.equals(name) ? "" : name) + "(" + String.join(", ", params) + ")";
        }
    }

    /**
     * Scans one SDK jar down to its {@code com.botmaker.sdk.api} classes. Goes through
     * {@link TypeSummaryManager} rather than ClassGraph directly so the scan lands in the same per-jar disk
     * cache everything else uses — comparing against a given target version is fast the second time.
     */
    private static Map<String, ApiClass> snapshot(Path jar) {
        TypeSummaryManager index = new TypeSummaryManager();
        index.refresh(List.of(jar.toString()));
        Map<String, ApiClass> out = new LinkedHashMap<>();
        for (ClassInfo ci : index.getAllTypes()) {
            out.put(ci.getSimpleName(), apiClassOf(ci));
        }
        return out;
    }

    private static ApiClass apiClassOf(ClassInfo ci) {
        Map<String, List<ApiMember>> byName = new LinkedHashMap<>();
        Set<String> deprecatedNames = new LinkedHashSet<>();
        Set<String> liveNames = new LinkedHashSet<>();

        List<MethodInfo> all = new ArrayList<>(ci.getMethodInfo());
        all.addAll(ci.getConstructorInfo());
        for (MethodInfo mi : all) {
            if (!mi.isPublic() || mi.isSynthetic()) continue;
            String name = mi.isConstructor() ? CTOR : mi.getName();
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new ApiMember(name, paramsOf(mi)));
            // A name counts as deprecated only when every overload carrying it is — same rule as
            // SdkSurfaceService, and for the same reason: the user reads a name, not an overload.
            (mi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(name);
        }
        deprecatedNames.removeAll(liveNames);
        return new ApiClass(ci.getSimpleName(), ci.hasAnnotation(Deprecated.class.getName()),
                Map.copyOf(byName), Set.copyOf(deprecatedNames));
    }

    private static List<String> paramsOf(MethodInfo mi) {
        List<String> out = new ArrayList<>();
        for (MethodParameterInfo pi : mi.getParameterInfo()) {
            String type = pi.getTypeSignatureOrTypeDescriptor().toString();
            int dot = type.lastIndexOf('.');
            out.add(dot < 0 ? type : type.substring(dot + 1));
        }
        return out;
    }

    // =========================================================================
    // THE BOT'S OWN CALL SITES
    // =========================================================================

    /** One call in the bot's source to something that looks like an SDK member. */
    private record Call(String type, String member, int argCount, CallSite site) {}

    private List<Call> callsIn(Set<String> sdkTypes, List<String> problems) {
        List<Call> calls = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            CompilationUnit cu = SourceParser.parse(file.getContent());
            if (cu == null || SourceParser.hasSyntaxErrors(cu)) {
                problems.add(relativePath(file.getPath()) + " does not parse, so its calls were not checked.");
                continue;
            }
            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    if (node.getExpression() instanceof SimpleName receiver
                            && sdkTypes.contains(receiver.getIdentifier())) {
                        calls.add(new Call(receiver.getIdentifier(), node.getName().getIdentifier(),
                                node.arguments().size(), siteOf(file, cu, node.getStartPosition())));
                    }
                    return true;
                }

                @Override
                public boolean visit(ClassInstanceCreation node) {
                    String type = simpleTypeName(node.getType());
                    if (type != null && sdkTypes.contains(type)) {
                        calls.add(new Call(type, CTOR, node.arguments().size(),
                                siteOf(file, cu, node.getStartPosition())));
                    }
                    return true;
                }
            });
        }
        return calls;
    }

    private CallSite siteOf(ProjectFile file, CompilationUnit cu, int startPosition) {
        return new CallSite(relativePath(file.getPath()), cu.getLineNumber(startPosition));
    }

    private String relativePath(Path path) {
        Path root = config.projectPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.getFileName().toString();
    }

    private static String simpleTypeName(Type type) {
        if (type instanceof SimpleType simple) {
            String name = simple.getName().getFullyQualifiedName();
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
        return null;
    }

    // =========================================================================
    // THE DIFF
    // =========================================================================

    /** New classes, and new members on classes that already existed. Display text, sorted. */
    private static List<String> additions(Map<String, ApiClass> before, Map<String, ApiClass> after) {
        Set<String> out = new TreeSet<>();
        for (ApiClass now : after.values()) {
            ApiClass then = before.get(now.simpleName());
            if (then == null) {
                out.add(now.simpleName() + " (new class)");
                continue;
            }
            for (String name : now.byName().keySet()) {
                if (!then.byName().containsKey(name)) {
                    out.add(CTOR.equals(name)
                            ? "new " + now.simpleName() + "(…)"
                            : now.simpleName() + "." + name + "(…)");
                }
            }
        }
        return List.copyOf(out);
    }

    /** Members this bot calls that the target marks {@code @Deprecated}. */
    private static List<Deprecation> deprecations(Map<String, ApiClass> after, List<Call> calls) {
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();
        for (Call call : calls) {
            ApiClass now = after.get(call.type());
            if (now == null) continue;
            boolean deprecated = now.deprecated() || now.deprecatedNames().contains(call.member());
            if (deprecated) {
                sites.computeIfAbsent(call.type() + "#" + call.member(), k -> new ArrayList<>()).add(call.site());
            }
        }
        return sites.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("#", 2);
                    return new Deprecation(parts[0], parts[1], sorted(e.getValue()));
                })
                .sorted(Comparator.comparing(Deprecation::display))
                .toList();
    }

    /**
     * Calls that would stop compiling. Only members the <em>old</em> jar actually had are judged: a call to
     * something neither jar declares is the bot's own code (or an unindexed library), not a break this
     * upgrade causes.
     */
    private static List<Break> breaks(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                      List<Call> calls) {
        Map<String, Break> found = new LinkedHashMap<>();
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();

        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            if (then == null || !then.byName().containsKey(call.member())) continue;

            ApiClass now = after.get(call.type());
            BreakKind kind;
            String detail = "";
            if (now == null) {
                kind = BreakKind.TYPE_REMOVED;
            } else if (!now.byName().containsKey(call.member())) {
                kind = BreakKind.MEMBER_REMOVED;
            } else if (acceptsArity(now, call.member(), call.argCount())) {
                continue;
            } else {
                kind = BreakKind.SIGNATURE_CHANGED;
                detail = "was " + signatures(then, call.member()) + " — now " + signatures(now, call.member());
            }

            String key = call.type() + "#" + call.member() + "#" + kind;
            found.putIfAbsent(key, new Break(call.type(), call.member(), kind, detail, List.of()));
            sites.computeIfAbsent(key, k -> new ArrayList<>()).add(call.site());
        }

        return found.entrySet().stream()
                .map(e -> {
                    Break b = e.getValue();
                    return new Break(b.type(), b.member(), b.kind(), b.detail(), sorted(sites.get(e.getKey())));
                })
                .sorted(Comparator.comparing(Break::display))
                .toList();
    }

    private static boolean acceptsArity(ApiClass klass, String member, int argCount) {
        // Arity, not types: without bindings the argument *types* at the call site are unknown, and claiming
        // a break that isn't one is worse than missing one — the user can always compile.
        return klass.byName().getOrDefault(member, List.of()).stream()
                .anyMatch(m -> m.params().size() == argCount);
    }

    private static String signatures(ApiClass klass, String member) {
        List<ApiMember> overloads = klass.byName().getOrDefault(member, List.of());
        if (overloads.isEmpty()) return "(nothing)";
        return overloads.stream().map(ApiMember::signature).sorted().distinct()
                .reduce((a, b) -> a + " / " + b).orElse("");
    }

    private static List<CallSite> sorted(Collection<CallSite> sites) {
        return sites.stream()
                .distinct()
                .sorted(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line))
                .toList();
    }

    // =========================================================================
    // WHAT THE SDK ITSELF SAYS
    // =========================================================================

    /**
     * The unmigratable changes the target jar declares, for every version in {@code (from, to]}.
     *
     * <p>When either bound is not a version {@link SemVer} understands — {@code 0.0.0-SNAPSHOT}, most often —
     * every entry is returned rather than none. An over-long list is a nuisance; a silently empty one is the
     * report saying "nothing to worry about" when the SDK author wrote down that there is.
     */
    private static List<Note> notes(Path targetJar, String from, String to) {
        Optional<String> json = readJarEntry(targetJar, NOTES_ENTRY);
        if (json.isEmpty()) return List.of();

        List<Note> out = new ArrayList<>();
        try {
            JsonNode versions = new ObjectMapper().readTree(json.get()).path("versions");
            versions.fieldNames().forEachRemaining(version -> {
                if (!inRange(version, from, to)) return;
                for (JsonNode entry : versions.path(version)) {
                    out.add(new Note(version,
                            entry.path("member").asText(""),
                            entry.path("summary").asText(""),
                            entry.path("action").asText("")));
                }
            });
        } catch (Exception e) {
            System.err.println("Could not read " + NOTES_ENTRY + " from " + targetJar + ": " + e.getMessage());
        }
        out.sort(Comparator.comparing(Note::version).thenComparing(Note::member));
        return List.copyOf(out);
    }

    private static boolean inRange(String version, String from, String to) {
        String v = strip(version);
        String lo = strip(from);
        String hi = strip(to);
        if (!SemVer.isValid(v) || !SemVer.isValid(lo) || !SemVer.isValid(hi)) return true;
        return SemVer.compare(v, lo) > 0 && SemVer.compare(v, hi) <= 0;
    }

    /** Release tags are cut as {@code v1.0.26}; {@code SemVer} wants {@code 1.0.26}. */
    private static String strip(String version) {
        if (version == null) return "";
        String t = version.trim();
        return (t.startsWith("v") || t.startsWith("V")) ? t.substring(1) : t;
    }

    private static Optional<String> readJarEntry(Path jar, String entryName) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) return Optional.empty();
            try (InputStream in = jarFile.getInputStream(entry)) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
