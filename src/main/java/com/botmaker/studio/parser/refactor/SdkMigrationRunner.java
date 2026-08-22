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
 * <h2>Compile, then review — the repair does not guess</h2>
 *
 * <p>A member the target no longer offers is <b>not</b> pointed at another member. Two members need not share
 * a return type, an arity or any semantics, so a redirection is a guess whose failure mode is a bot that
 * compiles and behaves differently — the one outcome worse than a compile error. Instead the call site gets a
 * <b>default value of the type the old jar said it returned</b> ({@code false}, {@code 0}, {@code ""},
 * {@code null}), and a call standing as a statement of its own is <b>deleted</b> rather than defaulted, since
 * {@code 0;} is not a statement any compiler accepts.
 *
 * <p>That leaves the bot compiling and, in places, wrong — deliberately. So the other half of the bargain is
 * written in the same rewrite: every function that got a default or lost a call is annotated
 * {@code @NeedsReview} ({@link ReviewMarks}), naming what happened to it. The mark lands with the repair or
 * not at all — a migration refused halfway leaves neither.
 *
 * <p><b>A rename is not marked.</b> {@code ImageClicker.click} becoming {@code IClicker.click} is a complete
 * repair: the bot does afterwards exactly what it did before, so asking the user to look at it would bury the
 * sites that genuinely changed meaning under the ones that did not.
 *
 * <h2>One pass over the facts, in two sweeps per file</h2>
 *
 * <p>There is no replay. The version keys in {@code migrations.json} are <em>composed</em> into a single
 * rename map before anything gets here, so what arrives is already "what this bot must end up saying".
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
     * A type that changed name — paired by {@code @ApiId} or by a declared rename, and applied file-wide.
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
     * A member that kept its role under a new name, on a type identified by the name the <b>bot</b> writes.
     *
     * <p>{@code @ApiId} deliberately does not answer this: an id pairs the type name only, so a member rename
     * is the one thing {@code migrations.json} is still for.
     */
    public record MemberRename(String type, String from, String to) {}

    /**
     * A member the target no longer offers in the shape this bot uses it — so, at every such call site, a
     * default value or a deleted statement.
     *
     * <p>{@code argCount} is {@link SdkReferences#FIELD_READ} for a constant and the exact argument count
     * otherwise: overloads are matched by arity, so removing {@code click(int)} must not touch {@code click()}.
     * {@code returnType} is the <b>old</b> jar's answer, because that is the type the code around the call
     * site was written for.
     */
    public record Removal(String type, String member, int argCount, String returnType) {

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
    public record Repairs(List<TypeRename> types, List<MemberRename> members, List<Removal> removals) {

        public boolean isEmpty() {
            return types.isEmpty() && members.isEmpty() && removals.isEmpty();
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
        String blocked = scaffoldingInTheWay(generated, repairs, sdkTypes, fieldOwners);
        if (blocked != null) return Outcome.refused(blocked);

        List<CallMigrator.Rewritten> changed = new ArrayList<>();
        for (ProjectFile file : editable) {
            String original = file.getContent();
            if (original == null) continue;

            Applied members = rewriteMembers(file, original, repairs, sdkTypes, fieldOwners,
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
    private static Applied rewriteMembers(ProjectFile file, String text, Repairs repairs,
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
                    note(marks, reference, removal, "the call was removed");
                } else if (removal.isVoid()) {
                    return Applied.refused("SDK removed " + removal.type() + "." + removal.member()
                            + ", which \"" + file.getClassName() + "\" uses somewhere that is not a line of "
                            + "its own — most often the body of a one-line lambda. There is nothing to put "
                            + "in its place, so nothing has been changed.");
                } else {
                    changes.add(new CallChange.ValueDefaulted(reference.site(), removal.returnType()));
                    note(marks, reference, removal, "the value it produced is now "
                            + CallMigrator.literalDefaultText(removal.returnType()));
                }
                continue;
            }
            repairs.members().stream()
                    .filter(m -> m.type().equals(reference.type()) && m.from().equals(reference.member()))
                    .findFirst()
                    .ifPresent(rename -> changes.add(new CallChange.Rewrite(reference.site(), rename.to(),
                            keepAll(Math.max(0, reference.argCount())))));
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
                             Removal removal, String what) {
        MethodDeclaration method = ReviewMarks.enclosingMethod(reference.site().node());
        if (method == null) return;
        marks.computeIfAbsent(method, m -> new LinkedHashSet<>())
                .add(removal.type() + "." + removal.member() + " is gone from this BotMaker version — "
                        + what + ", so this may no longer do what it did.");
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

    private static List<ArgumentEdit> keepAll(int arguments) {
        List<ArgumentEdit> kept = new ArrayList<>();
        for (int i = 0; i < arguments; i++) kept.add(new ArgumentEdit.Keep(i));
        return kept;
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
                        || repairs.members().stream().anyMatch(m -> m.type().equals(reference.type())
                        && m.from().equals(reference.member()))) {
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
