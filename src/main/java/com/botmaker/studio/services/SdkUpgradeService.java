package com.botmaker.studio.services;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.SdkMigrationRunner;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.parser.refactor.SdkReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.SemVer;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

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
 * <h2>A redirect where the jars confirm it, a default where they do not</h2>
 *
 * <p>The SDK once shipped a repair per break — a {@code fix} in {@code META-INF/botmaker/migrations.json}
 * naming another member to point the call at — and it was guessing, because nothing checked it: two members
 * need not share a return type, an arity or any semantics. What replaced it is not the absence of a redirect
 * but a <b>checked</b> one. Studio holds both jars, so it can ask the questions that objection was really
 * about, and the position of the call decides which it has to ask:
 *
 * <ul>
 *   <li>a call standing as a <b>statement</b> discards its value, so the target's return type cannot make
 *       the redirect wrong. It is taken. (A pure default repair <em>deletes</em> that statement, throwing
 *       away work the bot did.)</li>
 *   <li>a call whose value is <b>used</b> is redirected only when what comes back still fits where the old
 *       value sat — the same type, a subtype of it in the target jar, or a widening primitive.</li>
 * </ul>
 *
 * <p>Arity is not a reason to refuse either: the arguments the call already passes are kept in order and the
 * difference is filled with literal defaults or dropped, which is {@code SignatureMigration}'s own machinery.
 * <b>Everything the check refuses falls back to the old answer</b> — a <em>default value of the type it used
 * to give back</em> ({@code false}, {@code 0}, {@code ""}, {@code null}), a deleted statement for a
 * {@code void}, and a review mark either way. <b>The repair's job is to make the bot compile; the user's job
 * is to make it correct.</b>
 *
 * <p>What says where a member went is a <b>pointer written at both ends</b> — and the two ends are why it
 * needs no jar but the two already in hand:
 *
 * <ol>
 *   <li><b>{@code @ReplacedBy}</b>, read out of the bot's <em>own</em> jar: the forward half, on the
 *       deprecated element, naming what to use instead. The bot still spells the element the old way, so
 *       that is where the pointer to the new spelling has to be. An <em>empty</em> value is the author
 *       saying outright that nothing takes its place.</li>
 *   <li><b>{@code @Replaces}</b>, read out of the <em>target</em> jar: the backward half, on the survivor,
 *       naming every older spelling it took over and the last version each of those spellings existed in.
 *       It is the only place the answer survives once the deprecated element is finally deleted.</li>
 * </ol>
 *
 * <p>Either half alone resolves one hop. <b>Composed</b>, they resolve a chain — {@code a}→{@code b}
 * announced in 2.0 and {@code b}→{@code c} in 3.0 lands a bot still spelling it {@code a} on {@code c},
 * with no intermediate jar ever fetched. Absence of a pointer is an answer and not a gap: nothing is ever
 * paired by guesswork, because a wrong pairing is a bot that compiles and behaves differently.
 *
 * <p><b>Types and members pair independently.</b> A member pointer may cross types, and a paired type does
 * not vouch for its members: each one is still resolved on its own, so a pointer kept across a redesign
 * degrades to defaults plus review marks rather than a silently wrong rewrite.
 *
 * <p>{@link #apply} then carries it out: snapshot → repair the source
 * ({@code parser/refactor/SdkMigrationRunner}) → bump the pom, one button and one revert away.
 *
 * <h2>Modernising: the same walk, one hop further, no version change</h2>
 *
 * <p>A pointer says where something went whether or not it has gone yet — that is what a deprecation window
 * <em>is</em>, both ends present at once. So {@link #modernisations()} asks the identical question of a
 * single jar, the one the bot already pins: the graph is walked with one extra rule, that a spelling the jar
 * marks {@code @Deprecated} <em>and</em> points somewhere is walked past rather than accepted. Everything
 * downstream — the shape check, the arity repair, the review marks, the all-or-nothing commit — is the code
 * that was already there, which is the reason it is a stopping rule and not a second engine.
 *
 * <p>Two things reach it: <b>Project ▸ Modernise…</b>, which touches no pom at all, and the upgrade dialog's
 * "also move off deprecated members", where the extra hop is taken during the upgrade so a bot does not
 * arrive on the new version already owing the same work. The one rule that differs is that modernising never
 * writes a default value: a deprecated member is still there, so anything the shape check refuses is left
 * alone and stays on the list, where the user can see it.
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

    /** The forward pointer, on the deprecated element. Class-retained, so it survives into the jar. */
    private static final String REPLACED_BY = "com.botmaker.sdk.api.meta.ReplacedBy";

    /** The backward pointer, on the survivor. Same retention, same reason. */
    private static final String REPLACES = "com.botmaker.sdk.api.meta.Replaces";

    /**
     * Where the pointers lived before SDK 1.1.0 moved them into {@code api.meta}. Both spellings are read,
     * and the legacy one is not a courtesy: the jar being upgraded <em>from</em> is by definition the older
     * of the two, and for a bot coming off 1.0.x it is the only jar carrying the forward pointer on the
     * element that bot still calls. Reading only the new name would silently turn every pre-1.1.0 redirect
     * into an unpaired break — the exact failure the pointer pair exists to prevent.
     */
    private static final String REPLACED_BY_LEGACY = "com.botmaker.sdk.api.ReplacedBy";

    /** @see #REPLACED_BY_LEGACY */
    private static final String REPLACES_LEGACY = "com.botmaker.sdk.api.Replaces";

    /**
     * The release an element first appeared in, which is what gives "What's new" its eras.
     *
     * <p>There is no legacy spelling to read for this one or for {@link #SCAFFOLDING}, and that is a fact
     * about the SDK rather than an omission: both were introduced in the same unreleased window that moved
     * the whole surface into {@code api.meta}, so no published jar has ever carried them under {@code api}.
     */
    private static final String SINCE = "com.botmaker.sdk.api.meta.Since";

    /** Marks a member Studio's own generated files write — see {@link Report#scaffolding()}. */
    private static final String SCAFFOLDING = "com.botmaker.sdk.api.meta.Scaffolding";

    /** A constructor has no name of its own; this is how the pointer grammar spells one. */
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
         * The class is gone from the target SDK and no pointer, at either end, names what took its place.
         * <b>The one break that cannot be repaired</b>: a default value
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
     * <p>{@code repair} is the sentence the dialog shows beside it: what Studio will write in its place —
     * renaming the type, pointing the call at where the member went, or standing a default value in. It is
     * display text rather than a code because nothing but the dialog reads it: the edit itself is derived
     * again from the jars at the moment of writing, never from this record.
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

    /**
     * A member this bot calls that is {@code @Deprecated} in the SDK being read. Compiles; will not forever.
     *
     * <p>{@code becomes} and {@code repair} are the two halves of the answer <em>the SDK itself</em> gives:
     * the {@code @ReplacedBy} target resolved against that same jar, and the sentence saying what moving
     * there would cost. Both are empty when the member points nowhere — a deprecation the author has not
     * said what to do about is a deprecation nothing can act on, and saying so is the point of the pair
     * being empty rather than absent.
     */
    public record Deprecation(String type, String member, String becomes, String repair,
                              List<CallSite> sites) {
        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }

        /** Whether Studio can move these calls itself — something took its place and the shapes line up. */
        public boolean isMovable() {
            return !repair.isEmpty();
        }
    }

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * <p>{@code problems} is what the scan could <em>not</em> determine — an unresolvable jar, a file that
     * does not parse, an old spelling two survivors both claim. It is separate from the findings on purpose:
     * an empty {@code breaks} list means something quite different depending on whether this one is empty too.
     *
     * <p>{@code addedBySince} is the new API <b>grouped by the release it arrived in</b>, newest first, read
     * from {@code @Since}. A flat alphabetical list of names is a cost sheet, not a reason to upgrade: what
     * the user is deciding is whether to move, and "these six things arrived in 1.2.0" is the shape of that
     * answer. A jar carrying no {@code @Since} at all lands whole in the one unlabelled bucket, which is
     * exactly today's list — every new reader degrades.
     *
     * <p>{@code scaffolding} is the release's contact with the members <em>Studio's own generated files</em>
     * write. It is stated up front rather than discovered mid-apply, which is where
     * {@code SdkMigrationRunner.scaffoldingInTheWay} finds it: a refusal that arrives after the user has
     * committed to the upgrade is the same information delivered at the worst possible moment.
     */
    public record Report(String from, String to,
                         Map<String, List<String>> addedBySince,
                         List<Deprecation> deprecated,
                         List<Break> breaks,
                         List<String> scaffolding,
                         List<String> problems) {

        /** Everything new, as one list — the exhaustive answer, for a reader that does not want the eras. */
        public List<String> added() {
            return addedBySince.values().stream().flatMap(List::stream).toList();
        }

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

        /** The deprecated members Studio can move off by itself — what "Modernise" would actually rewrite. */
        public List<Deprecation> movable() {
            return deprecated.stream().filter(Deprecation::isMovable).toList();
        }

        /**
         * Whether modernising has anything to do. Deliberately not {@link #canMigrate()}: that one asks
         * whether a <em>break</em> may be repaired, and nothing here is broken — every one of these calls
         * compiles today and would go on compiling if the user closed the dialog.
         */
        public boolean canModernise() {
            return problems.isEmpty() && !movable().isEmpty();
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
            return new Report(from, to, Map.of(), List.of(), List.of(), List.of(), List.of(problem));
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
        return compare(targetVersion, false);
    }

    /**
     * The same report, optionally reading <em>through</em> the target's own deprecations.
     *
     * <p>{@code alsoModernise} is the dialog's checkbox, and it changes one thing: a member that survives the
     * upgrade but arrives {@code @Deprecated} with a {@code @ReplacedBy} is followed one hop further, so the
     * report names where it went and the repair moves the call there. With it off, such a member is listed as
     * a deprecation with nothing beside it — which is the honest answer, since it still compiles.
     */
    public Report compare(String targetVersion, boolean alsoModernise) {
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

        return compare(oldJar.get(), newJar.get(), from, to, alsoModernise);
    }

    /**
     * What moving off this SDK's own deprecated members would do — the same question with <b>one</b> jar.
     *
     * <p>There is no version change and so no diff: the jar is compared with itself, and the only thing that
     * moves is what the SDK's authors have already said should move. Every finding therefore lands in
     * {@link Report#deprecated()} and {@link Report#breaks()} comes back empty, because nothing here is
     * broken — that is the whole difference between this and an upgrade, and why it has a question of its
     * own ({@link Report#canModernise()}) rather than borrowing {@link Report#canMigrate()}.
     *
     * <p><b>Blocking</b>, for the same reasons {@link #compare(String)} is.
     */
    public Report modernisations() {
        String version = currentVersion();
        Optional<Path> jar = MavenService.resolveSdkJar(config.projectPath(), version);
        if (jar.isEmpty()) {
            return Report.unavailable(version, version,
                    "The SDK this project pins (" + version + ") could not be resolved, so there is nothing "
                            + "to read its deprecations out of.");
        }
        return compare(jar.get(), jar.get(), version, version, true);
    }

    /**
     * The comparison itself, given the two jars — everything except resolving them.
     *
     * <p>Split out so the diff can be tested against jars built on the spot rather than against whatever
     * happens to be published: the interesting cases (a method removed, an overload's arity changed, a class
     * that went away entirely) are exactly the ones no released pair of versions exhibits yet.
     */
    Report compare(Path oldJar, Path newJar, String from, String to) {
        return compare(oldJar, newJar, from, to, false);
    }

    Report compare(Path oldJar, Path newJar, String from, String to, boolean throughDeprecations) {
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
        Uses uses = usesIn(known, fieldOwners(before, after), problems);
        // Writes into problems too — an old spelling two survivors both claim is a question this cannot
        // answer, and it is recorded before the list is frozen.
        Pairing pairing = Pairing.of(before, after, from, problems, throughDeprecations);

        List<Deprecation> deprecated = deprecations(before, after, uses.calls(), pairing);
        List<Break> breaks = breaks(before, after, uses, pairing);
        return new Report(from, to,
                additions(before, after),
                deprecated,
                breaks,
                scaffolding(before, deprecated, breaks),
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
    public CompletableFuture<Void> apply(String targetVersion, boolean repairSources, boolean alsoModernise) {
        return CompletableFuture
                .runAsync(() -> {
                    snapshot("Before SDK upgrade to " + targetVersion);
                    if (repairSources || alsoModernise) {
                        migrateSources(targetVersion, alsoModernise, true);
                    }
                })
                .thenCompose(v -> libraryService.updateLibraries(libraryService.currentLibraries(),
                        targetVersion));
    }

    /**
     * Moves this bot off the deprecated members of the SDK it already pins — snapshot, then rewrite. No pom
     * is touched, because there is no version change: this is the same repair machinery answering the
     * question the SDK's own {@code @ReplacedBy} pointers pose, at any moment the user chooses.
     *
     * <p>It is the one entry point that is not an upgrade, and the one place a <em>default value</em> is
     * never written: a deprecated member is still there, so there is nothing to stand in for. Anything the
     * shape check refuses is simply left alone and stays on the deprecation list.
     */
    public CompletableFuture<Void> modernise() {
        return CompletableFuture.runAsync(() -> {
            snapshot("Before modernising");
            migrateSources(currentVersion(), true, false);
        });
    }

    /** The one revert away everything here promises. */
    private void snapshot(String message) {
        try {
            new ProjectVcs(config.projectPath()).commit(message);
        } catch (IOException e) {
            throw new RuntimeException("Could not snapshot the project first: " + e.getMessage(), e);
        }
    }

    /**
     * Repairs the project's own files against the target jar, or throws saying why it will not. An upgrade
     * that breaks nothing is not an error — most of them don't.
     *
     * <p>Everything it needs it works out again from the two jars: the report the user read is a value, and a
     * value that crossed a dialog and an FX thread is not evidence about the files on disk right now.
     */
    private void migrateSources(String targetVersion, boolean throughDeprecations, boolean allowDefaults) {
        String from = currentVersion();
        Optional<Path> oldJar = MavenService.resolveSdkJar(config.projectPath(), from);
        Optional<Path> newJar = MavenService.resolveSdkJar(config.projectPath(), targetVersion);
        if (oldJar.isEmpty() || newJar.isEmpty()) {
            throw new IllegalStateException("The SDK jars could not be resolved again, so the upgrade stopped "
                    + "before changing anything. Check the report and try once more.");
        }

        List<String> problems = new ArrayList<>();
        Map<String, ApiClass> before = snapshot(oldJar.get());
        Map<String, ApiClass> after = snapshot(newJar.get());
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Map<String, List<String>> fieldOwners = fieldOwners(before, after);
        Pairing pairing = Pairing.of(before, after, from, problems, throughDeprecations);

        Uses uses = usesIn(known, fieldOwners, problems);
        // The same all-or-nothing rule the report states: anything the scan could not answer — a file that
        // does not parse, an old name two survivors both claim — stops the rewrite before it writes.
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        List<Break> breaks = breaks(before, after, uses, pairing);
        Break refused = breaks.stream().filter(b -> !b.isRepairable()).findFirst().orElse(null);
        if (refused != null) {
            throw new IllegalStateException("\"" + refused.type() + "\" is gone from SDK " + targetVersion
                    + " and nothing in that release takes its place, so there is no value to stand in for it "
                    + "where this bot writes the type itself. Change these by hand, then upgrade: "
                    + String.join(", ", refused.sites().stream().map(CallSite::toString).toList())
                    + ". Nothing has been changed.");
        }

        SdkMigrationRunner.Repairs repairs = repairsFor(before, after, uses, pairing, allowDefaults);
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
     *
     * <p>{@code allowDefaults} is off for exactly one caller — {@link #modernise()}. A default value stands
     * in for something that is <em>gone</em>, and nothing is gone when the two jars are the same one: a
     * deprecated member is still there and still compiles, so a modernisation that cannot be made cleanly is
     * left alone rather than replaced by {@code false}.
     */
    private static SdkMigrationRunner.Repairs repairsFor(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after,
                                                         Uses uses, Pairing pairing,
                                                         boolean allowDefaults) {
        Map<String, SdkMigrationRunner.TypeRename> types = new LinkedHashMap<>();
        Map<String, SdkMigrationRunner.Redirect> redirects = new LinkedHashMap<>();
        Map<String, SdkMigrationRunner.Removal> removals = new LinkedHashMap<>();

        // A type the bot only *writes* — `ImageTemplate t;`, a parameter, a type argument — is renamed on
        // the same evidence as one it calls. The rename itself was always file-wide and so always covered
        // these places; what was missing was any reason to run it on a file that makes no call at all.
        for (TypeUse use : uses.types()) {
            ApiClass then = before.get(use.type());
            if (then == null) continue;
            ApiClass now = pairing.pairedTo(then, after);
            if (now != null && !now.simpleName().equals(then.simpleName())) {
                types.putIfAbsent(then.simpleName(),
                        new SdkMigrationRunner.TypeRename(then.name(), now.name()));
            }
        }

        for (Call call : uses.calls()) {
            ApiClass then = before.get(call.type());
            if (then == null || !declares(then, call)) continue;

            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) continue;                      // refused above; nothing to write
            if (!now.simpleName().equals(then.simpleName())) {
                types.putIfAbsent(then.simpleName(), new SdkMigrationRunner.TypeRename(
                        then.name(), now.name()));
            }

            String key = then.simpleName() + "#" + call.member() + "#" + call.argCount();
            SdkMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);
            if (redirect != null) {
                redirects.putIfAbsent(key, redirect);
                continue;
            }
            // Nothing to redirect to. Either the call already resolves on the paired type — the type sweep
            // will carry it across on its own — or there is nowhere for it to go and a default stands in.
            if (!allowDefaults || offers(now, call.member(), call.argCount())) continue;
            String removed = returnTypeOf(then, call);
            removals.putIfAbsent(key,
                    new SdkMigrationRunner.Removal(then.simpleName(), call.member(), call.argCount(),
                            removed, returnTypeFqn(removed, after)));
        }
        return new SdkMigrationRunner.Repairs(List.copyOf(types.values()), List.copyOf(redirects.values()),
                List.copyOf(removals.values()));
    }

    // =========================================================================
    // THE CHECKED REDIRECT
    // =========================================================================

    /**
     * Where this call should point instead, or null when nothing in the target jar can take it.
     *
     * <p>This is the one place that decides a redirect, and both readers go through it — {@link #breaks} for
     * the sentence the dialog shows, {@link #repairsFor} for the edit. Two answers to one question would
     * eventually be a dialog promising a rewrite the rewriter does not make.
     *
     * <p>It answers null for three quite different things, all of which end the same way (a default value and
     * a review mark): the call already resolves and needs nothing; nothing pairs with it; or something does
     * but the shapes cannot be reconciled. The last group is where the refusals live, and each is deliberate:
     *
     * <ul>
     *   <li><b>a field paired with a method, or a constructor with a method</b> — the source shapes differ,
     *       and rewriting one into the other is not a redirect but a rewrite of the surrounding code;</li>
     *   <li><b>several candidate overloads and none of this call's arity</b> — which one the author meant is
     *       exactly what an arity was going to tell us, so a guess between two is a guess.</li>
     * </ul>
     *
     * <p>Where a single overload of another arity is the only candidate, the arguments this call already
     * passes are kept in order and the difference is made up: a {@link ArgumentEdit.Literal} per input the
     * target gained, and trailing arguments simply dropped for one it lost. That is
     * {@code SignatureMigration}'s own machinery, doing here what it does for a hand-edited signature.
     */
    private static SdkMigrationRunner.Redirect redirectFor(ApiClass then, ApiClass now, Call call,
                                                           Map<String, ApiClass> after, Pairing pairing) {
        Pairing.Member target = pairing.targetOf(then, call.member());
        ApiClass owner = target == null ? now : after.get(target.type());
        String name = target == null ? call.member() : target.name();
        if (owner == null) return null;
        // A constructor is not a method with a funny name: `new Point(…)` and `Point.of(…)` are different
        // source shapes, and one is not rewritten into the other by renaming anything.
        if (CTOR.equals(call.member()) != CTOR.equals(name)) return null;

        boolean moved = !owner.name().equals(now.name());
        String oldReturn = returnTypeOf(then, call);
        Advice advice = adviceFor(then, call, owner, name);

        if (call.isField()) {
            if (!owner.declaresField(name)) return null;
            if (!moved && name.equals(call.member())) return null;      // still there, still spelled the same
            String newReturn = typeOfField(owner, name);
            return new SdkMigrationRunner.Redirect(then.simpleName(), call.member(), call.argCount(),
                    moved ? owner.name() : null, name, List.of(), oldReturn, newReturn,
                    fits(oldReturn, newReturn, after), returnTypeFqn(oldReturn, after),
                    advice.note(), advice.behaviourChanged());
        }

        List<ApiMember> overloads = owner.byName().getOrDefault(name, List.of()).stream()
                .filter(m -> !m.field()).toList();
        if (overloads.isEmpty()) return null;

        ApiMember exact = overloads.stream()
                .filter(m -> m.params().size() == call.argCount()).findFirst().orElse(null);
        if (exact != null && !moved && name.equals(call.member())) return null;   // the call already compiles

        ApiMember chosen = exact;
        if (chosen == null) {
            if (overloads.size() != 1) return null;
            chosen = overloads.getFirst();
        }
        return new SdkMigrationRunner.Redirect(then.simpleName(), call.member(), call.argCount(),
                moved ? owner.name() : null, name, argumentsFor(call.argCount(), chosen),
                oldReturn, chosen.type(), fits(oldReturn, chosen.type(), after),
                returnTypeFqn(oldReturn, after), advice.note(), advice.behaviourChanged());
    }

    /**
     * What the SDK's own author said about this move, from whichever end of the pointer pair carries it.
     *
     * <p>Both ends are asked because only one of them need exist. The <b>forward</b> half is the
     * {@code @ReplacedBy} on the element in the <em>old</em> jar — the one the bot still calls — and it wins,
     * being the author speaking on the member the user actually wrote. The <b>backward</b> half is the
     * {@code @Replaces} claim on the survivor in the <em>new</em> jar, which is the only place the sentence
     * survives once the deprecated element is finally deleted, and so is the answer for a bot that skipped
     * the deprecation release entirely. See {@link Advice}.
     */
    private static Advice adviceFor(ApiClass then, Call call, ApiClass owner, String name) {
        Advice forward = pointerAdvice(overloadOf(then, call));
        String oldSpelling = then.name() + "#" + call.member();
        Advice backward = owner.byName().getOrDefault(name, List.of()).stream()
                .flatMap(m -> m.replaces().stream())
                .filter(c -> c.name().equals(oldSpelling) && c.covers(call.argCount()))
                .findFirst()
                .map(c -> new Advice(c.note(), c.behaviourChanged()))
                .orElse(Advice.NONE);
        return forward.over(backward);
    }

    /** The overload this call reaches, by arity, falling back to any of the name — see {@link #returnTypeOf}. */
    private static ApiMember overloadOf(ApiClass then, Call call) {
        List<ApiMember> named = then.byName().getOrDefault(call.member(), List.of());
        return named.stream()
                .filter(m -> call.isField() ? m.field() : !m.field() && m.params().size() == call.argCount())
                .findFirst()
                .orElseGet(() -> named.stream().findFirst().orElse(null));
    }

    private static Advice pointerAdvice(ApiMember member) {
        if (member == null || member.replacedBy() == null) return Advice.NONE;
        return new Advice(member.replacedBy().note(), member.replacedBy().behaviourChanged());
    }

    /**
     * How the call's arguments become the target's: kept in order for as far as both go, then filled or
     * dropped. Filled with a <em>literal</em> default rather than the palette's, since the parameter's type
     * is whatever the target jar calls it and may be something this file cannot name.
     */
    private static List<ArgumentEdit> argumentsFor(int argCount, ApiMember target) {
        List<ArgumentEdit> edits = new ArrayList<>();
        for (int i = 0; i < target.params().size(); i++) {
            edits.add(i < argCount
                    ? new ArgumentEdit.Keep(i)
                    : new ArgumentEdit.Literal(target.params().get(i)));
        }
        return List.copyOf(edits);
    }

    /** What one field gives back — its own declared type, which is what a read of it is worth. */
    private static String typeOfField(ApiClass klass, String name) {
        return klass.byName().getOrDefault(name, List.of()).stream()
                .filter(ApiMember::field).map(ApiMember::type).findFirst().orElse("");
    }

    /** Every primitive a value of this type may stand in for without a cast. */
    private static final Map<String, Set<String>> WIDENS = Map.of(
            "byte", Set.of("short", "int", "long", "float", "double"),
            "short", Set.of("int", "long", "float", "double"),
            "char", Set.of("int", "long", "float", "double"),
            "int", Set.of("long", "float", "double"),
            "long", Set.of("float", "double"),
            "float", Set.of("double"));

    /**
     * Whether a value of {@code now} may stand where one of {@code was} was expected — the check that makes a
     * redirect in expression position safe rather than hopeful.
     *
     * <p>Four ways it can: the same type; a subtype of it in the target jar's own hierarchy; a widening
     * primitive conversion, which the compiler does silently; or {@code Object}, which takes anything. A
     * {@code void} <em>old</em> type accepts anything because nothing consumed it in the first place, and a
     * {@code void} new one fits nowhere, since there is no value to write.
     *
     * <p>Everything outside that list answers no — including a type the target jar does not declare at all,
     * which is where a {@code java.util.List} or a JDK type ends up. That falls the safe way: the site gets a
     * default and a review row instead of source that may not compile, and the user is told where the member
     * went in the same sentence.
     */
    private static boolean fits(String was, String now, Map<String, ApiClass> after) {
        if (was == null || now == null) return false;
        if (was.equals(now)) return true;
        if ("void".equals(was)) return true;
        if ("void".equals(now)) return false;
        if ("Object".equals(was)) return true;
        if (WIDENS.getOrDefault(now, Set.of()).contains(was)) return true;
        ApiClass target = after.get(now);
        return target != null && target.supertypes().contains(was);
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

    /**
     * That same type spelled fully, <em>as the target jar has it</em> — null when the target has no such
     * class, which includes every primitive, {@code void} and {@code String}.
     *
     * <p>It is asked of the target and not of the old jar on purpose: this is what a cast in the repaired
     * source will name, and naming a class the release just dropped would trade one compile error for
     * another. Where it answers null the repair writes the bare literal, exactly as it did before — and the
     * one case that would leave uncompilable ({@code ImageTemplate t;} against a jar without it) is a
     * {@link BreakKind#TYPE_REMOVED} break, which has already refused the upgrade.
     */
    private static String returnTypeFqn(String returnType, Map<String, ApiClass> after) {
        ApiClass klass = after.get(returnType);
        return klass == null ? null : klass.name();
    }

    // =========================================================================
    // THE TWO JARS
    // =========================================================================

    /**
     * One old spelling a surviving element claims: the name as it used to be written, optionally <em>which</em>
     * overload of it, and the last version it was written that way in. Parsed from one {@code @Replaces} entry,
     * and carrying that annotation's {@code note} and {@code behaviourChanged} with it — those describe the
     * move, and a claim is the only place a move survives once the element it moved from is deleted.
     *
     * <p>{@code arity} is null for an entry that names the member and not a signature, which is the ordinary
     * case: such a claim answers for every overload.
     */
    private record Claim(String name, Integer arity, String version, String note, boolean behaviourChanged) {

        /** Whether this claim speaks for a call of {@code argCount} arguments. */
        boolean covers(int argCount) {
            return arity == null || arity == argCount;
        }
    }

    /**
     * One {@code @ReplacedBy}, read whole: where the element went, when each candidate applies, and what its
     * author said about the move.
     *
     * <p>A null {@code Pointer} means <b>no annotation at all</b>; a present one whose {@link #targets()} are
     * empty is the author saying outright that nothing takes this element's place. The two read alike and are
     * not alike, which is why the distinction is kept — and it is not a defensive branch: {@code {}} is the
     * annotation's declared default, so javac emits no value element for a bare {@code @ReplacedBy} and
     * ClassGraph hands back a null value for a present annotation.
     *
     * <p>{@code targets} is a <b>list</b> because one old member may become two — a <em>split</em>, whose
     * candidates {@link #whens()} distinguishes one sentence each. Reading them all is this phase; offering
     * the user a choice between them is the next one, and until then the first candidate is the answer, which
     * is what "ordered, first preferred" means.
     *
     * <p>All four annotations are {@code @Retention(CLASS)} rather than {@code RUNTIME} for the same reason
     * {@code @Deprecated} is read from bytecode here: they are never reflected on at run time, only read off a
     * jar that is on no classpath, by the ClassGraph scan {@code TypeSummaryManager} already runs.
     */
    private record Pointer(List<String> targets, List<String> whens, String note, boolean behaviourChanged) {

        /**
         * The annotation as read, or null when it is absent. A blank target is dropped rather than kept:
         * {@code {""}} and {@code {}} are the same statement, and the SDK's own gate says so.
         */
        static Pointer of(AnnotationInfo annotation) {
            if (annotation == null) return null;
            return new Pointer(strings(annotation, "value").stream().filter(t -> !t.isBlank()).toList(),
                    strings(annotation, "whens"), text(annotation, "note"),
                    flag(annotation, "behaviourChanged"));
        }

        /** Where this element went, or null when it named nowhere — the answer a single-valued reader wants. */
        String first() {
            return targets.isEmpty() ? null : targets.getFirst();
        }
    }

    /**
     * What the SDK's own author said about a move, assembled from whichever end of the pointer pair carries it.
     *
     * <p>The two ends live in two different jars and only one of them need survive: a bot upgrading
     * <em>through</em> the deprecation release reads {@code @ReplacedBy} on the element it still calls, and one
     * that skipped that release finds the element gone and reads {@code @Replaces} on the survivor. So the
     * forward note <b>wins</b> — it is the author speaking on the element the bot actually names — and the
     * backward one is the fallback for everyone who arrived late. The flag is a logical <b>OR</b>: either end
     * asserting that the behaviour changed is enough to mark every redirected call site.
     */
    private record Advice(String note, boolean behaviourChanged) {

        static final Advice NONE = new Advice("", false);

        /** This one, taken as the forward half, over {@code back} as the backward half. */
        Advice over(Advice back) {
            return new Advice(note.isBlank() ? back.note() : note,
                    behaviourChanged || back.behaviourChanged());
        }

    }

    /**
     * One public API class, reduced to what a compatibility question can be asked of.
     *
     * <p>{@code supertypes} is every class and interface above it, by simple name — the one question the diff
     * asks that a member cannot answer for itself: whether a redirect's new return value may stand where the
     * old one did. It is read from the same scan, so it covers the SDK's own hierarchy and stops at the edge
     * of the jar, which is all a check between two SDK types needs.
     *
     * <p>{@code replacedBy} is the {@code @ReplacedBy} read whole (see {@link Pointer}), null when there is no
     * annotation at all. {@code replaces} is the {@code @Replaces} entries, which point the other way — these
     * older spellings became <em>this</em> type. {@code since} is the release it first appeared in, {@code ""}
     * when it does not say, and {@code scaffolding} is true for a type Studio's own generated files write.
     */
    private record ApiClass(String name, String simpleName, Pointer replacedBy, List<Claim> replaces,
                            String since, boolean scaffolding,
                            boolean deprecated, Set<String> supertypes,
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
     *
     * <p>{@code replacedBy} and {@code replaces} are the two halves of the pointer, read exactly as they are
     * on a class, and {@code since} / {@code scaffolding} likewise. They sit on the <em>overload</em>, which is
     * where the author wrote them — the pairing folds the overloads of one name together, since a call site is
     * attributed by name and arity and the forward pointer carries no arity of its own.
     */
    private record ApiMember(String name, String type, List<String> params, boolean field,
                             Pointer replacedBy, List<Claim> replaces,
                             String since, boolean scaffolding) {
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
                    .add(new ApiMember(name, type, paramsOf(mi), false,
                            Pointer.of(either(mi.getAnnotationInfo(), REPLACED_BY, REPLACED_BY_LEGACY)),
                            claims(either(mi.getAnnotationInfo(), REPLACES, REPLACES_LEGACY)),
                            text(either(mi.getAnnotationInfo(), SINCE), "value"),
                            mi.hasAnnotation(SCAFFOLDING)));
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
                            List.of(), true,
                            Pointer.of(either(fi.getAnnotationInfo(), REPLACED_BY, REPLACED_BY_LEGACY)),
                            claims(either(fi.getAnnotationInfo(), REPLACES, REPLACES_LEGACY)),
                            text(either(fi.getAnnotationInfo(), SINCE), "value"),
                            fi.hasAnnotation(SCAFFOLDING)));
            (fi.hasAnnotation(Deprecated.class.getName()) ? deprecatedNames : liveNames).add(fi.getName());
        }
        deprecatedNames.removeAll(liveNames);
        Set<String> supertypes = new LinkedHashSet<>();
        ci.getSuperclasses().forEach(parent -> supertypes.add(parent.getSimpleName()));
        ci.getInterfaces().forEach(parent -> supertypes.add(parent.getSimpleName()));
        return new ApiClass(ci.getName(), ci.getSimpleName(),
                Pointer.of(either(ci.getAnnotationInfo(), REPLACED_BY, REPLACED_BY_LEGACY)),
                claims(either(ci.getAnnotationInfo(), REPLACES, REPLACES_LEGACY)),
                text(either(ci.getAnnotationInfo(), SINCE), "value"), ci.hasAnnotation(SCAFFOLDING),
                ci.hasAnnotation(Deprecated.class.getName()), Set.copyOf(supertypes),
                Map.copyOf(byName), Set.copyOf(deprecatedNames));
    }

    /**
     * The first of {@code names} present on {@code annotations}, or {@code null}. Exists so one element can
     * be asked for a pointer under either its current or its pre-1.1.0 spelling without either read site
     * having to know there are two.
     */
    private static AnnotationInfo either(AnnotationInfoList annotations, String... names) {
        if (annotations == null) return null;
        for (String name : names) {
            AnnotationInfo found = annotations.get(name);
            if (found != null) return found;
        }
        return null;
    }

    /** One annotation element read as a list of strings — {@code String[]} being how all four spell theirs. */
    private static List<String> strings(AnnotationInfo annotation, String element) {
        if (annotation == null) return List.of();
        Object value = annotation.getParameterValues(true).getValue(element);
        if (value instanceof Object[] array) {
            return Arrays.stream(array).map(e -> String.valueOf(e).trim()).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value).trim());
    }

    /** One annotation element read as a single string, {@code ""} when the author left it at its default. */
    private static String text(AnnotationInfo annotation, String element) {
        if (annotation == null) return "";
        Object value = annotation.getParameterValues(true).getValue(element);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** One annotation element read as a flag, false when the author left it at its default. */
    private static boolean flag(AnnotationInfo annotation, String element) {
        if (annotation == null) return false;
        Object value = annotation.getParameterValues(true).getValue(element);
        return value instanceof Boolean b && b;
    }

    /**
     * The {@code @Replaces} entries, each {@code fqn[#member][(arity)]@<version>} split into its parts, plus
     * the two things the annotation as a whole says about the move.
     *
     * <p>An entry with no {@code @} is dropped rather than guessed at: the version is what says which era it
     * belongs to, and an entry without one could only be applied to every bot or to none. The SDK's own build
     * gate refuses that shape, so this is the reader being closed rather than the writer being distrusted.
     *
     * <p>The arity is optional and is dropped the same way when it is not a number — {@code #click(2)} names
     * <em>which</em> overload this element took over, which is the one thing the forward pointer never has to
     * spell out (it sits on the overload) and this one cannot read off anything, since by the time it is read
     * that overload may be gone.
     */
    private static List<Claim> claims(AnnotationInfo annotation) {
        if (annotation == null) return List.of();
        String note = text(annotation, "note");
        boolean behaviourChanged = flag(annotation, "behaviourChanged");
        List<Claim> out = new ArrayList<>();
        for (String entry : strings(annotation, "value")) {
            int at = entry.lastIndexOf('@');
            if (at <= 0 || at == entry.length() - 1) continue;
            String name = entry.substring(0, at);
            Integer arity = null;
            int open = name.lastIndexOf('(');
            if (open > 0 && name.endsWith(")")) {
                String digits = name.substring(open + 1, name.length() - 1);
                if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
                    arity = Integer.valueOf(digits);
                    name = name.substring(0, open);
                }
            }
            out.add(new Claim(name, arity, entry.substring(at + 1), note, behaviourChanged));
        }
        return List.copyOf(out);
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
     * <p>It is a walk over a tiny graph of <b>old spelling → newer spelling</b>, built once from both jars.
     * A node is a spelling in the grammar the pointers use: {@code fqn} for a type, {@code fqn#member} for a
     * member. Two things put edges in it:
     *
     * <ul>
     *   <li>the <b>old</b> jar's {@code @ReplacedBy}, which is the author of the element the bot actually
     *       calls saying where it went;</li>
     *   <li>the <b>new</b> jar's {@code @Replaces}, read backwards — each entry is an edge from the old
     *       spelling it names to the element carrying it — and <b>filtered by era</b>: an entry is consulted
     *       only for a bot pinned at or below the version the entry records, since a bot already past that
     *       release cannot still be spelling it the old way.</li>
     * </ul>
     *
     * <p>The walk follows edges until it reaches a spelling the target jar actually has, which is what makes
     * a <b>chain</b> resolve: {@code a}→{@code b} announced in 2.0 and {@code b}→{@code c} in 3.0 lands a bot
     * still spelling it {@code a} on {@code c}, with the 2.0 jar never fetched. A visited set bounds it — a
     * rename undone by a later release is a cycle, and a cycle that reaches nothing live is simply unpaired.
     *
     * <p>Three things it deliberately does not do. It does not follow a pointer for a spelling the target
     * <em>still has</em>: the live element wins, which is why an accumulated entry can never go stale into a
     * wrong answer. It does not resolve an ambiguous claim — two survivors claiming one old spelling at one
     * version leave it unpaired, with a line in {@link Report#problems()}. And it never invents a pairing:
     * unpaired is an answer, and a wrongly paired element is a bot that compiles and does something else.
     *
     * <p><b>Members are paired independently of types</b>, and a member pointer may cross types. Two readers
     * ask for different halves of that, deliberately: {@link #memberName} answers "what is this called on the
     * type this one paired with", so nothing but the type pairing decides which type a site writes, while
     * {@link #targetOf} hands back the endpoint whole — for {@link #redirectFor}, which is about to move the
     * receiver as well and is the only caller entitled to see a member that left.
     */
    private record Pairing(Map<String, String> types, Map<String, Member> members) {

        /** Where a member pointer ended up: the simple name of the owning type, and the member's own name. */
        record Member(String type, String name) {}

        static Pairing of(Map<String, ApiClass> before, Map<String, ApiClass> after, String botVersion,
                          List<String> problems, boolean throughDeprecations) {
            Map<String, String> edges = forwardEdges(before);
            backwardEdges(after, botVersion, problems).forEach(edges::putIfAbsent);
            // Modernising walks one hop further than an upgrade does, so it needs the pointers the *target*
            // jar's own deprecated elements carry. They are the same shape of edge; only the stopping rule
            // below differs, which is the whole of what "also move off deprecated members" means.
            if (throughDeprecations) forwardEdges(after).forEach(edges::putIfAbsent);

            Map<String, String> types = new LinkedHashMap<>();
            Map<String, Member> members = new LinkedHashMap<>();
            for (ApiClass then : before.values()) {
                String typeEnd = follow(then.name(), edges, after, throughDeprecations);
                ApiClass target = typeEnd.contains("#") ? null : resolveType(typeEnd, after);
                if (target != null) {
                    types.put(then.simpleName(), target.simpleName());
                } else if (after.containsKey(then.simpleName())) {
                    // The name survives even if the package moved under it. Nothing pointed anywhere, so the
                    // same spelling is the answer — this is the short-circuit an upgrade takes almost always.
                    types.put(then.simpleName(), then.simpleName());
                }
                for (String member : then.byName().keySet()) {
                    String key = then.name() + "#" + member;
                    String end = follow(key, edges, after, throughDeprecations);
                    if (end.equals(key)) continue;                  // nothing pointed anywhere
                    ApiClass owner = resolveType(typePart(end), after);
                    if (owner != null && owner.byName().containsKey(memberPart(end))) {
                        members.put(then.simpleName() + "#" + member,
                                new Member(owner.simpleName(), memberPart(end)));
                    }
                }
            }
            return new Pairing(Map.copyOf(types), Map.copyOf(members));
        }

        /**
         * What the old jar's own elements say about where they went. No target = nothing took my place.
         *
         * <p>A pointer may now name <b>several</b> candidates, and this graph takes the <b>first</b>: the
         * targets are declared in preference order, so the first is the answer for every reader that wants
         * one answer, which is all of them until the per-call-site choice arrives. Nothing here is lost —
         * the candidates and their {@code whens} are on the {@link Pointer} either way.
         */
        private static Map<String, String> forwardEdges(Map<String, ApiClass> before) {
            Map<String, String> edges = new LinkedHashMap<>();
            for (ApiClass then : before.values()) {
                if (then.replacedBy() != null && then.replacedBy().first() != null) {
                    edges.put(then.name(), then.replacedBy().first());
                }
                then.byName().forEach((member, overloads) -> overloads.stream()
                        .map(m -> m.replacedBy() == null ? null : m.replacedBy().first())
                        .filter(Objects::nonNull)
                        // Overloads share a name and a call site is attributed by name, so the first pointer
                        // any of them carries answers for all of them. Two overloads pointing different ways
                        // is a distinction this cannot act on either way.
                        .findFirst()
                        .ifPresent(target -> edges.putIfAbsent(then.name() + "#" + member, target)));
            }
            return edges;
        }

        /**
         * What the target jar's survivors claim, read as edges pointing forward in time. Entries are grouped
         * by the old spelling they name; only those from the bot's own era or later can apply to it, and of
         * those the <b>earliest</b> is the next hop — a later one describes a rename this bot has not reached.
         */
        private static Map<String, String> backwardEdges(Map<String, ApiClass> after, String botVersion,
                                                         List<String> problems) {
            Map<String, Map<String, Set<String>>> claims = new LinkedHashMap<>();
            for (ApiClass now : after.values()) {
                for (Claim claim : now.replaces()) claim(claims, claim, now.name());
                now.byName().forEach((member, overloads) -> {
                    for (ApiMember overload : overloads) {
                        for (Claim claim : overload.replaces()) {
                            claim(claims, claim, now.name() + "#" + member);
                        }
                    }
                });
            }

            Map<String, String> edges = new LinkedHashMap<>();
            claims.forEach((oldSpelling, byVersion) -> byVersion.entrySet().stream()
                    .filter(e -> appliesTo(botVersion, e.getKey()))
                    .min(Map.Entry.comparingByKey(SdkUpgradeService::compareVersions))
                    .ifPresent(e -> {
                        if (e.getValue().size() > 1) {
                            problems.add("\"" + oldSpelling + "\" is claimed by more than one element of the "
                                    + "target SDK (" + String.join(", ", e.getValue()) + "), so there is no "
                                    + "one answer to what it became. Uses of it are left for you to change.");
                            return;
                        }
                        edges.put(oldSpelling, e.getValue().iterator().next());
                    }));
            return edges;
        }

        private static void claim(Map<String, Map<String, Set<String>>> claims, Claim claim, String claimant) {
            claims.computeIfAbsent(claim.name(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(strip(claim.version()), k -> new LinkedHashSet<>())
                    .add(claimant);
        }

        /**
         * Whether an entry recorded as last existing in {@code entryVersion} can still describe a bot pinned
         * at {@code botVersion}. When either is not a version {@link SemVer} understands —
         * {@code 0.0.0-SNAPSHOT}, most often — the entry is consulted rather than dropped: a pointer for a
         * rename the bot has already had applied costs nothing, since the old name appears nowhere in it.
         */
        private static boolean appliesTo(String botVersion, String entryVersion) {
            String bot = strip(botVersion);
            if (!SemVer.isValid(bot) || !SemVer.isValid(entryVersion)) return true;
            return SemVer.compare(bot, entryVersion) <= 0;
        }

        /**
         * Walks the edges until the spelling is one the target jar has, or until there is nowhere to go.
         *
         * <p>{@code throughDeprecations} moves the finish line by one condition: a spelling the target does
         * have, but has marked {@code @Deprecated} <em>and</em> pointed somewhere, is walked past rather
         * than accepted. That is the only difference between an upgrade and a modernisation — the same
         * graph, read one hop further — and it is why a bot can move off a deprecated member with no version
         * change at all. A deprecated element with no pointer stops the walk like any other: there is
         * nothing to say about it.
         */
        private static String follow(String start, Map<String, String> edges, Map<String, ApiClass> after,
                                     boolean throughDeprecations) {
            Set<String> seen = new LinkedHashSet<>();
            String at = start;
            while (seen.add(at) && (!exists(at, after) || (throughDeprecations && edges.containsKey(at)
                    && isDeprecated(at, after)))) {
                String next = edges.get(at);
                if (next == null) return at;
                at = next;
            }
            return at;
        }

        /** Whether the target jar marks this exact spelling {@code @Deprecated}. False for one it lacks. */
        private static boolean isDeprecated(String spelling, Map<String, ApiClass> after) {
            ApiClass owner = resolveType(typePart(spelling), after);
            if (owner == null) return false;
            return spelling.contains("#")
                    ? owner.deprecatedNames().contains(memberPart(spelling))
                    : owner.deprecated();
        }

        /** Whether the target jar declares this exact spelling — the same fully-qualified type, at that. */
        private static boolean exists(String spelling, Map<String, ApiClass> after) {
            ApiClass owner = resolveType(typePart(spelling), after);
            if (owner == null) return false;
            return !spelling.contains("#") || owner.byName().containsKey(memberPart(spelling));
        }

        /**
         * The target's class of that fully-qualified name, or null. The FQN is compared, not just the simple
         * name: a pointer that names a package the target does not have is a pointer to nothing, and pairing
         * it with a same-named class elsewhere would be the invented answer this refuses to give.
         */
        private static ApiClass resolveType(String fqn, Map<String, ApiClass> after) {
            ApiClass candidate = after.get(lastSegment(fqn));
            return candidate != null && candidate.name().equals(fqn) ? candidate : null;
        }

        /** The old type's counterpart in {@code after}, or null when nothing takes its place. */
        ApiClass pairedTo(ApiClass then, Map<String, ApiClass> after) {
            String name = types.get(then.simpleName());
            return name == null ? null : after.get(name);
        }

        /**
         * What {@code member} of {@code then} is called in the target — itself, unless a pointer says
         * otherwise <em>and</em> lands on the type this one paired with.
         *
         * <p>Which type the site now writes is the type pairing's answer, and having two sources for it is
         * how they come to disagree. A member sent somewhere else therefore reads as unpaired here.
         */
        String memberName(ApiClass then, ApiClass now, String member) {
            Member paired = members.get(then.simpleName() + "#" + member);
            return paired != null && paired.type().equals(now.simpleName()) ? paired.name() : member;
        }

        /**
         * The endpoint itself, type and all — null when no pointer led anywhere the target jar has.
         *
         * <p>{@link #memberName} answers the narrower question and keeps the type pairing as the single
         * source of which type a site writes; this one is for the caller that is <em>about to move the
         * receiver too</em>, which is the only thing entitled to see an endpoint on another type.
         */
        Member targetOf(ApiClass then, String member) {
            return members.get(then.simpleName() + "#" + member);
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
    }

    /**
     * One place the bot writes an SDK type's name without calling it — {@code ImageTemplate t;}, a parameter,
     * a {@code List<ImageTemplate>}, a cast.
     *
     * <p>It carries no member because there is none: this is the bot depending on a type <em>existing</em>.
     * That is why it is here at all — a removed type is the one break with no repair, and a report built only
     * from calls said nothing about a bot that merely holds one.
     */
    private record TypeUse(String type, CallSite site) {}

    /** Everything one pass over the bot's sources found, which is what every reader downstream needs. */
    private record Uses(List<Call> calls, List<TypeUse> types) {}

    private Uses usesIn(Set<String> sdkTypes, Map<String, List<String>> fieldOwners, List<String> problems) {
        List<Call> calls = new ArrayList<>();
        List<TypeUse> types = new ArrayList<>();
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
            for (SdkReferences.TypeUse use : SdkReferences.typeUses(file, cu, sdkTypes)) {
                types.add(new TypeUse(use.type(),
                        new CallSite(path, cu.getLineNumber(use.site().node().getStartPosition()))));
            }
        }
        return new Uses(List.copyOf(calls), List.copyOf(types));
    }

    private String relativePath(Path path) {
        Path root = config.projectPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.getFileName().toString();
    }

    // =========================================================================
    // THE DIFF
    // =========================================================================

    /**
     * New classes, and new members on classes that already existed — display text, sorted, and <b>grouped by
     * the release each arrived in</b>.
     *
     * <p>The era comes from {@code @Since}, asked of the element itself and falling back to its declaring
     * class (a whole new class carries one annotation, not one per member). Everything that answers nothing
     * lands in the {@code ""} bucket, which is sorted last and rendered without a heading — a jar predating
     * {@code @Since} therefore produces exactly the flat alphabetical list this used to return.
     */
    private static Map<String, List<String>> additions(Map<String, ApiClass> before,
                                                       Map<String, ApiClass> after) {
        Map<String, Set<String>> byEra = new LinkedHashMap<>();
        for (ApiClass now : after.values()) {
            ApiClass then = before.get(now.simpleName());
            if (then == null) {
                byEra.computeIfAbsent(now.since(), k -> new TreeSet<>())
                        .add(now.simpleName() + " (new class)");
                continue;
            }
            for (Map.Entry<String, List<ApiMember>> entry : now.byName().entrySet()) {
                String name = entry.getKey();
                if (then.byName().containsKey(name)) continue;
                // A constant is read as a value, so it is shown as one: Key.ENTER, not Key.ENTER(…).
                String call = now.declaresCallable(name) ? "(…)" : "";
                String era = entry.getValue().stream().map(ApiMember::since)
                        .filter(s -> !s.isBlank()).findFirst().orElse(now.since());
                byEra.computeIfAbsent(era, k -> new TreeSet<>())
                        .add(CTOR.equals(name)
                                ? "new " + now.simpleName() + "(…)"
                                : now.simpleName() + "." + name + call);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byEra.entrySet().stream()
                // Newest first, and the era nobody declared last: it is the answer "we do not know", which
                // belongs after every answer we do have.
                .sorted((a, b) -> a.getKey().isBlank() ? 1 : b.getKey().isBlank() ? -1
                        : compareVersions(strip(b.getKey()), strip(a.getKey())))
                .forEach(e -> out.put(e.getKey(), List.copyOf(e.getValue())));
        // Not Map.copyOf: that is a hash map, and the sort immediately above is the whole point.
        return Collections.unmodifiableMap(out);
    }

    /**
     * What this release does to the members Studio writes into a bot's <em>generated</em> files, said up front.
     *
     * <p>Those files are never migrated — they are rendered from Studio's own templates, so rewriting one
     * would be overwritten at the next regeneration and regenerating it would reproduce the same old-SDK code.
     * When a repair touches one, {@code SdkMigrationRunner} refuses the whole upgrade, and until now it did so
     * <b>mid-apply</b>: the user read a report, pressed the button and only then learned the answer was no.
     * This is the same fact, stated before they commit to anything.
     *
     * <p>It is read from the <b>old</b> jar, which is the one whose spelling the generated files currently
     * use, and it says nothing about whether the case is repairable — that is a question for the phases that
     * make regeneration verify-then-emit. Its job is to be honest and to arrive early.
     */
    private static List<String> scaffolding(Map<String, ApiClass> before, List<Deprecation> deprecated,
                                            List<Break> breaks) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Break b : breaks) {
            if (isScaffolding(before, b.type(), b.member())) out.put(b.display(), b.display());
        }
        for (Deprecation d : deprecated) {
            if (isScaffolding(before, d.type(), d.member())) out.put(d.display(), d.display());
        }
        return List.copyOf(out.values());
    }

    /** Whether the old jar marks this element {@code @Scaffolding} — the type itself, or any overload of it. */
    private static boolean isScaffolding(Map<String, ApiClass> before, String type, String member) {
        ApiClass klass = before.get(type);
        if (klass == null) return false;
        if (member == null || member.isEmpty()) return klass.scaffolding();
        return klass.scaffolding()
                || klass.byName().getOrDefault(member, List.of()).stream().anyMatch(ApiMember::scaffolding);
    }

    /**
     * Members this bot calls that the target marks {@code @Deprecated}, each with what the SDK itself says
     * to use instead.
     *
     * <p>The replacement is read the same way every other answer here is — through {@link #redirectFor},
     * against the same jar — so a row that promises a move is a row the repair pass will actually make.
     * It is empty in two cases that read alike and are not alike: the member points nowhere, or it points
     * somewhere the shapes refuse. Both leave the user to it, which is what a deprecation is for.
     */
    private static List<Deprecation> deprecations(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                                  List<Call> calls, Pairing pairing) {
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();
        Map<String, String[]> moves = new LinkedHashMap<>();
        for (Call call : calls) {
            ApiClass then = before.get(call.type());
            // Through the pairing, so a renamed-but-deprecated type is still reported: the bot writes the old
            // name, and looking that up in the new jar would find nothing and say nothing.
            ApiClass now = then == null ? after.get(call.type()) : pairing.pairedTo(then, after);
            if (now == null) continue;
            String member = then == null ? call.member() : pairing.memberName(then, now, call.member());
            // Both ends of the pairing are asked, and the origin has to be, because modernising follows the
            // pointer *past* the deprecated element: what the bot writes is the deprecated half, and what it
            // is paired with is precisely the half that is not. Asking only the destination would report
            // nothing at all in the one case this list exists for.
            ApiClass origin = after.get(call.type());
            boolean deprecated = now.deprecated() || now.deprecatedNames().contains(member)
                    || (origin != null
                    && (origin.deprecated() || origin.deprecatedNames().contains(call.member())));
            if (!deprecated) continue;
            String key = call.type() + "#" + call.member();
            sites.computeIfAbsent(key, k -> new ArrayList<>()).add(call.site());
            if (then != null) moves.computeIfAbsent(key, k -> moveText(then, now, call, after, pairing));
        }
        return sites.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split("#", 2);
                    String[] move = moves.getOrDefault(e.getKey(), new String[]{"", ""});
                    return new Deprecation(parts[0], parts[1], move[0], move[1], sorted(e.getValue()));
                })
                .sorted(Comparator.comparing(Deprecation::display))
                .toList();
    }

    /**
     * Where a deprecated call would go and what that would cost, as {@code {becomes, repair}} — both empty
     * when the answer is "nowhere".
     *
     * <p>A deprecated <em>type</em> that was replaced has no redirect of its own: nothing about the call
     * changes but the name it is reached through, and that is the file-wide rename's job. It is still an
     * answer the user wants to read, so it is written here in the type sweep's own words.
     */
    private static String[] moveText(ApiClass then, ApiClass now, Call call,
                                     Map<String, ApiClass> after, Pairing pairing) {
        SdkMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);
        if (redirect != null) return new String[]{redirect.display(), repairText(redirect)};
        if (!now.simpleName().equals(then.simpleName())) {
            return new String[]{now.simpleName(),
                    "every use of \"" + then.simpleName() + "\" becomes \"" + now.simpleName() + "\""};
        }
        return new String[]{"", ""};
    }

    /**
     * Calls that would stop compiling, and what Studio will write in their place. Only members the
     * <em>old</em> jar actually had are judged: a call to something neither jar declares is the bot's own code
     * (or an unindexed library), not a break this upgrade causes.
     *
     * <p>A renamed type yields <b>two</b> findings where both apply — one {@link BreakKind#TYPE_RENAMED} for
     * the type, and, for a member that also went, its own finding under the new type. That is the pairing
     * rule made visible: a pointer pairs the element it is written on, and the members of a paired type are
     * still resolved one at a time.
     *
     * <p>A member the target still offers <em>somewhere</em> is listed too, with the redirect as its repair.
     * It is a break — the bot does not compile until the call moves — and one Studio makes itself, which is
     * exactly what the {@code repair} sentence is for.
     */
    private static List<Break> breaks(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                      Uses uses, Pairing pairing) {
        Map<String, Break> found = new LinkedHashMap<>();
        Map<String, List<CallSite>> sites = new LinkedHashMap<>();

        // The type verdict first, and from the places the bot writes the type *name* — which a call scan
        // never sees. Without this a bot whose only contact with a removed class is `ImageTemplate t;` got no
        // finding at all and was upgraded into something that does not compile.
        for (TypeUse use : uses.types()) {
            ApiClass then = before.get(use.type());
            if (then != null) typeVerdict(found, sites, then, after, pairing, use.site());
        }

        for (Call call : uses.calls()) {
            ApiClass then = before.get(call.type());
            // In the shape the bot uses it: a name the old jar had only as a method is not evidence that
            // this file's `Foo.NAME` was ever SDK, and vice versa.
            if (then == null || !declares(then, call)) continue;

            if (typeVerdict(found, sites, then, after, pairing, call.site())) continue;
            ApiClass now = pairing.pairedTo(then, after);

            // A call that still resolves under the same spelling is no break at all — deprecated or not,
            // pointed somewhere or not, it compiles, and a deprecation is not a break. One that resolves
            // only somewhere else is a break with a redirect for a repair, and is still listed, because the
            // bot does not compile until the redirect is made.
            if (offers(now, call.member(), call.argCount())) continue;
            SdkMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);

            BreakKind kind;
            String detail = "";
            if (redirect != null
                    && (!call.member().equals(redirect.toMember()) || redirect.toTypeFqn() != null)) {
                // The old spelling is gone; the redirect says where it went, which is what the user needs
                // to read even though nothing here has to be changed by hand.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
                detail = "now " + redirect.display();
            } else if (declares(now, call.isField(), call.member())) {
                kind = BreakKind.SIGNATURE_CHANGED;
                detail = "was " + signatures(then, call.member()) + " — now "
                        + signatures(now, call.member());
            } else {
                // Covers a field turned into a method (and the reverse) as well as an outright removal —
                // every one of them stops this call site compiling.
                kind = call.isField() ? BreakKind.FIELD_REMOVED : BreakKind.MEMBER_REMOVED;
            }
            record(found, sites, new Break(call.type(), call.member(), kind, detail,
                    redirect == null ? repairText(returnTypeOf(then, call)) : repairText(redirect),
                    List.of()), call.site());
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

    /**
     * Records what became of {@code then} as a type, at one site — true when it is <b>gone</b>, which is the
     * caller's cue that there is nothing further to say about that site.
     *
     * <p>One place builds the two type findings because two loops now reach them: a call written on the type,
     * and a place the type is written on its own. Both are the same fact about the same class, so both file
     * into the same finding and the sites simply accumulate.
     */
    private static boolean typeVerdict(Map<String, Break> found, Map<String, List<CallSite>> sites,
                                       ApiClass then, Map<String, ApiClass> after, Pairing pairing,
                                       CallSite site) {
        ApiClass now = pairing.pairedTo(then, after);
        if (now == null) {
            record(found, sites, new Break(then.simpleName(), "", BreakKind.TYPE_REMOVED, "",
                    "nothing — this one has to be changed by hand", List.of()), site);
            return true;
        }
        // A type paired elsewhere while its own name survives in the target is not a break — the bot goes on
        // compiling. That is a modernisation (a deprecated class pointed at its successor), and it belongs on
        // the deprecation list, where the rename is offered rather than announced.
        if (!now.simpleName().equals(then.simpleName()) && !after.containsKey(then.simpleName())) {
            record(found, sites, new Break(then.simpleName(), "", BreakKind.TYPE_RENAMED,
                    "now " + now.simpleName(),
                    "every use of \"" + then.simpleName() + "\" becomes \"" + now.simpleName() + "\"",
                    List.of()), site);
        }
        return false;
    }

    private static void record(Map<String, Break> found, Map<String, List<CallSite>> sites,
                               Break unsited, CallSite site) {
        String key = unsited.type() + "#" + unsited.member() + "#" + unsited.kind();
        found.putIfAbsent(key, unsited);
        sites.computeIfAbsent(key, k -> new ArrayList<>()).add(site);
    }

    /**
     * What the user is told a redirected call becomes.
     *
     * <p>Three sentences, because there are three outcomes and the difference matters to whoever reads the
     * dialog: a plain rename is complete and says so; a redirect that changed shape is complete but wants
     * looking at; and one that could not be used everywhere says <em>where</em> it could not, since that is
     * the half the user has to finish.
     *
     * <p><b>The author's own sentence, where there is one, is appended verbatim and never rewritten.</b>
     * Everything above it is Studio restating what the diff already showed; the note is the one thing in the
     * dialog that says <em>why</em>, and it is the only text here Studio did not write.
     */
    private static String repairText(SdkMigrationRunner.Redirect redirect) {
        String repair = mechanicsOf(redirect);
        return redirect.note().isBlank() ? repair : repair + " — " + redirect.note();
    }

    /** The half of {@link #repairText(SdkMigrationRunner.Redirect)} that is Studio's own reading of the jars. */
    private static String mechanicsOf(SdkMigrationRunner.Redirect redirect) {
        String was = CTOR.equals(redirect.member())
                ? "new " + redirect.type() : redirect.type() + "." + redirect.member();
        List<String> what = new ArrayList<>();
        if (!redirect.display().equals(was)) what.add("becomes " + redirect.display());

        long gained = redirect.arguments().stream().filter(ArgumentEdit.Literal.class::isInstance).count();
        long dropped = Math.max(0, redirect.argCount())
                - redirect.arguments().stream().filter(ArgumentEdit.Keep.class::isInstance).count();
        if (gained > 0) what.add("gains " + inputs(gained) + ", filled in with a default value");
        if (dropped > 0) what.add("loses " + inputs(dropped) + " this call passes");
        // Nothing about the call changes but the type it is reached through, which the file-wide type
        // rename is already doing — so there is nothing more to promise here.
        if (what.isEmpty()) what.add("is carried across as it is");

        String head = String.join(" and ", what);
        if (!redirect.expressionSafe()) {
            return head + " where it stands on its own, and becomes " + defaultTextOf(redirect.returnType())
                    + " where its result is used — those functions are marked for your review";
        }
        return redirect.needsReview() ? head + ", and the function is marked for your review" : head;
    }

    private static String inputs(long count) {
        return count + (count == 1 ? " input" : " inputs");
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
    // THE POINTER GRAMMAR
    // =========================================================================

    /** {@code com.botmaker.sdk.api.Key#ENTER} → {@code ENTER}; a name with no {@code #} is its own answer. */
    private static String memberPart(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(hash + 1);
    }

    /** The other half: {@code …Key#ENTER} → {@code …Key}, and a bare type name is its own answer. */
    private static String typePart(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
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

    /** Release tags are cut as {@code v1.0.26}; {@code SemVer} wants {@code 1.0.26}. */
    private static String strip(String version) {
        if (version == null) return "";
        String t = version.trim();
        return (t.startsWith("v") || t.startsWith("V")) ? t.substring(1) : t;
    }
}
