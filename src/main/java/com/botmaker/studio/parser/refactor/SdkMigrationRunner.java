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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries out the repairs an SDK ships with its breaks — the half of the upgrade that writes.
 *
 * <p>{@code services/SdkUpgradeService} reads {@code META-INF/botmaker/migrations.json} out of the target jar
 * and turns each automatic entry into a {@link Fix}. This turns those into edits, through the same
 * {@link CallMigrator} a signature change goes through, and hands back the new source of every file — or a
 * sentence saying why it will not.
 *
 * <h2>Ordered replay, never a fixpoint</h2>
 *
 * <p>A bot on 1.x jumping to 3.0.0 does not follow a chain of renames. <b>Each version in the span is applied
 * as its own pass, in ascending order, over the source the previous pass produced.</b> If 2.0.0 renamed
 * {@code foo} to {@code bar} and 3.0.0 renames {@code bar} to {@code baz}, the 2.0.0 pass writes {@code bar}
 * and the 3.0.0 pass then finds exactly what its own entry names — because the SDK author wrote that entry in
 * 3.0.0's spelling. Composition is free and no entry ever needs to know another exists.
 *
 * <p>Iterating to a fixpoint instead would be a bug rather than an optimisation: {@code a → b} in 2.0.0 and
 * {@code b → a} in 3.0.0 is a legal pair of releases and an infinite loop for anything that re-runs the whole
 * set until nothing changes. Ordered replay gets it right — {@code a → b → a}, a no-op — without noticing that
 * it was a hard case.
 *
 * <h2>Nothing, or all of it</h2>
 *
 * <p>Every refusal — a file that stops parsing, a {@code case} label whose enum cannot be told, a constant
 * moved out from under a switch, a rewrite that produces text no compiler accepts — abandons the <em>whole</em>
 * migration with nothing written anywhere. The alternative is a project in neither shape, with nothing telling
 * the user which half was touched. Disk is only reached afterwards, by {@code CallMigrator.commit}.
 *
 * <h2>Studio's own scaffolding is not rewritten</h2>
 *
 * <p>The generated entry point, {@code FlowDriver}, {@code ActivityRegistry}, {@code Activities} and
 * {@code Templates} are renderings of things the user has on screen, written by <em>this Studio</em> — so
 * rewriting them would be overwritten at the next regeneration, and regenerating them would reproduce the same
 * old-SDK code, since the templates that produce them live in the Studio build, not in the SDK. When a fix
 * targets something a scaffold file uses, the migration is refused and says so: that upgrade needs a newer
 * Studio, not a cleverer rewrite.
 */
public final class SdkMigrationRunner {

    private SdkMigrationRunner() {}

    static final String RENAME_METHOD = "renameMethod";
    static final String RENAME_TYPE = "renameType";
    static final String RENAME_FIELD = "renameField";
    static final String MOVE_MEMBER = "moveMember";
    static final String DROP_ARGUMENT = "dropArgument";
    static final String REORDER_ARGUMENTS = "reorderArguments";
    static final String INSERT_ARGUMENT = "insertArgument";

    /**
     * One automatic repair, flattened out of a {@code migrations.json} entry.
     *
     * <p>Flat and mostly-nullable on purpose: each {@code kind} reads different options, and the alternative —
     * a sealed type per kind — would put the file's grammar in two places, since {@code SdkUpgradeService}
     * already has to know which JSON field each kind uses in order to validate it. The fields each kind reads:
     *
     * <table>
     *   <tr><td>{@code renameMethod}, {@code renameField}</td><td>{@code to}</td></tr>
     *   <tr><td>{@code renameType}</td><td>{@code to} (a fully-qualified name, so a package move is the same
     *       kind)</td></tr>
     *   <tr><td>{@code moveMember}</td><td>{@code toType}, and {@code to} when the move renames too</td></tr>
     *   <tr><td>{@code dropArgument}</td><td>{@code index}</td></tr>
     *   <tr><td>{@code reorderArguments}</td><td>{@code order}</td></tr>
     *   <tr><td>{@code insertArgument}</td><td>{@code index}, {@code value}, {@code importFqn}</td></tr>
     * </table>
     *
     * <p>{@code arity} is {@code -1} for "every overload". It exists because call sites are matched by arity
     * and not by argument type, so an unscoped rename would hit overloads the SDK author did not mean.
     */
    public record Fix(String version, String typeFqn, String member, int arity, String kind,
                      String to, String toType, int index, List<Integer> order,
                      String value, String importFqn) {

        /** The type as the bot's source writes it — the simple name, since there are no bindings to resolve. */
        public String type() {
            int dot = typeFqn.lastIndexOf('.');
            return dot < 0 ? typeFqn : typeFqn.substring(dot + 1);
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
     * Replays {@code fixes} over the project.
     *
     * @param fixes       every automatic repair in the span, <b>already sorted ascending by version</b> — this
     *                    groups them in the order it is given and does not re-sort, because the caller is the
     *                    one that knows how to compare two SDK version strings
     * @param editable    the files that may be rewritten ({@code FileRole.EDITABLE})
     * @param generated   the files that may not, scanned only so a fix that would have touched one is caught
     * @param sdkTypes    every SDK class simple name, for {@link SdkReferences}
     * @param fieldOwners constant name → declaring SDK types, likewise
     */
    public static Outcome run(List<Fix> fixes, List<ProjectFile> editable, List<ProjectFile> generated,
                              Set<String> sdkTypes, Map<String, List<String>> fieldOwners,
                              ProjectAnalyzer analyzer, ProjectState state) {
        Map<ProjectFile, String> current = new LinkedHashMap<>();
        for (ProjectFile file : editable) {
            if (file.getContent() != null) current.put(file, file.getContent());
        }

        Map<String, List<Fix>> byVersion = new LinkedHashMap<>();
        for (Fix fix : fixes) byVersion.computeIfAbsent(fix.version(), v -> new ArrayList<>()).add(fix);

        for (Map.Entry<String, List<Fix>> pass : byVersion.entrySet()) {
            String blocked = scaffoldingInTheWay(generated, pass.getValue(), sdkTypes, fieldOwners);
            if (blocked != null) return Outcome.refused(blocked);

            for (Map.Entry<ProjectFile, String> file : current.entrySet()) {
                Applied applied = applyPass(file.getKey(), file.getValue(), pass.getKey(), pass.getValue(),
                        sdkTypes, fieldOwners, analyzer, state);
                if (applied.refusal() != null) return Outcome.refused(applied.refusal());
                if (applied.text() != null) file.setValue(applied.text());
            }
        }

        List<CallMigrator.Rewritten> changed = new ArrayList<>();
        current.forEach((file, text) -> {
            if (!text.equals(file.getContent())) changed.add(new CallMigrator.Rewritten(file, text));
        });
        return new Outcome(List.copyOf(changed), null);
    }

    // --- one file, one version ------------------------------------------------------------------------------

    /** The new text of a file after one pass — {@code text} null when the pass left it alone. */
    private record Applied(String text, String refusal) {

        static Applied unchanged() {
            return new Applied(null, null);
        }

        static Applied refused(String why) {
            return new Applied(null, why);
        }
    }

    private static Applied applyPass(ProjectFile file, String text, String version, List<Fix> fixes,
                                     Set<String> sdkTypes, Map<String, List<String>> fieldOwners,
                                     ProjectAnalyzer analyzer, ProjectState state) {
        CompilationUnit unit = SourceParser.parse(text);
        if (unit == null || SourceParser.hasSyntaxErrors(unit)) {
            return Applied.refused("\"" + file.getClassName() + "\" does not parse, so it could not be "
                    + "migrated. Fix that file first — nothing has been changed.");
        }
        SdkReferences.Scan scan = SdkReferences.in(file, unit, file.getClassName(), sdkTypes, fieldOwners);
        if (!scan.problems().isEmpty()) return Applied.refused(scan.problems().getFirst());

        List<Fix> typeRenames = new ArrayList<>();
        for (Fix fix : fixes) {
            if (RENAME_TYPE.equals(fix.kind()) && SdkReferences.mentions(unit, fix.type())) typeRenames.add(fix);
        }
        List<CallChange> changes = changesFor(fixes, typeRenames, scan.references());
        if (changes == null) {
            return Applied.refused("SDK " + version + " declares a repair this Studio cannot make sense of, so "
                    + "nothing has been changed. Update Studio, or upgrade one version at a time by hand.");
        }
        if (typeRenames.isEmpty() && changes.isEmpty()) return Applied.unchanged();

        EditContext ctx = EditContext.of(unit, analyzer, state);
        for (Fix rename : typeRenames) CallMigrator.renameTypeIn(ctx, rename.typeFqn(), rename.to());
        SignatureMigration.Plan plan =
                new SignatureMigration.Plan(changes, List.of(), List.of(), ReturnFate.UNCHANGED);
        if (!CallMigrator.applyIn(ctx, plan)) {
            return Applied.refused("SDK " + version + " moves something \"" + file.getClassName() + "\" uses in "
                    + "a way that cannot be repaired from the source alone — most often a constant used as a "
                    + "case label, whose type the source never names. Nothing has been changed.");
        }

        String rewritten;
        try {
            rewritten = ctx.applyTo(text);
        } catch (RuntimeException e) {
            // Two edits landing on one node — a type renamed and a member of it moved in the same release.
            rewritten = null;
        }
        if (rewritten == null || SourceParser.hasSyntaxErrors(SourceParser.parse(rewritten))) {
            return Applied.refused("Repairing \"" + file.getClassName() + "\" for SDK " + version + " produced "
                    + "source that does not compile, so nothing has been changed.");
        }
        return rewritten.equals(text) ? Applied.unchanged() : new Applied(rewritten, null);
    }

    /**
     * The per-call edits for one pass — or {@code null} when a fix is malformed, which refuses the migration
     * rather than quietly skipping the one entry nobody would then be told about.
     *
     * <p>A member of a type being renamed in the same pass is left to the rename: both edits would land on the
     * same nodes, and {@code renameTypeIn} already rewrites every use of the type, the ones no call scan finds
     * included.
     */
    private static List<CallChange> changesFor(List<Fix> fixes, List<Fix> typeRenames,
                                               List<SdkReferences.Reference> references) {
        List<CallChange> out = new ArrayList<>();
        for (Fix fix : fixes) {
            if (RENAME_TYPE.equals(fix.kind())) continue;
            if (typeRenames.stream().anyMatch(rename -> rename.type().equals(fix.type()))) continue;
            for (SdkReferences.Reference reference : references) {
                if (!reference.type().equals(fix.type())) continue;
                if (!reference.member().equals(fix.member())) continue;
                if (fix.arity() >= 0 && reference.argCount() != fix.arity()) continue;
                CallChange change = changeFor(fix, reference);
                if (change == null) return null;
                out.add(change);
            }
        }
        return out;
    }

    private static CallChange changeFor(Fix fix, SdkReferences.Reference reference) {
        int arguments = Math.max(0, reference.argCount());
        return switch (fix.kind()) {
            case RENAME_METHOD, RENAME_FIELD -> blank(fix.to())
                    ? null
                    : new CallChange.Rewrite(reference.site(), fix.to(), keepAll(arguments));
            case MOVE_MEMBER -> blank(fix.toType())
                    ? null
                    : new CallChange.MemberMoved(reference.site(), fix.toType(), blank(fix.to()) ? null : fix.to());
            case DROP_ARGUMENT -> {
                if (fix.index() < 0 || fix.index() >= arguments) yield null;
                List<ArgumentEdit> kept = new ArrayList<>();
                for (int i = 0; i < arguments; i++) {
                    if (i != fix.index()) kept.add(new ArgumentEdit.Keep(i));
                }
                yield new CallChange.Rewrite(reference.site(), reference.member(), kept);
            }
            case REORDER_ARGUMENTS -> {
                List<Integer> order = fix.order();
                if (order == null || order.size() != arguments) yield null;
                List<ArgumentEdit> moved = new ArrayList<>();
                for (int from : order) {
                    if (from < 0 || from >= arguments) yield null;
                    moved.add(new ArgumentEdit.Keep(from));
                }
                yield new CallChange.Rewrite(reference.site(), reference.member(), moved);
            }
            case INSERT_ARGUMENT -> {
                int at = fix.index();
                if (at < 0 || at > arguments || blank(fix.value())) yield null;
                List<ArgumentEdit> widened = new ArrayList<>();
                for (int i = 0; i < arguments; i++) {
                    if (i == at) widened.add(new ArgumentEdit.Literal(fix.value(), fix.importFqn()));
                    widened.add(new ArgumentEdit.Keep(i));
                }
                if (at == arguments) widened.add(new ArgumentEdit.Literal(fix.value(), fix.importFqn()));
                yield new CallChange.Rewrite(reference.site(), reference.member(), widened);
            }
            default -> null;
        };
    }

    private static List<ArgumentEdit> keepAll(int arguments) {
        List<ArgumentEdit> kept = new ArrayList<>();
        for (int i = 0; i < arguments; i++) kept.add(new ArgumentEdit.Keep(i));
        return kept;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The reason a scaffold file blocks this pass, or null when none does. See the class Javadoc for why a
     * generated file is neither rewritten nor regenerated: the code in it is written by Studio's own
     * templates, so only a newer Studio can make it speak the new SDK.
     */
    private static String scaffoldingInTheWay(List<ProjectFile> generated, List<Fix> fixes,
                                              Set<String> sdkTypes, Map<String, List<String>> fieldOwners) {
        for (ProjectFile file : generated) {
            String text = file.getContent();
            if (text == null) continue;
            CompilationUnit unit = SourceParser.parse(text);
            if (unit == null || SourceParser.hasSyntaxErrors(unit)) continue;

            for (Fix fix : fixes) {
                boolean touched = RENAME_TYPE.equals(fix.kind())
                        ? SdkReferences.mentions(unit, fix.type())
                        : SdkReferences.in(file, unit, file.getClassName(), sdkTypes, fieldOwners).references()
                        .stream()
                        .anyMatch(r -> r.type().equals(fix.type()) && r.member().equals(fix.member()));
                if (touched) {
                    return "SDK " + fix.version() + " changes " + fix.type()
                            + (blank(fix.member()) ? "" : "." + fix.member())
                            + ", which BotMaker's own generated file \"" + file.getClassName() + "\" uses. "
                            + "Generated files are never rewritten by an upgrade — update Studio itself so it "
                            + "generates code for this SDK. Nothing has been changed.";
                }
            }
        }
        return null;
    }
}
