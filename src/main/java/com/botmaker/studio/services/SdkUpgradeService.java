package com.botmaker.studio.services;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.SdkReferences;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.SemVer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import org.eclipse.jdt.core.dom.CompilationUnit;

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
 * <h2>The repair the SDK ships with the break</h2>
 *
 * <p>Every breaking change is declared in the target jar at {@code META-INF/botmaker/migrations.json},
 * keyed by the release that introduced it, and each entry carries either a {@code fix} (something Studio's
 * own rewriter can do) or a {@code manual} sentence (something no rewrite can repair). {@link #migrations}
 * reads that file for every version in {@code (from, to]}; the report shows the two sets separately, because
 * they ask different things of the user.
 *
 * <p>{@link #apply} then carries the {@code fix} entries out: snapshot → repair the source
 * ({@code parser/refactor/SdkMigrationRunner}) → bump the pom, one button and one revert away.
 *
 * <p>This replaced {@code mvn rewrite:run} against OpenRewrite recipes. That existed to let a user migrate
 * with no Studio at all; once that stopped being a requirement, an engine we do not control bought nothing
 * {@code parser/refactor/CallMigrator} could not do. One consequence is worth stating because it removed a
 * constraint rather than adding one: OpenRewrite type-attributes against the <em>old</em> SDK, so the
 * rewrite had to run before the pom was bumped, and the dialog had to teach that ordering. Our rewriter
 * resolves the SDK not at all, so snapshot → migrate → bump is a single operation.
 *
 * <h2>A file this Studio cannot fully read is refused, not half-applied</h2>
 *
 * <p>Studio is the version that lags — a bot may pin an SDK newer than the Studio editing it. So a
 * {@code schema} above {@link #MIGRATIONS_MAX_SCHEMA} is refused <em>whole</em> (one line in
 * {@link Report#problems()}; breaks are still reported, since those come from scanning the jar), and an
 * unrecognised {@code fix.kind} degrades that one entry to manual — its summary still shown — rather than
 * being skipped. Both leave {@link Report#canMigrate()} false. Half a migration is the failure
 * {@code CallMigrator.rewriteOthers} returns {@code null} to prevent.
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
 *
 * <h2>Fields and constants count as API</h2>
 *
 * <p>{@code Key.ENTER}, {@code Precision.TIGHT}, {@code Direction.UP} are as much of the surface as any
 * method, and a release that deletes one breaks a bot exactly as hard. Public fields are therefore scanned
 * out of both jars alongside methods and constructors (an enum constant is a static field, so the five
 * public enums arrive for free), and three shapes of use are recognised in the bot's own source: the
 * qualified read {@code Key.ENTER}, a bare name reaching a {@code import static …Key.ENTER}, and a
 * {@code case} label, whose enum type is carried by the switch expression and so cannot be read off the
 * label at all.
 *
 * <p>That last one is why the unqualified shapes follow {@code MethodReferences}' three-way verdict rather
 * than a simple match: a bare {@code UP} that names a constant on exactly one known SDK type is attributed,
 * one that could be a constant on several is a line in {@link Report#problems()}, and one that matches
 * nothing is not an SDK reference. Guessing between two enums would report a break in the wrong class.
 */
public final class SdkUpgradeService {

    /**
     * The highest {@code schema} of {@code migrations.json} this Studio knows how to read. A file declaring
     * more is refused whole rather than guessed at — see the class Javadoc.
     *
     * <p>Adding a {@code fix.kind} does <em>not</em> bump this: an unknown kind degrades to manual, which is
     * the whole point of having that rule. It bumps only for a grammar change that would make this reader
     * <em>misread</em> an entry it thinks it understands.
     */
    public static final int MIGRATIONS_MAX_SCHEMA = 1;

    /**
     * The {@code fix.kind}s this Studio can carry out. Anything else is a newer SDK talking to an older
     * Studio, and degrades to manual. The rewrites themselves live in {@code parser/refactor}.
     */
    public static final Set<String> KNOWN_FIX_KINDS = Set.of(
            "renameMethod", "renameType", "renameField", "moveMember",
            "dropArgument", "reorderArguments", "insertArgument");

    private static final String MIGRATIONS_ENTRY = "META-INF/botmaker/migrations.json";

    /** A constructor has no name of its own; this is how japicmp and {@code migrations.json} spell one. */
    private static final String CTOR = SdkReferences.CTOR;

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
        /**
         * The class is still there; the field or enum constant this bot reads is not. Distinct from
         * {@link #MEMBER_REMOVED} so the report can say "the constant is gone" instead of describing
         * {@code Key.ENTER} as though it were a method.
         */
        FIELD_REMOVED,
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
     * The automatic repair for one entry: a {@code kind} from {@link #KNOWN_FIX_KINDS} and its options,
     * carried as raw JSON because each kind reads different ones ({@code to}, {@code toType}, {@code index},
     * {@code order}, {@code value}). Phase 4's edit model is what interprets them.
     *
     * <p>{@code arity} is the optional {@code when.arity} scope, or {@code -1} for "every overload". It
     * exists because call sites are matched by arity and not by argument type, so an unscoped rename would
     * hit overloads the SDK author did not mean.
     */
    public record Fix(String kind, JsonNode options, int arity) {}

    /**
     * One breaking change the target SDK declares, read from its {@code migrations.json}.
     *
     * <p>Exactly one of {@code fix} and {@code manual} is set — that is the file's own invariant, checked in
     * the SDK repo by {@code ApiRulesCheck} so a malformed entry is a red build there rather than a
     * half-migration here. {@code summary} is always present and always shown verbatim: the SDK author wrote
     * that sentence for the user, and it is never paraphrased.
     *
     * <p>An entry whose {@code fix.kind} this Studio does not know arrives here as {@code manual} with
     * {@code degraded} set, so the dialog can say <em>why</em> it cannot be repaired automatically — "needs a
     * newer Studio" is a different sentence from "no rewrite can express this".
     *
     * <p>{@code sites} is where <em>this project</em> uses the member, spelled as the project spells it
     * <b>today</b> — which is not always what {@code member} says. See {@link #resolveNames}: a 3.0.0 entry
     * about {@code baz} has to be shown against the call sites a file still writes as {@code foo}, because
     * 2.0.0 is what renames {@code foo} to {@code bar} and the user has run neither.
     */
    public record Migration(String version, String member, String summary,
                            Fix fix, String manual, boolean degraded, List<CallSite> sites) {

        /** True when Studio can carry this one out itself. */
        public boolean isAutomatic() {
            return fix != null;
        }

        Migration withSites(List<CallSite> found) {
            return new Migration(version, member, summary, fix, manual, degraded, found);
        }
    }

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
                         List<Migration> migrations,
                         List<String> problems) {

        /** The entries Studio can carry out itself. */
        public List<Migration> automatic() {
            return migrations.stream().filter(Migration::isAutomatic).toList();
        }

        /** The entries that need the user — no fix expressible, or a kind this Studio does not know. */
        public List<Migration> manual() {
            return migrations.stream().filter(m -> !m.isAutomatic()).toList();
        }

        /**
         * Whether the upgrade may be applied automatically: every declared change has a fix this Studio
         * understands, and the scan itself read everything.
         *
         * <p>One manual entry disables the whole span rather than the one file it names. The alternative —
         * rewrite what we can and leave the rest — is the half-migration the whole design refuses: the user
         * would be left with a project that is neither the old shape nor the new one, and no way to tell
         * which call sites were touched.
         */
        public boolean canMigrate() {
            return problems.isEmpty() && manual().isEmpty() && !automatic().isEmpty();
        }

        /** True when the scan ran cleanly and found nothing that would stop this bot compiling. */
        public boolean nothingBreaks() {
            return breaks.isEmpty() && migrations.isEmpty() && problems.isEmpty();
        }

        /** True when the scan could not answer the question, whatever the other lists say. */
        public boolean isIncomplete() {
            return !problems.isEmpty();
        }

        static Report unavailable(String from, String to, String problem) {
            return new Report(from, to, List.of(), List.of(), List.of(), List.of(), List.of(problem));
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
        List<Call> calls = callsIn(known, fieldOwners(before, after), problems);
        // Reads problems too — a migrations file this Studio is too old for adds a line here, and does it
        // before the list is frozen.
        List<Migration> migrations = migrations(newJar, from, to, problems);

        return new Report(from, to,
                additions(before, after),
                deprecations(after, calls),
                breaks(before, after, calls),
                resolveNames(migrations, calls),
                List.copyOf(problems));
    }

    /**
     * The whole upgrade, in one button: snapshot → repair the source → bump the pom.
     *
     * <p>The snapshot comes first so all of it is one revert away in the VCS panel — which is the point, since
     * what a changed SDK does to a bot is only fully visible once the project is reopened.
     *
     * <p>The three steps used to be two, and the missing one was the whole reason the SDK ships repairs at all.
     * The ordering carries no constraint of its own any more: {@code mvn rewrite:run} had to run <em>before</em>
     * the bump because OpenRewrite type-attributed against the old SDK, and {@link SdkMigrationRunner} resolves
     * the SDK not at all.
     *
     * <p>Any refusal from the migration aborts before the pom is touched, with nothing written anywhere — so a
     * failed upgrade leaves a project that still compiles against the version it already had.
     *
     * <p>{@code repairSources} is {@link Report#canMigrate()}, and it gates the middle step only. A span
     * carrying one change no rewrite can express still has to be <em>switchable</em>: the user reads the manual
     * notes, makes those edits themselves, and moves. Refusing the whole button in that case would be a trap
     * with no way out, since the SDK goes on declaring that entry forever. What it must never do is repair
     * half of the span, which is why the flag is all-or-nothing rather than per entry.
     */
    public CompletableFuture<Void> apply(String targetVersion, boolean repairSources) {
        return CompletableFuture
                .runAsync(() -> {
                    try {
                        ProjectVcs vcs = new ProjectVcs(config.projectPath());
                        vcs.commit("Before SDK upgrade to " + targetVersion);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "Could not snapshot the project before upgrading: " + e.getMessage(), e);
                    }
                    if (repairSources) migrateSources(targetVersion);
                })
                .thenCompose(v -> libraryService.updateLibraries(libraryService.currentLibraries(),
                        targetVersion));
    }

    /**
     * Runs every automatic repair the target declares over the project's own files, or throws saying why it
     * will not. An SDK that declares nothing is not an error — most upgrades break nothing.
     */
    private void migrateSources(String targetVersion) {
        String from = currentVersion();
        Optional<Path> oldJar = MavenService.resolveSdkJar(config.projectPath(), from);
        Optional<Path> newJar = MavenService.resolveSdkJar(config.projectPath(), targetVersion);
        if (oldJar.isEmpty() || newJar.isEmpty()) {
            throw new IllegalStateException("The SDK jars could not be resolved again, so the upgrade stopped "
                    + "before changing anything. Check the report and try once more.");
        }

        List<String> problems = new ArrayList<>();
        List<Migration> declared = migrations(newJar.get(), from, targetVersion, problems);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());
        for (Migration migration : declared) {
            if (!migration.isAutomatic()) {
                throw new IllegalStateException("\"" + migration.member() + "\" has to be changed by hand, so "
                        + "none of these changes were applied. Make it yourself, then upgrade.");
            }
        }
        List<SdkMigrationRunner.Fix> fixes = declared.stream().map(SdkUpgradeService::fixOf).toList();
        if (fixes.isEmpty()) return;

        Map<String, ApiClass> before = snapshot(oldJar.get());
        Map<String, ApiClass> after = snapshot(newJar.get());
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());

        List<ProjectFile> editable = new ArrayList<>();
        List<ProjectFile> generated = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            // FileRole is the single source of truth for "may the user change this?", and the migration
            // answers to the same rule the editor does — see SdkMigrationRunner on why a scaffold file is
            // refused rather than rewritten.
            (FileRole.of(config, state.getTemplate(), file.getPath()) == FileRole.EDITABLE ? editable : generated)
                    .add(file);
        }

        SdkMigrationRunner.Outcome outcome = SdkMigrationRunner.run(fixes, editable, generated,
                known, fieldOwners(before, after), null, state);
        if (outcome.isRefusal()) throw new IllegalStateException(outcome.refusal());
        try {
            CallMigrator.commit(outcome.files());
        } catch (IOException e) {
            throw new RuntimeException("Some files could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * One declared repair, flattened for {@link SdkMigrationRunner}. The JSON stays here — the rewriter reads
     * typed fields, so the file's grammar is known in one place rather than two.
     */
    private static SdkMigrationRunner.Fix fixOf(Migration migration) {
        JsonNode options = migration.fix().options();
        String[] parts = migration.member().split("#", 2);
        List<Integer> order = new ArrayList<>();
        for (JsonNode each : options.path("order")) order.add(each.asInt());
        return new SdkMigrationRunner.Fix(migration.version(), parts[0], parts.length > 1 ? parts[1] : "",
                migration.fix().arity(), migration.fix().kind(),
                text(options, "to"), text(options, "toType"), options.path("index").asInt(-1),
                List.copyOf(order), text(options, "value"), text(options, "import"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    // =========================================================================
    // THE TWO JARS
    // =========================================================================

    /** One public API class, reduced to what a compatibility question can be asked of. */
    private record ApiClass(String simpleName, boolean deprecated,
                            Map<String, List<ApiMember>> byName, Set<String> deprecatedNames) {

        /** Whether this class offers {@code name} as a field — an enum constant included. */
        boolean declaresField(String name) {
            return byName.getOrDefault(name, List.of()).stream().anyMatch(ApiMember::field);
        }

        /** Whether this class offers {@code name} as something callable: a method or a constructor. */
        boolean declaresCallable(String name) {
            return byName.getOrDefault(name, List.of()).stream().anyMatch(m -> !m.field());
        }
    }

    /**
     * One public member: a method or constructor with its parameter types, or a field with none.
     *
     * <p>Fields share {@code byName} with methods rather than living in a set of their own, so the
     * deprecation rule, the additions diff and the break diff each have one thing to consult. {@code field}
     * is what keeps a constant from being mistaken for a no-argument method — a distinction that matters
     * both ways round, since turning one into the other is itself a break.
     */
    private record ApiMember(String name, List<String> params, boolean field) {
        String signature() {
            if (field) return name;
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
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new ApiMember(name, paramsOf(mi), false));
            // A name counts as deprecated only when every overload carrying it is — same rule as
            // SdkSurfaceService, and for the same reason: the user reads a name, not an overload.
            (mi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(name);
        }
        // Fields go through the same map and the same deprecation rule. Enum constants need no special
        // case: the compiler emits each one as a public static field of the enum type.
        for (FieldInfo fi : ci.getFieldInfo()) {
            if (!fi.isPublic() || fi.isSynthetic()) continue;
            byName.computeIfAbsent(fi.getName(), k -> new ArrayList<>())
                    .add(new ApiMember(fi.getName(), List.of(), true));
            (fi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(fi.getName());
        }
        deprecatedNames.removeAll(liveNames);
        return new ApiClass(ci.getSimpleName(), ci.hasAnnotation(Deprecated.class.getName()),
                Map.copyOf(byName), Set.copyOf(deprecatedNames));
    }

    /**
     * Constant name → the SDK types declaring it, across <em>both</em> jars. The union is deliberate: an
     * unqualified use of a constant the target removed still has to be recognised, and only the old jar
     * knows it ever existed.
     */
    private static Map<String, List<String>> fieldOwners(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (Map<String, ApiClass> jar : List.of(before, after)) {
            for (ApiClass klass : jar.values()) {
                for (Map.Entry<String, List<ApiMember>> entry : klass.byName().entrySet()) {
                    if (entry.getValue().stream().anyMatch(ApiMember::field)) {
                        owners.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).add(klass.simpleName());
                    }
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        owners.forEach((name, types) -> out.put(name, List.copyOf(types)));
        return Map.copyOf(out);
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

    /**
     * One reference in the bot's source to something that looks like an SDK member, reduced to what the report
     * asks of it: which member, how many arguments, and where the user would find it.
     *
     * <p>The finding itself is {@link SdkReferences}' — the same scan {@code SdkMigrationRunner} rewrites from.
     * That sharing is the point: two scans would eventually disagree, and the shape of the disagreement would
     * be a dialog listing three call sites next to a button that repairs two.
     */
    private record Call(String type, String member, int argCount, CallSite site) {
        boolean isField() {
            return argCount == SdkReferences.FIELD_READ;
        }

        /** {@code Key#ENTER} — the spelling {@code migrations.json} and japicmp both use. */
        String key() {
            return type + "#" + member;
        }
    }

    private List<Call> callsIn(Set<String> sdkTypes, Map<String, List<String>> fieldOwners,
                               List<String> problems) {
        List<Call> calls = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            String path = relativePath(file.getPath());
            CompilationUnit cu = SourceParser.parse(file.getContent());
            if (cu == null || SourceParser.hasSyntaxErrors(cu)) {
                problems.add(path + " does not parse, so its calls were not checked.");
                continue;
            }
            SdkReferences.Scan scan = SdkReferences.in(file, cu, path, sdkTypes, fieldOwners);
            problems.addAll(scan.problems());
            for (SdkReferences.Reference reference : scan.references()) {
                calls.add(new Call(reference.type(), reference.member(), reference.argCount(),
                        new CallSite(path, cu.getLineNumber(reference.site().node().getStartPosition()))));
            }
        }
        return calls;
    }

    private String relativePath(Path path) {
        Path root = config.projectPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.getFileName().toString();
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
                    // A constant is read as a value, so it is shown as one: Key.ENTER, not Key.ENTER(…).
                    String call = now.declaresCallable(name) ? "(…)" : "";
                    out.add(CTOR.equals(name)
                            ? "new " + now.simpleName() + "(…)"
                            : now.simpleName() + "." + name + call);
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
            // In the shape the bot uses it: a name the old jar had only as a method is not evidence that
            // this file's `Foo.NAME` was ever SDK, and vice versa.
            if (then == null || !declares(then, call)) continue;

            ApiClass now = after.get(call.type());
            BreakKind kind;
            String detail = "";
            if (now == null) {
                kind = BreakKind.TYPE_REMOVED;
            } else if (!declares(now, call)) {
                // Covers a field turned into a method (and the reverse) as well as an outright removal —
                // every one of them stops this call site compiling.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
            } else if (call.isField() || acceptsArity(now, call.member(), call.argCount())) {
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

    /** Whether {@code klass} offers this call's member in the shape the call site uses it. */
    private static boolean declares(ApiClass klass, Call call) {
        return call.isField() ? klass.declaresField(call.member()) : klass.declaresCallable(call.member());
    }

    private static boolean acceptsArity(ApiClass klass, String member, int argCount) {
        // Arity, not types: without bindings the argument *types* at the call site are unknown, and claiming
        // a break that isn't one is worse than missing one — the user can always compile.
        // A field shares the map but has no parameter list at all, so it must not answer for arity 0.
        return klass.byName().getOrDefault(member, List.of()).stream()
                .anyMatch(m -> !m.field() && m.params().size() == argCount);
    }

    private static String signatures(ApiClass klass, String member) {
        List<ApiMember> overloads = klass.byName().getOrDefault(member, List.of()).stream()
                .filter(m -> !m.field()).toList();
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
     * The breaking changes the target jar declares, for every version in {@code (from, to]}.
     *
     * <p>When either bound is not a version {@link SemVer} understands — {@code 0.0.0-SNAPSHOT}, most often —
     * every entry is returned rather than none. An over-long list is a nuisance; a silently empty one is the
     * report saying "nothing to worry about" when the SDK author wrote down that there is.
     *
     * <p>Ordering is by version, ascending, then by member. That is not cosmetic: it is the order the
     * migration must be <em>replayed</em> in — each version applied as its own pass over what the previous
     * one produced — so the list the user reads and the list the rewriter walks are the same list.
     */
    private static List<Migration> migrations(Path targetJar, String from, String to, List<String> problems) {
        Optional<String> json = readJarEntry(targetJar, MIGRATIONS_ENTRY);
        if (json.isEmpty()) return List.of();

        List<Migration> out = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(json.get());

            int schema = root.path("schema").asInt(0);
            if (schema > MIGRATIONS_MAX_SCHEMA) {
                // Refused whole, and deliberately not "best effort": a grammar we do not know is one we may
                // MISREAD, which is worse than not reading it. Breaks are still reported below — those come
                // from scanning the jar and need this file not at all.
                problems.add("SDK " + to + " describes its changes in a newer format (schema " + schema
                        + "; this Studio reads " + MIGRATIONS_MAX_SCHEMA + "). Update Studio to see them and "
                        + "to have them applied for you.");
                return List.of();
            }

            JsonNode versions = root.path("versions");
            versions.fieldNames().forEachRemaining(version -> {
                if (!inRange(version, from, to)) return;
                for (JsonNode entry : versions.path(version)) {
                    out.add(migrationOf(version, entry));
                }
            });
        } catch (Exception e) {
            problems.add("SDK " + to + " ships a migration file that could not be read (" + e.getMessage()
                    + "), so what it declares about this upgrade is unknown.");
            return List.of();
        }
        out.sort(Comparator.comparing((Migration m) -> strip(m.version()), SdkUpgradeService::compareVersions)
                .thenComparing(Migration::member));
        return List.copyOf(out);
    }

    /**
     * One entry, with the degradation rule applied: a {@code fix} whose {@code kind} this Studio does not
     * know becomes a manual entry that still shows its summary. Never dropped — an entry silently skipped is
     * a break the user is never told about, which is the one outcome worse than not repairing it.
     */
    private static Migration migrationOf(String version, JsonNode entry) {
        String member = entry.path("member").asText("");
        String summary = entry.path("summary").asText("");
        JsonNode fix = entry.path("fix");

        if (fix.isMissingNode() || fix.isNull()) {
            return new Migration(version, member, summary, null, entry.path("manual").asText(""), false,
                    List.of());
        }

        String kind = fix.path("kind").asText("");
        if (!KNOWN_FIX_KINDS.contains(kind)) {
            return new Migration(version, member, summary, null,
                    "This Studio is too old to repair this one automatically (it does not know how to apply a "
                            + "\"" + kind + "\" fix). Update Studio, or change these call sites by hand.",
                    true, List.of());
        }
        int arity = entry.path("when").path("arity").asInt(-1);
        return new Migration(version, member, summary, new Fix(kind, fix, arity), "", false, List.of());
    }

    /**
     * Attaches each entry's call sites, resolved through the renames the earlier versions in the span perform.
     *
     * <p>The trail is the half of ordered replay that the <em>report</em> needs, and it is easy to leave out
     * and impossible to notice missing. If 2.0.0 renames {@code foo} to {@code bar} and 3.0.0 then drops
     * {@code bar}, the user upgrading straight from 1.x has a project that says {@code foo} everywhere: an
     * entry naming {@code bar} would find nothing and quietly show no sites at all — the most confusing
     * possible upgrade reporting a note about a name that appears nowhere.
     *
     * <p>So the versions are walked in the order they will be applied, each entry resolved against the trail
     * built by the versions <em>before</em> it, and only then does that version's own rename join the trail.
     * {@code migrations} has already sorted them ascending, which is the same order the rewriter walks.
     */
    private static List<Migration> resolveNames(List<Migration> migrations, List<Call> calls) {
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();
        for (Call call : calls) {
            sites.computeIfAbsent(call.key(), k -> new ArrayList<>()).add(call.site());
            // A type-level entry — `renameType`, or a whole class withdrawn — names no member, and is keyed
            // here as `Key#` so it collects every use of the type rather than none.
            sites.computeIfAbsent(call.type() + "#", k -> new ArrayList<>()).add(call.site());
        }

        Map<String, String> spelledNow = new LinkedHashMap<>();
        List<Migration> out = new ArrayList<>();
        for (Migration migration : migrations) {
            String declared = simpleKeyOf(migration.member());
            String today = spelledNow.getOrDefault(declared, declared);
            out.add(migration.withSites(sorted(sites.getOrDefault(today, List.of()))));

            String after = renamedTo(migration, declared);
            if (after != null) spelledNow.put(after, today);
        }
        return List.copyOf(out);
    }

    /** The key this entry's fix leaves behind, or null when it renames nothing this can follow. */
    private static String renamedTo(Migration migration, String declared) {
        if (!migration.isAutomatic()) return null;
        String[] parts = declared.split("#", 2);
        String type = parts[0];
        String member = parts.length > 1 ? parts[1] : "";
        JsonNode options = migration.fix().options();
        return switch (migration.fix().kind()) {
            case "renameMethod", "renameField" -> type + "#" + options.path("to").asText(member);
            case "renameType" -> lastSegment(options.path("to").asText(type)) + "#" + member;
            case "moveMember" -> lastSegment(options.path("toType").asText(type))
                    + "#" + options.path("to").asText(member);
            default -> null;
        };
    }

    /** {@code com.botmaker.sdk.api.Key#ENTER} → {@code Key#ENTER} — what a call site is keyed by. */
    private static String simpleKeyOf(String member) {
        String[] parts = member.split("#", 2);
        return lastSegment(parts[0]) + "#" + (parts.length > 1 ? parts[1] : "");
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** Ascending semver where both sides parse, falling back to text so the sort stays total. */
    private static int compareVersions(String a, String b) {
        if (SemVer.isValid(a) && SemVer.isValid(b)) return SemVer.compare(a, b);
        return a.compareTo(b);
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
