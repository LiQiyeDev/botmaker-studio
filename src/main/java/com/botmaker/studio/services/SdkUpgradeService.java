package com.botmaker.studio.services;

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
import com.botmaker.studio.services.SdkApiModel.ApiClass;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.botmaker.studio.services.SdkApiModel.CTOR;
import static com.botmaker.studio.services.SdkApiModel.declares;
import static com.botmaker.studio.services.SdkApiModel.offers;
import static com.botmaker.studio.services.SdkRedirects.fittingAt;
import static com.botmaker.studio.services.SdkRedirects.redirectFor;
import static com.botmaker.studio.services.SdkRedirects.redirectsFor;
import static com.botmaker.studio.services.SdkRedirects.returnTypeFqn;
import static com.botmaker.studio.services.SdkRedirects.returnTypeOf;

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
 * <h2>Where the work lives</h2>
 *
 * <p>This class is the service: the public {@linkplain Report report} it hands back, the entry points that
 * build one, and the scan of the bot's own sources — the only part that needs the project. Everything it
 * asks of the two jars lives beside it, package-private, in five files that no caller outside this package
 * ever names:
 *
 * <ul>
 *   <li>{@link SdkApiModel} — the two jars reduced to what the questions need, and the pointer grammar;</li>
 *   <li>{@link SdkPairing} — the edges, and the walk that follows them to something the target jar has;</li>
 *   <li>{@link SdkRedirects} — the one place a redirect is decided, and the checks it has to pass;</li>
 *   <li>{@link SdkUpgradeDiff} — the lists a report carries, and the sentences shown beside them;</li>
 *   <li>{@link SdkWhatsNew} — the one thing not derived from the bytecode: the release's own changelog.</li>
 * </ul>
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

    /**
     * One place in the bot's own source, as the user would find it: project-relative path and 1-based line.
     *
     * <p>{@code text} is the call as the user wrote it, elided if long. A line number cannot tell
     * {@code scroll(3)} from {@code scroll(-3)}, and those are exactly the two calls a
     * {@linkplain Choice split} asks the user to answer differently.
     *
     * <p>{@code offset} is not for display. It is the <b>key</b> a per-site decision travels under: the
     * report pass and the apply pass parse the sources twice, so the AST node the dialog was built from is
     * not the node the rewriter holds, and node identity would silently pair nothing. Nothing edits the
     * files between the two passes, so a character offset is stable — and a key that misses simply falls
     * back to that site's own default, which is the correct degradation.
     */
    public record CallSite(String file, int line, String text, int offset) {

        /** The call as written, cut to something a dialog row can hold. */
        static String elide(String source) {
            String one = source.replaceAll("\\s+", " ").trim();
            return one.length() <= 40 ? one : one.substring(0, 39) + "…";
        }

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
     * One place a redirect could go, and the sentence the SDK's author wrote to distinguish it from the
     * others. {@code when} is blank for a candidate read only off the back edge, where the survivor knows
     * what it replaced but not why one call meant it rather than the other.
     */
    public record Candidate(SdkMigrationRunner.Redirect redirect, String when) {

        /** How the user reads it in the menu: {@code Mouse.scrollUp — when notches is positive}. */
        public String display() {
            return when.isBlank() ? redirect.display() : redirect.display() + " — " + when;
        }
    }

    /**
     * A member that became <b>two</b>, and the question that puts to each call of it.
     *
     * <p>Which candidate a call meant is a property of <em>that call</em>, not of the member — the sign of
     * the argument, the thing the surrounding code does with the result — so this is the one thing in the
     * whole upgrade that is a decision rather than a fact, and the only place the user is asked one. It is
     * surfaced beside {@link Report#breaks()} and {@link Report#deprecated()} rather than inside them: a
     * split is not a new <em>verdict</em> about the member (it is still deprecated, or still gone), so
     * folding it in would change what {@link Report#canMigrate()} and {@link Report#canModernise()} mean.
     *
     * <p>Nothing is required of the user: every site arrives answered with the author's preferred candidate,
     * so a dialog closed without a click migrates exactly as it would have before splits existed.
     */
    public record Choice(String type, String member, int argCount, List<Candidate> candidates,
                         String note, List<Site> sites) {

        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }
    }

    /**
     * One call of a split member, with the candidates that fit <em>there</em>.
     *
     * <p>The list is filtered per site because {@code expressionSafe} is a property of the position, not of
     * the candidate: a call standing as a statement discards its result, so every candidate fits, while one
     * whose value is used admits only those whose return type still sits where the old one did. The
     * <b>first</b> is the preselection. An <b>empty</b> list is not a new outcome — it is today's default
     * value plus {@code @NeedsReview}, and the dialog says so rather than offering an empty menu.
     */
    public record Site(CallSite site, List<Candidate> candidates) {}

    /**
     * One release's own account of itself: a section of the target jar's {@code CHANGELOG.md}.
     *
     * <p>{@code date} is whatever followed the version in the heading and may be blank — a section is
     * identified by its version, never by its date. {@code lines} is the section body with the emphasis
     * markers removed and nothing else touched, so the author's wording reaches the user verbatim.
     *
     * @see SdkWhatsNew
     */
    public record Highlight(String version, String date, List<String> lines) {}

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * <p>{@code problems} is what the scan could <em>not</em> determine — an unresolvable jar, a file that
     * does not parse, an old spelling two survivors both claim. It is separate from the findings on purpose:
     * an empty {@code breaks} list means something quite different depending on whether this one is empty too.
     *
     * <p>{@code highlights} is the only list here the two jars did not produce: it is the target release's
     * own {@code CHANGELOG.md} sections for the span being crossed, newest first, read out of the target jar
     * itself. Every other list states a <em>cost</em>; this one is the only thing that can state a reason,
     * which is why the dialog leads with it. Empty for a jar that carries no changelog — see
     * {@link SdkWhatsNew}.
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
     *
     * <p>{@code splits} are the members that became two — see {@link Choice}. They sit beside the two verdict
     * lists rather than inside them, deliberately: a split member is already reported there as deprecated or
     * as a break, and this is the question that goes with it, not a third kind of finding.
     */
    public record Report(String from, String to,
                         List<Highlight> highlights,
                         Map<String, List<String>> addedBySince,
                         List<Deprecation> deprecated,
                         List<Break> breaks,
                         List<Choice> splits,
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
            return new Report(from, to, List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(problem));
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
        Map<String, ApiClass> before = SdkApiModel.snapshot(oldJar);
        Map<String, ApiClass> after = SdkApiModel.snapshot(newJar);
        if (before.isEmpty() || after.isEmpty()) {
            return Report.unavailable(from, to,
                    "One of the two SDK jars scanned to no public API at all, which means the comparison "
                            + "would be meaningless rather than empty.");
        }

        List<String> problems = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Uses uses = usesIn(known, SdkApiModel.fieldOwners(before, after), problems);
        // Writes into problems too — an old spelling two survivors both claim is a question this cannot
        // answer, and it is recorded before the list is frozen.
        SdkPairing pairing = SdkPairing.of(before, after, from, problems, throughDeprecations);

        List<Deprecation> deprecated = SdkUpgradeDiff.deprecations(before, after, uses.calls(), pairing);
        List<Break> breaks = SdkUpgradeDiff.breaks(before, after, uses, pairing);
        return new Report(from, to,
                // Read from the target jar, not diffed out of the two: a release's reason for existing is
                // not a property of its API surface. A span of (from, from] — which is what modernising
                // passes — is empty by construction, and correctly so: nothing is being moved to.
                SdkWhatsNew.between(newJar, from, to),
                SdkUpgradeDiff.additions(before, after),
                deprecated,
                breaks,
                SdkUpgradeDiff.splits(before, after, uses, pairing),
                SdkUpgradeDiff.scaffolding(before, deprecated, breaks),
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
        return apply(targetVersion, repairSources, alsoModernise, Map.of());
    }

    /**
     * The same, carrying the per-site answers a {@link Choice} asked for: the report's own {@link CallSite}
     * mapped to the index the user picked in <em>that site's</em> candidate list. An empty map — every
     * headless caller, and a dialog the user simply accepted — takes the preferred candidate everywhere.
     *
     * <p>The indices are all that crosses the dialog boundary. What each one <em>means</em> is worked out
     * again from the two jars at the moment of writing, for the same reason the rest of the repair is: a
     * value that crossed an FX thread is not evidence about the files on disk right now.
     */
    public CompletableFuture<Void> apply(String targetVersion, boolean repairSources, boolean alsoModernise,
                                         Map<CallSite, Integer> picks) {
        return CompletableFuture
                .runAsync(() -> {
                    snapshot("Before SDK upgrade to " + targetVersion);
                    if (repairSources || alsoModernise) {
                        migrateSources(targetVersion, alsoModernise, true, picks);
                    }
                })
                .thenCompose(v -> libraryService.updateLibraries(libraryService.currentLibraries(),
                        targetVersion))
                .thenRun(this::regenerateScaffolding);
    }

    /**
     * Produces the generated files again, now that the pom names the new SDK.
     *
     * <p>This is the other half of the mid-apply refusal narrowing (see
     * {@code SdkMigrationRunner.scaffoldingInTheWay}). The migrator deliberately never rewrites a generated
     * file; it does not have to, because these files are derived from the activity model and can simply be
     * produced again — against the new jar. After the pom has moved, not before: the render has to see the
     * SDK the project actually pins now.
     *
     * <p><b>Since 2026-08-25 it re-renders only {@code Templates.java}</b>, and the four files that mattered
     * most here — {@code Activities}, {@code Parameters}, {@code ActivityRegistry}, {@code FlowDriver} — are
     * left exactly as the upgrade found them. Studio has no generator: the scaffold templates left the SDK
     * and its own emitters do not exist yet (inversion phase 2). So an upgrade repairs the user's own source
     * and moves the pom, and a bot whose generated files name something the new SDK renamed does not compile
     * until phase 2 lands. That is why {@code SdkMigrationRunner.scaffoldingInTheWay} went back to refusing
     * such an upgrade outright rather than letting it through on the promise of a re-render.
     *
     * <p>An {@code IOException} here is worth a sentence and not worth failing the upgrade over: the sources
     * are already repaired and the pom is already moved, so undoing it would be the destructive answer, and
     * the snapshot taken before the upgrade is the way back.
     */
    private void regenerateScaffolding() {
        try {
            ImageTemplateLibrary.regenerateTemplatesClass(config);
        } catch (RuntimeException e) {
            System.err.println("SDK upgrade: the generated files could not be re-rendered against the new "
                    + "SDK (" + e.getMessage() + "). Open Project ▸ Activity Flow and save to redo them.");
        }
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
            migrateSources(currentVersion(), true, false, Map.of());
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
    private void migrateSources(String targetVersion, boolean throughDeprecations, boolean allowDefaults,
                                Map<CallSite, Integer> picks) {
        String from = currentVersion();
        Optional<Path> oldJar = MavenService.resolveSdkJar(config.projectPath(), from);
        Optional<Path> newJar = MavenService.resolveSdkJar(config.projectPath(), targetVersion);
        if (oldJar.isEmpty() || newJar.isEmpty()) {
            throw new IllegalStateException("The SDK jars could not be resolved again, so the upgrade stopped "
                    + "before changing anything. Check the report and try once more.");
        }

        SdkMigrationRunner.Outcome outcome = migrate(oldJar.get(), newJar.get(), from, targetVersion,
                throughDeprecations, allowDefaults, picks);
        if (outcome == null) return;                        // nothing needed repairing
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
     * The rewrite worked out, given the two jars — everything except resolving them and writing the result.
     * Null means nothing needed repairing, which is not an error: most upgrades break nothing.
     *
     * <p>Split out for the same reason {@link #compare(Path, Path, String, String)} is: the cases worth
     * testing are the ones no published pair of versions exhibits, and they have to be compiled on the spot.
     * It is also the only seam through which a per-site {@linkplain Choice choice} can be exercised without
     * a pom, a VCS repository and a network round trip standing between the test and the answer.
     */
    SdkMigrationRunner.Outcome migrate(Path oldJar, Path newJar, String from, String targetVersion,
                                       boolean throughDeprecations, boolean allowDefaults,
                                       Map<CallSite, Integer> picks) {
        List<String> problems = new ArrayList<>();
        Map<String, ApiClass> before = SdkApiModel.snapshot(oldJar);
        Map<String, ApiClass> after = SdkApiModel.snapshot(newJar);
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Map<String, List<String>> fieldOwners = SdkApiModel.fieldOwners(before, after);
        SdkPairing pairing = SdkPairing.of(before, after, from, problems, throughDeprecations);

        Uses uses = usesIn(known, fieldOwners, problems);
        // The same all-or-nothing rule the report states: anything the scan could not answer — a file that
        // does not parse, an old name two survivors both claim — stops the rewrite before it writes.
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        List<Break> breaks = SdkUpgradeDiff.breaks(before, after, uses, pairing);
        Break refused = breaks.stream().filter(b -> !b.isRepairable()).findFirst().orElse(null);
        if (refused != null) {
            throw new IllegalStateException("\"" + refused.type() + "\" is gone from SDK " + targetVersion
                    + " and nothing in that release takes its place, so there is no value to stand in for it "
                    + "where this bot writes the type itself. Change these by hand, then upgrade: "
                    + String.join(", ", refused.sites().stream().map(CallSite::toString).toList())
                    + ". Nothing has been changed.");
        }

        SdkMigrationRunner.Repairs repairs = repairsFor(before, after, uses, pairing, allowDefaults);
        if (repairs.isEmpty()) return null;

        List<ProjectFile> editable = new ArrayList<>();
        List<ProjectFile> generated = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            // FileRole is the single source of truth for "may the user change this?", and the migration
            // answers to the same rule the editor does — see SdkMigrationRunner on why a scaffold file is
            // refused rather than rewritten.
            (FileRole.of(config, state.getTemplate(), file.getPath()) == FileRole.EDITABLE ? editable : generated)
                    .add(file);
        }

        return SdkMigrationRunner.run(repairs, choicesFor(before, after, uses, pairing, picks),
                editable, generated, known, fieldOwners, config.mainPackage(), null, state);
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
                                                         Uses uses, SdkPairing pairing,
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
            if (then == null || !declares(then, call.isField(), call.member())) continue;

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

    /**
     * Turns the dialog's per-site picks into the redirects the runner will apply — see {@link Choice}.
     *
     * <p>An index is looked up in the same list {@link SdkUpgradeDiff#splits} built for that site, worked out
     * here from the jars rather than carried over from the report. Anything that does not line up — a site
     * nobody was asked about, a member that no longer splits, an index past the end — is simply left out, and
     * the site takes the preferred candidate that {@link #repairsFor} already recorded. A key that misses is
     * the correct degradation, not a failure: it produces the upgrade the user would have got by not choosing.
     */
    private static SdkMigrationRunner.Choices choicesFor(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after, Uses uses,
                                                         SdkPairing pairing, Map<CallSite, Integer> picks) {
        if (picks.isEmpty()) return SdkMigrationRunner.Choices.NONE;

        Map<SdkMigrationRunner.SiteKey, SdkMigrationRunner.Redirect> out = new LinkedHashMap<>();
        for (Call call : uses.calls()) {
            Integer pick = picks.get(call.site());
            if (pick == null || pick == 0) continue;        // 0 is the default the repairs already carry
            ApiClass then = before.get(call.type());
            if (then == null || !declares(then, call.isField(), call.member())) continue;
            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) continue;

            List<Candidate> fitting = fittingAt(redirectsFor(then, now, call, after, pairing), call);
            if (pick < 0 || pick >= fitting.size()) continue;
            out.put(call.key(), fitting.get(pick).redirect());
        }
        return new SdkMigrationRunner.Choices(out);
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
    record Call(String type, String member, int argCount, CallSite site, Path file,
                boolean statement) {
        boolean isField() {
            return argCount == SdkReferences.FIELD_READ;
        }

        /** The key a per-site decision is looked up under — see {@link CallSite#offset()}. */
        SdkMigrationRunner.SiteKey key() {
            return new SdkMigrationRunner.SiteKey(file.toString(), site.offset());
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
    record TypeUse(String type, CallSite site) {}

    /** Everything one pass over the bot's sources found, which is what every reader downstream needs. */
    record Uses(List<Call> calls, List<TypeUse> types) {}

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
                int offset = reference.site().node().getStartPosition();
                calls.add(new Call(reference.type(), reference.member(), reference.argCount(),
                        new CallSite(path, cu.getLineNumber(offset),
                                CallSite.elide(reference.site().node().toString()), offset),
                        file.getPath(), reference.site().isStatement()));
            }
            for (SdkReferences.TypeUse use : SdkReferences.typeUses(file, cu, sdkTypes)) {
                int offset = use.site().node().getStartPosition();
                types.add(new TypeUse(use.type(), new CallSite(path, cu.getLineNumber(offset),
                        CallSite.elide(use.site().node().toString()), offset)));
            }
        }
        return new Uses(List.copyOf(calls), List.copyOf(types));
    }

    private String relativePath(Path path) {
        Path root = config.projectPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.getFileName().toString();
    }
}
