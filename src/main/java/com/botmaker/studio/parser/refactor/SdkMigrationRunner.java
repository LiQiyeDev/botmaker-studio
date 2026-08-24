package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.parser.refactor.SignatureMigration.CallChange;
import com.botmaker.studio.parser.refactor.SignatureMigration.ReturnFate;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes the bot compile against the new SDK — the half of the upgrade that writes.
 *
 * <p>{@code services/SdkUpgradeService} diffs the two jars, pairs the types, and hands over three lists of
 * facts. This turns them into edits, through the same {@link CallMigrator} a signature change goes through,
 * and hands back the new source of every file — or a sentence saying why it will not.
 *
 * <h2>Redirect where it is checked, default where it is not</h2>
 *
 * <p>A member the target still offers somewhere — under a new name, on another type, through a different
 * argument list — is {@linkplain Redirect redirected} to it. Nothing here decides that: the redirect is
 * declared by the SDK at both ends ({@code @ReplacedBy} / {@code @Replaces}) and <em>checked</em> against the
 * target jar by {@code services/SdkUpgradeService} before it arrives, which is what separates it from the
 * guess this used to refuse. Two positions, two answers:
 *
 * <ul>
 *   <li>a call standing as a <b>statement</b> is always redirected — nothing consumes its value, so the
 *       target's return type cannot make it wrong. This is the case a pure default repair <em>deletes</em>,
 *       throwing away work the bot did;</li>
 *   <li>a call whose value is <b>used</b> is redirected only when the new value still fits where the old one
 *       sat ({@link Redirect#expressionSafe()}).</li>
 * </ul>
 *
 * <p>Everything else — no pointer, a pointer to nothing, an ambiguous target — gets a <b>default value of the
 * type the old jar said it returned</b> ({@code false}, {@code 0}, {@code ""}, {@code null}), and a call
 * standing as a statement of its own is <b>deleted</b> rather than defaulted, since {@code 0;} is not a
 * statement any compiler accepts.
 *
 * <p>That leaves the bot compiling and, in places, wrong — deliberately. So the other half of the bargain is
 * written in the same rewrite: every function that got a default, lost a call, or had one redirected into a
 * different shape is annotated {@code @NeedsReview} ({@link ReviewMarks}), naming what happened to it. The
 * mark lands with the repair or not at all — a migration refused halfway leaves neither.
 *
 * <p><b>A rename is not marked.</b> {@code ImageClicker.click} becoming {@code IClicker.click} is a complete
 * repair: the bot does afterwards exactly what it did before, so asking the user to look at it would bury the
 * sites that genuinely changed meaning under the ones that did not. That is what
 * {@link Redirect#shapeChanged()} draws the line at.
 *
 * <h2>One pass over the facts, in two sweeps per file</h2>
 *
 * <p>There is no replay. A chain of renames across several releases is <em>composed</em> into one answer
 * before anything gets here, so what arrives is already "what this bot must end up saying".
 *
 * <p>Each file is nevertheless rewritten twice, and the ordering is load-bearing rather than incidental:
 * <b>members first, types second</b>, with a re-parse between. A removed member of a renamed type would
 * otherwise be two edits landing on one node — {@code ASTRewrite} replacing the whole {@code ImageClicker.gone()}
 * while also retargeting the {@code ImageClicker} inside it — which is not a rewrite it can express. Doing the
 * member sweep against the source the user actually has, then renaming what is left, needs no such
 * reconciliation: the sites that became defaults no longer name the type at all.
 *
 * <h2>Nothing, or all of it</h2>
 *
 * <p>Every refusal — a file that stops parsing, a {@code case} label whose enum cannot be told, a {@code void}
 * call in a position with no statement to delete, a rewrite that produces text no compiler accepts — abandons
 * the <em>whole</em> migration with nothing written anywhere. The alternative is a project in neither shape,
 * with nothing telling the user which half was touched. Disk is only reached afterwards, by
 * {@code CallMigrator.commit}.
 *
 * <h2>Studio's own scaffolding is not rewritten</h2>
 *
 * <p>The generated entry point, {@code FlowDriver}, {@code ActivityRegistry}, {@code Activities} and
 * {@code Templates} are renderings of things the user has on screen, written by <em>this Studio</em> — so
 * rewriting them would be overwritten at the next regeneration, and regenerating them would reproduce the same
 * old-SDK code, since the templates that produce them live in the Studio build, not in the SDK. When a repair
 * would have touched a scaffold file, the migration is refused and says so: that upgrade needs a newer Studio,
 * not a cleverer rewrite.
 */
public final class SdkMigrationRunner {

    private SdkMigrationRunner() {}

    /**
     * A type that changed name — paired by the two-ended pointer the SDK declares, and applied file-wide.
     *
     * <p>Fully-qualified on both sides, so a package move is the same edit as a rename and the imports move
     * with it.
     */
    public record TypeRename(String fromFqn, String toFqn) {

        /** The name the bot's source actually writes. */
        public String from() {
            return simpleNameOf(fromFqn);
        }
    }

    /**
     * A member the target still offers somewhere, and the call that reaches it: the same member under a new
     * name, on another type, or through a different argument list.
     *
     * <p>The type is the one the <b>bot</b> writes. {@code toTypeFqn} is null when the receiver is not to be
     * touched — the member stayed on this type, or the type is being renamed file-wide by a
     * {@link TypeRename} and having two edits move one receiver is how they come to disagree. A non-null one
     * is a genuine move, and is rewritten and imported at the call site.
     *
     * <p>{@code expressionSafe} is the whole reason a redirect may be taken at all. In <b>statement</b>
     * position the value is discarded, so the target's return type cannot matter and the call is always
     * redirected — that is the case that is <em>deleted</em> under a pure default repair, losing work the bot
     * did. In <b>expression</b> position something consumes the value, so the redirect is taken only when
     * {@code SdkUpgradeService} has checked against the target jar that what comes back still fits where the
     * old value sat; otherwise the site falls back to a literal default of {@code returnType}, exactly as a
     * {@link Removal} does.
     *
     * <p>{@code returnTypeFqn} is {@code returnType} spelled fully in the target jar, for the same reason
     * {@link Removal} carries one: the fallback below is a literal default, and a default in a position with
     * no type of its own has to say what it is.
     *
     * <p>{@link #shapeChanged()} is derived rather than passed: a redirect that keeps every argument where it
     * was and gives back the same type is a <b>rename</b>, does afterwards exactly what it did before, and is
     * not marked for review. Anything that gains, loses or retypes something is.
     *
     * <p>{@code note} and {@code behaviourChanged} are the two things the <b>SDK's own author</b> said about
     * this move, read off the pointer pair and passed through untouched. They exist because
     * {@link #shapeChanged()} cannot see the one gap the model admits by construction: a redirect that keeps
     * every argument and the return type — a click that now targets the match's centre, a wait that counts
     * from a different instant — is a same-shape redirect, so it lands silently and the bot quietly does
     * something else. Nothing in the bytecode reveals it, so the author says it, and
     * {@link #needsReview()} is what {@link #shapeChanged()} alone used to answer.
     */
    public record Redirect(String type, String member, int argCount,
                           String toTypeFqn, String toMember, List<ArgumentEdit> arguments,
                           String returnType, String toReturnType, boolean expressionSafe,
                           String returnTypeFqn, String note, boolean behaviourChanged) {

        /** The same, with nothing the SDK's author had to add about the move. */
        public Redirect(String type, String member, int argCount,
                        String toTypeFqn, String toMember, List<ArgumentEdit> arguments,
                        String returnType, String toReturnType, boolean expressionSafe,
                        String returnTypeFqn) {
            this(type, member, argCount, toTypeFqn, toMember, arguments,
                    returnType, toReturnType, expressionSafe, returnTypeFqn, "", false);
        }

        /** The same, where the fallback default has nothing to cast to — see {@link Removal#Removal}. */
        public Redirect(String type, String member, int argCount,
                        String toTypeFqn, String toMember, List<ArgumentEdit> arguments,
                        String returnType, String toReturnType, boolean expressionSafe) {
            this(type, member, argCount, toTypeFqn, toMember, arguments,
                    returnType, toReturnType, expressionSafe, null);
        }

        /**
         * Whether the call sites this redirect touches are marked for the user's review.
         *
         * <p>Two quite different reasons, deliberately one question: the shape moved (Studio can see that),
         * or the author said the behaviour did (only they can). A rename is still not marked — the bot does
         * afterwards exactly what it did before, and burying the sites that changed meaning under the ones
         * that did not is how a review list stops being read.
         */
        public boolean needsReview() {
            return shapeChanged() || behaviourChanged;
        }

        boolean matches(SdkReferences.Reference reference) {
            return reference.type().equals(type)
                    && reference.member().equals(member)
                    && reference.argCount() == argCount;
        }

        /** How the user reads where it went: {@code IClicker.tap}, or {@code new Point} for a constructor. */
        public String display() {
            String owner = toTypeFqn == null ? type : simpleNameOf(toTypeFqn);
            return SdkReferences.CTOR.equals(toMember) ? "new " + owner : owner + "." + toMember;
        }

        /** True when nothing about the call changes but its name and place — see the class Javadoc. */
        public boolean shapeChanged() {
            return !returnType.equals(toReturnType) || !isPlainKeep();
        }

        /** Whether every argument stays exactly where it is: no fill, no drop, no reorder. */
        private boolean isPlainKeep() {
            if (arguments.size() != Math.max(0, argCount)) return false;
            for (int i = 0; i < arguments.size(); i++) {
                if (!(arguments.get(i) instanceof ArgumentEdit.Keep keep) || keep.from() != i) return false;
            }
            return true;
        }

        CallChange changeAt(MethodReferences.CallSite site) {
            return toTypeFqn == null
                    ? new CallChange.Rewrite(site, toMember, arguments)
                    : new CallChange.Retargeted(site, toTypeFqn, toMember, arguments);
        }
    }

    /**
     * A member the target no longer offers in the shape this bot uses it — so, at every such call site, a
     * default value or a deleted statement.
     *
     * <p>{@code argCount} is {@link SdkReferences#FIELD_READ} for a constant and the exact argument count
     * otherwise: overloads are matched by arity, so removing {@code click(int)} must not touch {@code click()}.
     * {@code returnType} is the <b>old</b> jar's answer, because that is the type the code around the call
     * site was written for. {@code returnTypeFqn} is the same type spelled fully in the <b>target</b> jar, or
     * null when that release has no such type — the cast a default needs where the site gives it no type of
     * its own ({@link CallMigrator#literalDefaultFor}).
     */
    public record Removal(String type, String member, int argCount, String returnType, String returnTypeFqn) {

        /** A removal with nothing to cast to — a primitive, a {@code void}, or a type the target also lost. */
        public Removal(String type, String member, int argCount, String returnType) {
            this(type, member, argCount, returnType, null);
        }

        boolean matches(SdkReferences.Reference reference) {
            return reference.type().equals(type)
                    && reference.member().equals(member)
                    && reference.argCount() == argCount;
        }

        boolean isVoid() {
            return "void".equals(returnType);
        }
    }

    /** Everything the upgrade has to write, worked out from the two jars before a character is changed. */
    public record Repairs(List<TypeRename> types, List<Redirect> redirects, List<Removal> removals) {

        public boolean isEmpty() {
            return types.isEmpty() && redirects.isEmpty() && removals.isEmpty();
        }
    }

    /**
     * One call in the bot's own source, identified by <b>position</b> rather than by node.
     *
     * <p>The report pass and this one parse the sources twice, so the AST node the dialog was built from is
     * not the node held here and node identity would silently pair nothing. Nothing edits the files between
     * the two passes, so a character offset is stable — and a key that misses is not a failure: the site
     * simply takes the default, which is what every headless caller gets anyway.
     *
     * @param file   the file's path as {@code ProjectFile.getPath().toString()} gives it, on both sides
     * @param offset the call expression's start offset in that file
     */
    public record SiteKey(String file, int offset) {}

    /**
     * Which candidate the user picked, per call site — the only thing in the upgrade that is a decision
     * rather than a fact.
     *
     * <p>It exists because a <b>split</b> is a property of the call, not of the member: {@code scroll(3)} and
     * {@code scroll(-3)} in one bot want different answers, so no project-wide pick can be right in both.
     * Every site absent from the map takes {@link Repairs}' own redirect, which is the author's first
     * candidate — so {@link #NONE} reproduces the behaviour of every caller that never asks: Modernise, the
     * tests, and any headless path.
     */
    public record Choices(Map<SiteKey, Redirect> bySite) {

        /** Ask nobody: every site takes the preferred candidate. */
        public static final Choices NONE = new Choices(Map.of());

        public Choices(Map<SiteKey, Redirect> bySite) {
            this.bySite = Map.copyOf(bySite);
        }

        /** The redirect chosen at this reference, or null when the user was never asked about it. */
        Redirect at(ProjectFile file, SdkReferences.Reference reference) {
            return bySite.get(new SiteKey(file.getPath().toString(),
                    reference.site().node().getStartPosition()));
        }
    }

    /**
     * What the run produced: the files to write, or the reason nothing will be. Never both — a refusal carries
     * an empty file list precisely so a caller that ignores {@link #isRefusal()} writes nothing rather than
     * half of it.
     */
    public record Outcome(List<CallMigrator.Rewritten> files, String refusal) {

        public boolean isRefusal() {
            return refusal != null;
        }

        static Outcome refused(String why) {
            return new Outcome(List.of(), why);
        }
    }

    /**
     * Carries {@code repairs} out over the project.
     *
     * @param editable    the files that may be rewritten ({@code FileRole.EDITABLE})
     * @param generated   the files that may not, scanned only so a repair that would have touched one is caught
     * @param sdkTypes    every SDK class simple name, for {@link SdkReferences}
     * @param fieldOwners constant name → declaring SDK types, likewise
     * @param markerPackage the bot's own package, holding the generated {@code NeedsReview} — null to skip
     *                      marking altogether, which only a test that is asserting about the code wants
     */
    public static Outcome run(Repairs repairs, List<ProjectFile> editable, List<ProjectFile> generated,
                              Set<String> sdkTypes, Map<String, List<String>> fieldOwners,
                              String markerPackage, ProjectAnalyzer analyzer, ProjectState state) {
        return run(repairs, Choices.NONE, editable, generated, sdkTypes, fieldOwners, markerPackage,
                analyzer, state);
    }

    /** As above, with the per-site decisions a split asked the user for. See {@link Choices}. */
    public static Outcome run(Repairs repairs, Choices choices, List<ProjectFile> editable,
                              List<ProjectFile> generated, Set<String> sdkTypes,
                              Map<String, List<String>> fieldOwners, String markerPackage,
                              ProjectAnalyzer analyzer, ProjectState state) {
        String blocked = scaffoldingInTheWay(generated, repairs, sdkTypes, fieldOwners);
        if (blocked != null) return Outcome.refused(blocked);

        List<CallMigrator.Rewritten> changed = new ArrayList<>();
        for (ProjectFile file : editable) {
            String original = file.getContent();
            if (original == null) continue;

            Applied members = rewriteMembers(file, original, repairs, choices, sdkTypes, fieldOwners,
                    markerPackage, analyzer, state);
            if (members.refusal() != null) return Outcome.refused(members.refusal());
            String afterMembers = members.text() == null ? original : members.text();

            Applied types = rewriteTypes(file, afterMembers, repairs, analyzer, state);
            if (types.refusal() != null) return Outcome.refused(types.refusal());
            String finalText = types.text() == null ? afterMembers : types.text();

            if (!finalText.equals(original)) changed.add(new CallMigrator.Rewritten(file, finalText));
        }
        return new Outcome(List.copyOf(changed), null);
    }

    // --- one file, one sweep --------------------------------------------------------------------------------

    /** The new text of a file after one sweep — {@code text} null when the sweep left it alone. */
    private record Applied(String text, String refusal) {

        static Applied unchanged() {
            return new Applied(null, null);
        }

        static Applied refused(String why) {
            return new Applied(null, why);
        }
    }

    /**
     * Sweep one: the members. Renames what kept its role, and stands a default value in — or deletes the
     * statement — for what did not.
     */
    private static Applied rewriteMembers(ProjectFile file, String text, Repairs repairs, Choices choices,
                                          Set<String> sdkTypes, Map<String, List<String>> fieldOwners,
                                          String markerPackage, ProjectAnalyzer analyzer, ProjectState state) {
        CompilationUnit unit = SourceParser.parse(text);
        if (unit == null || SourceParser.hasSyntaxErrors(unit)) {
            return Applied.refused("\"" + file.getClassName() + "\" does not parse, so it could not be "
                    + "migrated. Fix that file first — nothing has been changed.");
        }
        SdkReferences.Scan scan = SdkReferences.in(file, unit, file.getClassName(), sdkTypes, fieldOwners);
        if (!scan.problems().isEmpty()) return Applied.refused(scan.problems().getFirst());

        List<CallChange> changes = new ArrayList<>();
        // Insertion-ordered per function, and a set: two identical calls in one function are one thing to look
        // at, and the review list should say so once.
        Map<MethodDeclaration, Set<String>> marks = new LinkedHashMap<>();
        for (SdkReferences.Reference reference : scan.references()) {
            Removal removal = repairs.removals().stream().filter(r -> r.matches(reference))
                    .findFirst().orElse(null);
            if (removal != null) {
                // A call made for its effect has no value to replace: `Mouse.click();` would become `0;`,
                // and for a void member there is no value at all. Either way the statement goes.
                if (reference.site().isStatement()) {
                    changes.add(new CallChange.CallDeleted(reference.site()));
                    note(marks, reference, removal.type(), removal.member(), "the call was removed");
                } else if (removal.isVoid()) {
                    return Applied.refused("SDK removed " + removal.type() + "." + removal.member()
                            + ", which \"" + file.getClassName() + "\" uses somewhere that is not a line of "
                            + "its own — most often the body of a one-line lambda. There is nothing to put "
                            + "in its place, so nothing has been changed.");
                } else {
                    changes.add(new CallChange.ValueDefaulted(reference.site(), removal.returnType(),
                            removal.returnTypeFqn()));
                    note(marks, reference, removal.type(), removal.member(), "the value it produced is now "
                            + CallMigrator.literalDefaultText(removal.returnType(), removal.returnTypeFqn(),
                            reference.site().node()));
                }
                continue;
            }
            // What the user chose here, if they were asked; otherwise the author's preferred candidate. The
            // chosen one is re-checked against this reference so a key that landed on the wrong call — which
            // nothing edits the files to allow, but which no assertion here could rule out — degrades to the
            // default rather than rewriting a site into something it never offered.
            Redirect chosen = choices.at(file, reference);
            Redirect redirect = chosen != null && chosen.matches(reference)
                    ? chosen
                    : repairs.redirects().stream().filter(r -> r.matches(reference))
                            .findFirst().orElse(null);
            if (redirect == null) continue;
            // The position decides. A statement discards the value, so nothing the target gives back can be
            // wrong there; anywhere else the fit had to be checked against the target jar, and a redirect
            // that did not pass falls back to the same default a removal gets.
            if (reference.site().isStatement() || redirect.expressionSafe()) {
                changes.add(redirect.changeAt(reference.site()));
                if (redirect.needsReview()) noteRedirect(marks, reference, redirect);
            } else {
                changes.add(new CallChange.ValueDefaulted(reference.site(), redirect.returnType(),
                        redirect.returnTypeFqn()));
                note(marks, reference, redirect.type(), redirect.member(),
                        "it is now " + redirect.display() + ", whose result does not fit where this uses it, "
                                + "and the value it produced is now "
                                + CallMigrator.literalDefaultText(redirect.returnType(),
                                redirect.returnTypeFqn(), reference.site().node()));
            }
        }
        if (changes.isEmpty()) return Applied.unchanged();

        EditContext ctx = EditContext.of(unit, analyzer, state);
        SignatureMigration.Plan plan =
                new SignatureMigration.Plan(changes, List.of(), List.of(), ReturnFate.UNCHANGED);
        if (!CallMigrator.applyIn(ctx, plan)) {
            return Applied.refused("The upgrade moves something \"" + file.getClassName() + "\" uses in a way "
                    + "that cannot be repaired from the source alone — most often a constant used as a case "
                    + "label, whose type the source never names. Nothing has been changed.");
        }
        if (markerPackage != null) {
            marks.forEach((method, entries) ->
                    ReviewMarks.mark(ctx, method, markerPackage, List.copyOf(entries)));
        }
        return finish(ctx, file, text);
    }

    /**
     * Remembers that {@code reference}'s enclosing function needs looking at, and why.
     *
     * <p>The entry names the member and what stands in its place, and deliberately carries <b>no line
     * number</b>: it is written into the source and outlives every later edit, so a number in it would be
     * wrong by the second time the user opened the file. The function is the unit the review walks anyway.
     *
     * <p>A reference outside any function — a field initializer at class level — is silently unmarked rather
     * than refused. It is still repaired; there is simply nowhere to hang a {@code @Target(METHOD)} annotation.
     */
    private static void note(Map<MethodDeclaration, Set<String>> marks, SdkReferences.Reference reference,
                             String type, String member, String what) {
        MethodDeclaration method = ReviewMarks.enclosingMethod(reference.site().node());
        if (method == null) return;
        marks.computeIfAbsent(method, m -> new LinkedHashSet<>())
                .add(type + "." + member + " is gone from this BotMaker version — "
                        + what + ", so this may no longer do what it did.");
    }

    /**
     * The same, for a call that <em>was</em> redirected — so the sentence says where it went and what did not
     * survive the move, rather than that something is gone.
     *
     * <p>Only ever called for a redirect that {@linkplain Redirect#needsReview() needs review}. A redirect
     * that keeps every argument, gives back the same type and carries no behaviour warning leaves the call
     * doing exactly what it did, and a review list that lists those buries the sites that genuinely changed
     * under the ones that did not.
     *
     * <p>The author's own sentence, where there is one, is written <b>first and verbatim</b>: it is the one
     * channel through which the person who made the change speaks to the person whose bot it lands on, and
     * paraphrasing it here would be Studio talking over them. Everything after it is Studio's own reading of
     * the shape, which may be empty — a same-shape move flagged {@code behaviourChanged} has nothing else to
     * say, and the note is then the whole entry.
     */
    private static void noteRedirect(Map<MethodDeclaration, Set<String>> marks,
                                     SdkReferences.Reference reference, Redirect redirect) {
        MethodDeclaration method = ReviewMarks.enclosingMethod(reference.site().node());
        if (method == null) return;

        List<String> what = new ArrayList<>();
        for (ArgumentEdit edit : redirect.arguments()) {
            if (edit instanceof ArgumentEdit.Literal literal) {
                what.add("it gained an input, filled in with "
                        + CallMigrator.literalDefaultText(literal.typeName()) + " — check that is the value "
                        + "you want");
            }
        }
        // Whatever the new argument list has no place for. The text of each dropped argument would be
        // better, but this runs over the reference and not the rewrite: what the site loses is decided by
        // the shape of the list, and naming the count is honest where naming the wrong expression is not.
        int dropped = Math.max(0, reference.argCount()) - (int) redirect.arguments().stream()
                .filter(ArgumentEdit.Keep.class::isInstance).count();
        if (dropped > 0) {
            what.add("it no longer has a place for " + dropped + (dropped == 1 ? " input" : " inputs")
                    + " this call passed, so whatever " + (dropped == 1 ? "that did" : "those did")
                    + " no longer runs");
        }
        if (!redirect.returnType().equals(redirect.toReturnType())) {
            what.add("it gives back " + describe(redirect.toReturnType()) + " instead of "
                    + describe(redirect.returnType()));
        }
        String head = redirect.type() + "." + redirect.member() + " is now " + redirect.display()
                + " in this BotMaker version";
        if (!redirect.note().isBlank()) {
            marks.computeIfAbsent(method, m -> new LinkedHashSet<>())
                    .add(head + " — " + redirect.note()
                            + (what.isEmpty() ? "" : " Also: " + String.join(", and ", what) + "."));
            return;
        }
        if (what.isEmpty()) return;
        marks.computeIfAbsent(method, m -> new LinkedHashSet<>())
                .add(head + " — " + String.join(", and ", what) + ".");
    }

    private static String describe(String returnType) {
        return "void".equals(returnType) ? "nothing" : returnType;
    }

    /** Sweep two: the types, file-wide, over whatever sweep one produced. */
    private static Applied rewriteTypes(ProjectFile file, String text, Repairs repairs,
                                        ProjectAnalyzer analyzer, ProjectState state) {
        CompilationUnit unit = SourceParser.parse(text);
        if (unit == null || SourceParser.hasSyntaxErrors(unit)) {
            return Applied.refused("Repairing \"" + file.getClassName() + "\" produced source that does not "
                    + "parse, so nothing has been changed.");
        }
        List<TypeRename> here = repairs.types().stream()
                .filter(rename -> SdkReferences.mentions(unit, rename.from()))
                .toList();
        if (here.isEmpty()) return Applied.unchanged();

        EditContext ctx = EditContext.of(unit, analyzer, state);
        for (TypeRename rename : here) CallMigrator.renameTypeIn(ctx, rename.fromFqn(), rename.toFqn());
        return finish(ctx, file, text);
    }

    /** Applies one sweep's rewrite and insists the result still parses. */
    private static Applied finish(EditContext ctx, ProjectFile file, String text) {
        String rewritten;
        try {
            rewritten = ctx.applyTo(text);
        } catch (RuntimeException e) {
            rewritten = null;
        }
        if (rewritten == null || SourceParser.hasSyntaxErrors(SourceParser.parse(rewritten))) {
            return Applied.refused("Repairing \"" + file.getClassName() + "\" for the new SDK produced source "
                    + "that does not compile, so nothing has been changed.");
        }
        return rewritten.equals(text) ? Applied.unchanged() : new Applied(rewritten, null);
    }

    /**
     * The reason a scaffold file blocks this migration, or null when none does. See the class Javadoc for why
     * a generated file is neither rewritten nor regenerated: the code in it is written by Studio's own
     * templates, so only a newer Studio can make it speak the new SDK.
     */
    private static String scaffoldingInTheWay(List<ProjectFile> generated, Repairs repairs,
                                              Set<String> sdkTypes, Map<String, List<String>> fieldOwners) {
        for (ProjectFile file : generated) {
            String text = file.getContent();
            if (text == null) continue;
            CompilationUnit unit = SourceParser.parse(text);
            if (unit == null || SourceParser.hasSyntaxErrors(unit)) continue;

            for (TypeRename rename : repairs.types()) {
                if (SdkReferences.mentions(unit, rename.from())) {
                    return blocked(file, rename.from(), "");
                }
            }
            List<SdkReferences.Reference> references =
                    SdkReferences.in(file, unit, file.getClassName(), sdkTypes, fieldOwners).references();
            for (SdkReferences.Reference reference : references) {
                if (repairs.removals().stream().anyMatch(r -> r.matches(reference))
                        || repairs.redirects().stream().anyMatch(r -> r.matches(reference))) {
                    return blocked(file, reference.type(), reference.member());
                }
            }
        }
        return null;
    }

    private static String blocked(ProjectFile file, String type, String member) {
        return "The new SDK changes " + type + (member.isBlank() ? "" : "." + member)
                + ", which BotMaker's own generated file \"" + file.getClassName() + "\" uses. Generated "
                + "files are never rewritten by an upgrade — update Studio itself so it generates code for "
                + "this SDK. Nothing has been changed.";
    }

    private static String simpleNameOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }
}
