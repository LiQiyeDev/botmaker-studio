package com.botmaker.studio.services;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.parser.refactor.SdkReferences;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.SemVer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.classgraph.AnnotationInfo;
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
 * <h2>The repair is a default value, and the SDK declares almost nothing</h2>
 *
 * <p>Until 2026-08-22 every break had to ship its own repair — a {@code fix} in
 * {@code META-INF/botmaker/migrations.json} naming another member to point the call at. That was guessing:
 * two members need not share a return type, an arity or any semantics, and a wrong guess yields a bot that
 * compiles and behaves differently, which is the one outcome worse than a compile error. So the model
 * inverted. <b>The repair's job is to make the bot compile; the user's job is to make it correct.</b> A call
 * to a member the target no longer offers is replaced with a <em>default value of the type it used to give
 * back</em> — {@code false}, {@code 0}, {@code ""}, {@code null} — and a call standing as a statement of its
 * own is deleted outright.
 *
 * <p>Two pairings survive that deletion, because a rename must stay a rename: {@code ImageClicker} →
 * {@code IClicker} is one file-wide edit, and as a removal it would be hundreds of defaulted statements.
 *
 * <ol>
 *   <li><b>{@code @ApiId}</b> — {@code com.botmaker.sdk.api.ApiId}, a stable kebab-case identity on every
 *       public API type, read straight out of both jars. Both releases spell the id the same way, so the
 *       pairing is a <em>fact</em> rather than a declaration. Absence of an id is itself the signal that the
 *       role is gone: it can never invent a counterpart.</li>
 *   <li><b>{@code migrations.json}</b> — renames only ({@code schema} 2), the fallback for what ids cannot
 *       reach, chiefly anything renamed relative to v1.0.26, which carries no ids at all.</li>
 * </ol>
 *
 * <p>An id pairs the <b>type name only</b>. Every member is still resolved individually against the paired
 * type, so an id kept across a redesign degrades to defaults plus review marks rather than a silently wrong
 * rewrite.
 *
 * <p>{@link #apply} then carries it out: snapshot → repair the source
 * ({@code parser/refactor/SdkMigrationRunner}) → bump the pom, one button and one revert away.
 *
 * <p>This lineage is worth one line: an OpenRewrite recipe YAML → a declarative fix engine → this.
 * OpenRewrite existed to let a user migrate with no Studio at all; once that stopped being a requirement, an
 * engine we do not control bought nothing {@code parser/refactor/CallMigrator} could not do. One consequence
 * removed a constraint rather than adding one: OpenRewrite type-attributes against the <em>old</em> SDK, so
 * the rewrite had to run before the pom was bumped, and the dialog had to teach that ordering. Our rewriter
 * resolves the SDK not at all, so snapshot → migrate → bump is a single operation.
 *
 * <h2>The one break that still cannot be repaired</h2>
 *
 * <p>A <b>removed type with no pairing</b> refuses the upgrade, naming the type and its uses. A default has
 * nowhere to go in {@code ImageTemplate t = …;} and {@code Object} would be silently wrong. Everything else
 * is repairable, which is why {@link Report#canMigrate()} is now a question about the jars rather than about
 * what the SDK author remembered to declare.
 *
 * <p>Studio is still the version that lags, so a {@code schema} above {@link #MIGRATIONS_MAX_SCHEMA} is
 * refused <em>whole</em> (one line in {@link Report#problems()}; breaks are still reported, since those come
 * from scanning the jar). Half a migration is the failure {@code CallMigrator.rewriteOthers} returns
 * {@code null} to prevent.
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
     * <p>Schema 2 is renames only. Schema 1's {@code fix} grammar is gone, along with the engine that read
     * it; a schema-1 file is still read, and its {@code from}/{@code to} pairs are simply absent, which is
     * the right answer — there were none.
     */
    public static final int MIGRATIONS_MAX_SCHEMA = 2;

    /** The annotation both jars spell a type's stable identity with. Class-retained, so it is in the jar. */
    private static final String API_ID = "com.botmaker.sdk.api.ApiId";

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
        /**
         * The class is gone from the target SDK and nothing pairs with it — neither an {@code @ApiId} it
         * kept nor a rename the SDK declares. <b>The one break that cannot be repaired</b>: a default value
         * has nowhere to go in {@code ImageTemplate t = …;}, so the upgrade is refused rather than half-made.
         */
        TYPE_REMOVED,
        /**
         * The class was renamed and this bot still writes the old name. Repaired file-wide by
         * {@code CallMigrator.renameTypeIn} — including the declarations, casts and type arguments no call
         * scan records. Listed as a break because the bot does not compile until it is made.
         */
        TYPE_RENAMED,
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
     * {@code detail} is display text — the old and new signatures, or the type's new name — and is empty for
     * a plain removal.
     *
     * <p>{@code repair} is the sentence the dialog shows beside it: what Studio will write in its place. It
     * is display text rather than a code, because there are only two shapes of repair now (rename the type,
     * or stand a default value in) and neither needs to be switched on anywhere but here.
     */
    public record Break(String type, String member, BreakKind kind, String detail, String repair,
                        List<CallSite> sites) {

        /** {@code Mouse.click} / {@code new ImageTemplate} — how the user reads it in their own code. */
        public String display() {
            if (kind == BreakKind.TYPE_REMOVED || kind == BreakKind.TYPE_RENAMED) return type;
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }

        /** False for exactly one kind — see {@link BreakKind#TYPE_REMOVED}. */
        public boolean isRepairable() {
            return kind != BreakKind.TYPE_REMOVED;
        }
    }

    /** A member this bot calls that is {@code @Deprecated} in the target SDK. Compiles; will not forever. */
    public record Deprecation(String type, String member, List<CallSite> sites) {
        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }
    }

    /**
     * The renames the target jar declares, composed across every version in {@code (from, to]}.
     *
     * <p>Both maps are keyed and valued the way {@code migrations.json} writes them: a fully-qualified type
     * name, or {@code fqn#member}. They are the <em>fallback</em> pairing — {@code @ApiId} answers first, and
     * answers for free — and exist chiefly for anything renamed relative to v1.0.26, which carries no ids.
     *
     * <p><b>Composed, not replayed.</b> A bot jumping 1.x → 3.0 still spells a twice-renamed member the 1.x
     * way, so every version in the span is folded into one map, ascending, and the rewrite makes a single
     * pass. {@code a→b} in 2.0 then {@code b→a} in 3.0 composes to the identity and is dropped, where
     * re-running passes until nothing changed would loop forever.
     */
    public record Renames(Map<String, String> types, Map<String, String> members) {

        static final Renames NONE = new Renames(Map.of(), Map.of());

        boolean isEmpty() {
            return types.isEmpty() && members.isEmpty();
        }
    }

    /** One declared rename, for display only: {@code 2.0.0 — ImageClicker → IClicker}. */
    public record Rename(String version, String from, String to) {
        public String display() {
            return from + " → " + to;
        }
    }

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * <p>{@code problems} is what the scan could <em>not</em> determine — an unresolvable jar, a file that
     * does not parse. It is separate from the findings on purpose: an empty {@code breaks} list means
     * something quite different depending on whether this one is empty too.
     *
     * <p>{@code renames} is what the SDK <em>declares</em>, shown so the user can read the release's own
     * account of itself. It is not the list of what will be rewritten — that is {@code breaks}, which is
     * intersected with this bot's own call sites and covers the {@code @ApiId} pairings too.
     */
    public record Report(String from, String to,
                         List<String> added,
                         List<Deprecation> deprecated,
                         List<Break> breaks,
                         List<Rename> renames,
                         List<String> problems) {

        /** The breaks Studio will repair itself — a type rename, or a default value standing in. */
        public List<Break> repairable() {
            return breaks.stream().filter(Break::isRepairable).toList();
        }

        /** The breaks nothing can repair: a removed type with no pairing. See {@link BreakKind#TYPE_REMOVED}. */
        public List<Break> unrepairable() {
            return breaks.stream().filter(b -> !b.isRepairable()).toList();
        }

        /**
         * Whether the upgrade may repair the source: the scan read everything, something needs repairing, and
         * nothing in it is a removed type with no counterpart.
         *
         * <p>One unrepairable break disables the whole span rather than the file it sits in. The alternative
         * — rewrite what we can and leave the rest — is the half-migration the whole design refuses: the user
         * would be left with a project that is neither the old shape nor the new one, and no way to tell
         * which call sites were touched.
         */
        public boolean canMigrate() {
            return problems.isEmpty() && unrepairable().isEmpty() && !breaks.isEmpty();
        }

        /** True when the scan ran cleanly and found nothing that would stop this bot compiling. */
        public boolean nothingBreaks() {
            return breaks.isEmpty() && problems.isEmpty();
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
        Renames declared = renames(newJar, from, to, problems);
        Pairing pairing = Pairing.of(before, after, declared);

        return new Report(from, to,
                additions(before, after),
                deprecations(before, after, calls, pairing),
                breaks(before, after, calls, pairing),
                renameList(newJar, from, to),
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
     * carrying a removed type nothing pairs with still has to be <em>switchable</em>: the user reads which
     * type it is and where they use it, makes those edits themselves, and moves. Refusing the whole button in
     * that case would be a trap with no way out, since the target jar goes on lacking that type forever. What
     * it must never do is repair half of the span, which is why the flag is all-or-nothing rather than per
     * break.
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
     * Repairs the project's own files against the target jar, or throws saying why it will not. An upgrade
     * that breaks nothing is not an error — most of them don't.
     *
     * <p>Everything it needs it works out again from the two jars: the report the user read is a value, and a
     * value that crossed a dialog and an FX thread is not evidence about the files on disk right now.
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
        Renames declared = renames(newJar.get(), from, targetVersion, problems);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        Map<String, ApiClass> before = snapshot(oldJar.get());
        Map<String, ApiClass> after = snapshot(newJar.get());
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Map<String, List<String>> fieldOwners = fieldOwners(before, after);
        Pairing pairing = Pairing.of(before, after, declared);

        List<Call> calls = callsIn(known, fieldOwners, problems);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        List<Break> breaks = breaks(before, after, calls, pairing);
        Break refused = breaks.stream().filter(b -> !b.isRepairable()).findFirst().orElse(null);
        if (refused != null) {
            throw new IllegalStateException("\"" + refused.type() + "\" is gone from SDK " + targetVersion
                    + " and nothing in that release takes its place, so there is no value to stand in for it "
                    + "where this bot writes the type itself. Change those " + refused.sites().size()
                    + " place(s) by hand, then upgrade. Nothing has been changed.");
        }

        SdkMigrationRunner.Repairs repairs = repairsFor(before, after, calls, pairing);
        if (repairs.isEmpty()) return;

        List<ProjectFile> editable = new ArrayList<>();
        List<ProjectFile> generated = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            // FileRole is the single source of truth for "may the user change this?", and the migration
            // answers to the same rule the editor does — see SdkMigrationRunner on why a scaffold file is
            // refused rather than rewritten.
            (FileRole.of(config, state.getTemplate(), file.getPath()) == FileRole.EDITABLE ? editable : generated)
                    .add(file);
        }

        SdkMigrationRunner.Outcome outcome = SdkMigrationRunner.run(repairs, editable, generated,
                known, fieldOwners, config.mainPackage(), null, state);
        if (outcome.isRefusal()) throw new IllegalStateException(outcome.refusal());
        try {
            // The annotation the rewritten files now reference. Written before them, so the project never
            // exists in a state where a mark names a type that isn't there — and only when the migration has
            // already agreed to write something, so a refused upgrade adds no file at all.
            ReviewMarks.ensureFile(config.mainPackageDir(), config.mainPackage());
            CallMigrator.commit(outcome.files());
        } catch (IOException e) {
            throw new RuntimeException("Some files could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * What the rewriter has to do, derived from the two jars and this bot's own call sites.
     *
     * <p>Only types this bot actually mentions are renamed: a file-wide rename is cheap but not free, and a
     * project that never heard of {@code ImageClicker} should not have its files rewritten to themselves.
     */
    private static SdkMigrationRunner.Repairs repairsFor(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after,
                                                         List<Call> calls, Pairing pairing) {
        Map<String, SdkMigrationRunner.TypeRename> types = new LinkedHashMap<>();
        Map<String, SdkMigrationRunner.MemberRename> members = new LinkedHashMap<>();
        Map<String, SdkMigrationRunner.Removal> removals = new LinkedHashMap<>();

        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            if (then == null || !declares(then, call)) continue;

            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) continue;                      // refused above; nothing to write
            if (!now.simpleName().equals(then.simpleName())) {
                types.putIfAbsent(then.simpleName(), new SdkMigrationRunner.TypeRename(
                        then.name(), now.name()));
            }

            String member = pairing.memberName(then, call.member());
            if (offers(now, member, call.argCount())) {
                if (!member.equals(call.member())) {
                    members.putIfAbsent(then.simpleName() + "#" + call.member(),
                            new SdkMigrationRunner.MemberRename(then.simpleName(), call.member(), member));
                }
                continue;
            }
            removals.putIfAbsent(then.simpleName() + "#" + call.member() + "#" + call.argCount(),
                    new SdkMigrationRunner.Removal(then.simpleName(), call.member(), call.argCount(),
                            returnTypeOf(then, call)));
        }
        return new SdkMigrationRunner.Repairs(List.copyOf(types.values()), List.copyOf(members.values()),
                List.copyOf(removals.values()));
    }

    /**
     * The type the old jar said this call gives back — the value the code around it was written for, and so
     * the type whose default stands in when the member is gone. {@code void} for a call made for its effect,
     * which is deleted rather than defaulted.
     */
    private static String returnTypeOf(ApiClass then, Call call) {
        return then.byName().getOrDefault(call.member(), List.of()).stream()
                .filter(m -> call.isField() ? m.field() : !m.field() && m.params().size() == call.argCount())
                .map(ApiMember::type)
                .findFirst()
                // An arity nothing matched is a SIGNATURE_CHANGED break: any overload's type is a better
                // guess than none, and they are usually the same.
                .orElseGet(() -> then.byName().getOrDefault(call.member(), List.of()).stream()
                        .map(ApiMember::type).findFirst().orElse("void"));
    }

    // =========================================================================
    // THE TWO JARS
    // =========================================================================

    /**
     * One public API class, reduced to what a compatibility question can be asked of.
     *
     * <p>{@code apiId} is the {@code @ApiId} value, or null for a type that carries none — which is not a
     * gap to be filled in but the signal itself: <b>absence of an id means this role is gone</b>, and a
     * pairing can never be invented for it. Every type in v1.0.26 and earlier is in that position, which is
     * what {@code migrations.json}'s rename list exists to cover.
     */
    private record ApiClass(String name, String simpleName, String apiId, boolean deprecated,
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
     *
     * <p>{@code type} is what it gives back, written as source names it — the <b>old</b> jar's answer, since
     * that is what the code around the call site was written for, and so what a default value standing in for
     * a removed member has to be a default of. A constructor's is its own class: {@code new ImageTemplate(…)}
     * yields an {@code ImageTemplate}.
     */
    private record ApiMember(String name, String type, List<String> params, boolean field) {
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
            String type = mi.isConstructor()
                    ? ci.getSimpleName()
                    : lastSegment(mi.getTypeSignatureOrTypeDescriptor().getResultType().toString());
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new ApiMember(name, type, paramsOf(mi), false));
            // A name counts as deprecated only when every overload carrying it is — same rule as
            // SdkSurfaceService, and for the same reason: the user reads a name, not an overload.
            (mi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(name);
        }
        // Fields go through the same map and the same deprecation rule. Enum constants need no special
        // case: the compiler emits each one as a public static field of the enum type.
        for (FieldInfo fi : ci.getFieldInfo()) {
            if (!fi.isPublic() || fi.isSynthetic()) continue;
            byName.computeIfAbsent(fi.getName(), k -> new ArrayList<>())
                    .add(new ApiMember(fi.getName(), lastSegment(fi.getTypeDescriptor().toString()),
                            List.of(), true));
            (fi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(fi.getName());
        }
        deprecatedNames.removeAll(liveNames);
        return new ApiClass(ci.getName(), ci.getSimpleName(), apiIdOf(ci),
                ci.hasAnnotation(Deprecated.class.getName()),
                Map.copyOf(byName), Set.copyOf(deprecatedNames));
    }

    /**
     * The {@code @ApiId} value on a class, or null.
     *
     * <p>Read out of the bytecode by the ClassGraph scan {@code TypeSummaryManager} already runs with
     * {@code enableAnnotationInfo()}. {@code ApiId} is {@code @Retention(CLASS)} rather than {@code RUNTIME}
     * for exactly this: it never has to be reflected on at run time, only read off a jar that is not on any
     * classpath.
     */
    private static String apiIdOf(ClassInfo ci) {
        AnnotationInfo annotation = ci.getAnnotationInfo(API_ID);
        if (annotation == null) return null;
        Object value = annotation.getParameterValues(true).getValue("value");
        String id = value == null ? "" : value.toString().trim();
        return id.isEmpty() ? null : id;
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

    // =========================================================================
    // PAIRING: WHICH TYPE IN THE NEW JAR IS THIS OLD ONE?
    // =========================================================================

    /**
     * Which type in the target jar takes each old type's place, and what each member is now called.
     *
     * <p>Three answers, tried in order, and the order is the design:
     *
     * <ol>
     *   <li><b>The same name.</b> Nothing moved; nothing to write.</li>
     *   <li><b>The same {@code @ApiId}.</b> A rename that is <em>known</em> rather than declared, because both
     *       releases spelled the id the same way. Nothing has to be remembered, which is the point — a rename
     *       nobody wrote down is still a rename.</li>
     *   <li><b>A declared rename</b> from {@code migrations.json}, composed across the span. The fallback for
     *       what ids cannot reach: an old release that carries none, or an id retired on purpose.</li>
     * </ol>
     *
     * <p>Then <b>unpaired</b>, which is an answer and not a failure. Absence of an id is the signal that the
     * role is gone, and this must never invent a counterpart: a wrongly paired type is a bot that compiles and
     * does something else.
     *
     * <p>An id pairs the <b>type name only</b>. Every member is still looked up individually on whatever this
     * returns, so an id kept across a redesign that dropped half the class degrades to defaults and review
     * marks rather than a silently wrong rewrite.
     */
    private record Pairing(Map<String, String> types, Renames declared) {

        static Pairing of(Map<String, ApiClass> before, Map<String, ApiClass> after, Renames declared) {
            Map<String, ApiClass> byId = new LinkedHashMap<>();
            for (ApiClass now : after.values()) {
                // First id wins. Two types sharing one id is the SDK breaking its own retire-never-reuse
                // rule; picking either is a guess, and picking the first at least is a stable one.
                if (now.apiId() != null) byId.putIfAbsent(now.apiId(), now);
            }

            Map<String, String> pairs = new LinkedHashMap<>();
            for (ApiClass then : before.values()) {
                if (after.containsKey(then.simpleName())) {
                    pairs.put(then.simpleName(), then.simpleName());
                    continue;
                }
                ApiClass byApiId = then.apiId() == null ? null : byId.get(then.apiId());
                if (byApiId != null) {
                    pairs.put(then.simpleName(), byApiId.simpleName());
                    continue;
                }
                String renamed = declared.types().get(then.name());
                ApiClass target = renamed == null ? null : after.get(lastSegment(renamed));
                if (target != null) pairs.put(then.simpleName(), target.simpleName());
            }
            return new Pairing(Map.copyOf(pairs), declared);
        }

        /** The old type's counterpart in {@code after}, or null when nothing takes its place. */
        ApiClass pairedTo(ApiClass then, Map<String, ApiClass> after) {
            String name = types.get(then.simpleName());
            return name == null ? null : after.get(name);
        }

        /**
         * What {@code member} of {@code then} is called in the target — itself, unless a rename says so.
         *
         * <p>The entry's {@code to} may name another type as well ({@code A#foo} → {@code B#bar}); only the
         * member half is read here, because which type the site now writes is the type pairing's answer and
         * having two sources for it is how they come to disagree.
         */
        String memberName(ApiClass then, String member) {
            String renamed = declared.members().get(then.name() + "#" + member);
            return renamed == null ? member : memberPart(renamed);
        }
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
    private static List<Deprecation> deprecations(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                                  List<Call> calls, Pairing pairing) {
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();
        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            // Through the pairing, so a renamed-but-deprecated type is still reported: the bot writes the old
            // name, and looking that up in the new jar would find nothing and say nothing.
            ApiClass now = then == null ? after.get(call.type()) : pairing.pairedTo(then, after);
            if (now == null) continue;
            String member = then == null ? call.member() : pairing.memberName(then, call.member());
            boolean deprecated = now.deprecated() || now.deprecatedNames().contains(member);
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
     * Calls that would stop compiling, and what Studio will write in their place. Only members the
     * <em>old</em> jar actually had are judged: a call to something neither jar declares is the bot's own code
     * (or an unindexed library), not a break this upgrade causes.
     *
     * <p>A renamed type yields <b>two</b> findings where both apply — one {@link BreakKind#TYPE_RENAMED} for
     * the type, and, for a member that also went, its own removal under the new type. That is the pairing rule
     * made visible: the id paired the name, the members were still resolved one at a time.
     */
    private static List<Break> breaks(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                      List<Call> calls, Pairing pairing) {
        Map<String, Break> found = new LinkedHashMap<>();
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();

        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            // In the shape the bot uses it: a name the old jar had only as a method is not evidence that
            // this file's `Foo.NAME` was ever SDK, and vice versa.
            if (then == null || !declares(then, call)) continue;

            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) {
                record(found, sites, new Break(call.type(), "", BreakKind.TYPE_REMOVED, "",
                        "nothing — this one has to be changed by hand", List.of()), call.site());
                continue;
            }
            if (!now.simpleName().equals(then.simpleName())) {
                record(found, sites, new Break(call.type(), "", BreakKind.TYPE_RENAMED,
                        "now " + now.simpleName(),
                        "every use of \"" + call.type() + "\" becomes \"" + now.simpleName() + "\"",
                        List.of()), call.site());
            }

            String member = pairing.memberName(then, call.member());
            if (offers(now, member, call.argCount())) continue;

            BreakKind kind;
            String detail = "";
            if (!declares(now, call.isField(), member)) {
                // Covers a field turned into a method (and the reverse) as well as an outright removal —
                // every one of them stops this call site compiling.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
            } else {
                kind = BreakKind.SIGNATURE_CHANGED;
                detail = "was " + signatures(then, call.member()) + " — now " + signatures(now, member);
            }
            record(found, sites, new Break(call.type(), call.member(), kind, detail,
                    repairText(returnTypeOf(then, call)), List.of()), call.site());
        }

        return found.entrySet().stream()
                .map(e -> {
                    Break b = e.getValue();
                    return new Break(b.type(), b.member(), b.kind(), b.detail(), b.repair(),
                            sorted(sites.get(e.getKey())));
                })
                .sorted(Comparator.comparing(Break::display))
                .toList();
    }

    private static void record(Map<String, Break> found, Map<String, List<CallSite>> sites,
                               Break unsited, CallSite site) {
        String key = unsited.type() + "#" + unsited.member() + "#" + unsited.kind();
        found.putIfAbsent(key, unsited);
        sites.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
    }

    /** What the user is told will stand in — the one sentence that says the model out loud. */
    private static String repairText(String returnType) {
        return "void".equals(returnType)
                ? "the call is removed, and the function is marked for your review"
                : "replaced with " + defaultTextOf(returnType) + ", and the function is marked for your review";
    }

    /** The default this type gets, spelled as the rewrite will spell it — the rewriter's own switch. */
    private static String defaultTextOf(String type) {
        return CallMigrator.literalDefaultText(type);
    }

    /** Whether {@code klass} offers this call's member in the shape the call site uses it. */
    private static boolean declares(ApiClass klass, Call call) {
        return declares(klass, call.isField(), call.member());
    }

    private static boolean declares(ApiClass klass, boolean field, String member) {
        return field ? klass.declaresField(member) : klass.declaresCallable(member);
    }

    /**
     * Whether {@code klass} offers {@code member} in the exact shape a call site uses it — a field, or a
     * callable taking that many arguments.
     *
     * <p>Arity, not types: without bindings the argument <em>types</em> at the call site are unknown, and
     * claiming a break that isn't one is worse than missing one — the user can always compile. A field shares
     * the map but has no parameter list at all, so it must not answer for arity 0.
     */
    private static boolean offers(ApiClass klass, String member, int argCount) {
        if (argCount == SdkReferences.FIELD_READ) return klass.declaresField(member);
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
     * The renames the target jar declares, for every version in {@code (from, to]}, <b>composed</b> into one
     * map per kind.
     *
     * <p>Composition rather than replay is the whole reason the version keys survived the deletion of the fix
     * engine. A bot jumping 1.x → 3.0 has never run the 2.0 pass, so it still spells a twice-renamed member
     * the 1.x way: {@code foo→bar} in 2.0 and {@code bar→baz} in 3.0 have to become the single fact
     * {@code foo→baz} before anything is rewritten. Folding forward gets that, and gets the hard case free —
     * {@code a→b} then {@code b→a} composes to the identity and is dropped, where re-running passes until
     * nothing changed would loop forever on it.
     *
     * <p>When either bound is not a version {@link SemVer} understands — {@code 0.0.0-SNAPSHOT}, most often —
     * every version is folded in rather than none. Composing a rename the bot has already had applied costs
     * nothing (the old name appears nowhere); missing one costs a file naming a class that is gone.
     */
    private static Renames renames(Path targetJar, String from, String to, List<String> problems) {
        Optional<String> json = readJarEntry(targetJar, MIGRATIONS_ENTRY);
        if (json.isEmpty()) return Renames.NONE;

        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> members = new LinkedHashMap<>();
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
                return Renames.NONE;
            }

            for (Rename rename : declaredRenames(root, from, to)) {
                compose(rename.from().contains("#") ? members : types, rename.from(), rename.to());
            }
        } catch (Exception e) {
            problems.add("SDK " + to + " ships a migration file that could not be read (" + e.getMessage()
                    + "), so what it declares about this upgrade is unknown.");
            return Renames.NONE;
        }
        return new Renames(Map.copyOf(types), Map.copyOf(members));
    }

    /**
     * Folds {@code from → to} into a map that may already end at {@code from}: every key currently pointing
     * at {@code from} is re-pointed at {@code to}, and a chain that comes back to its own start is dropped.
     */
    private static void compose(Map<String, String> map, String from, String to) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue().equals(from) && !entry.getKey().equals(from)) entry.setValue(to);
        }
        map.put(from, to);
        map.entrySet().removeIf(entry -> entry.getKey().equals(entry.getValue()));
    }

    /**
     * Every declared rename in {@code (from, to]}, ascending — the list the report shows and the order
     * {@link #compose} has to see them in.
     */
    private static List<Rename> declaredRenames(JsonNode root, String from, String to) {
        List<Rename> out = new ArrayList<>();
        JsonNode versions = root.path("versions");
        versions.fieldNames().forEachRemaining(version -> {
            if (!inRange(version, from, to)) return;
            for (JsonNode entry : versions.path(version)) {
                String was = entry.path("from").asText("");
                String now = entry.path("to").asText("");
                if (!was.isBlank() && !now.isBlank()) out.add(new Rename(version, was, now));
            }
        });
        out.sort(Comparator.comparing((Rename r) -> strip(r.version()), SdkUpgradeService::compareVersions)
                .thenComparing(Rename::from));
        return out;
    }

    /** The same list, for display — read again rather than threaded through, since it is only ever shown. */
    private static List<Rename> renameList(Path targetJar, String from, String to) {
        Optional<String> json = readJarEntry(targetJar, MIGRATIONS_ENTRY);
        if (json.isEmpty()) return List.of();
        try {
            return declaredRenames(new ObjectMapper().readTree(json.get()), from, to);
        } catch (Exception e) {
            // Already reported as a problem by renames(); a second sentence saying the same thing is noise.
            return List.of();
        }
    }

    /** {@code com.botmaker.sdk.api.Key#ENTER} → {@code ENTER}; a name with no {@code #} is its own answer. */
    private static String memberPart(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(hash + 1);
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
