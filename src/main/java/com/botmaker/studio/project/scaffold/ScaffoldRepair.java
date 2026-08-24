package com.botmaker.studio.project.scaffold;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.project.scaffold.ScaffoldCheck.Substitution;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites text Studio just generated so it names what the project's SDK actually has.
 *
 * <h2>Why the emitted text and not the generator</h2>
 *
 * <p>A generator is a text block: it can only write the one spelling it was written with. When the pinned SDK
 * is newer than this Studio and has renamed something, the answer is not to teach the generator a second
 * spelling — it would need one per release, forever. It is to emit Studio's own spelling and then apply the
 * substitutions the <em>jar itself</em> declares, which is the same thing an upgrade does to the user's code
 * and needs no Studio release at all for a pure rename.
 *
 * <h2>What it can express, and what it refuses</h2>
 *
 * <p>Two shapes, which is what the generators write:
 *
 * <ul>
 *   <li><b>A type that moved</b> — applied file-wide by {@link CallMigrator#renameTypeIn}, so the
 *       {@code extends} clause, the field types, the {@code new T(…)} and the imports all move together. This
 *       is the shape a package move takes, and it carries every member with it for free.</li>
 *   <li><b>A static call whose member moved</b> — {@code Wait.milliseconds(…)} becoming
 *       {@code Wait.millis(…)}, receiver included when the owning type moved too.</li>
 * </ul>
 *
 * <p>It <b>refuses</b> anything else, and the refusal is the point rather than a gap. A generated class
 * {@code extends Activity} and <em>overrides</em> {@code run()}; retargeting that is not a call rewrite, it is
 * a declaration rewrite whose correctness depends on the new supertype's shape — precisely the judgement a
 * mechanical repair must not make. The same goes for a member reached through an instance
 * ({@code GoHome.INSTANCE.execute()}), whose receiver's type is only knowable with bindings this parser does
 * not have. Such a substitution comes back in {@link Outcome#unexpressed()} and the caller treats it exactly
 * as {@link ScaffoldCheck.Status#UNSATISFIABLE}: write nothing.
 *
 * <p>The result is re-parsed before it is handed back. A rewrite that applies can still produce nonsense, and
 * a generated file that does not compile is worse than a refusal — the whole bar for scaffolding is flawless
 * or nothing.
 */
public final class ScaffoldRepair {

    private ScaffoldRepair() {}

    /**
     * The rewritten files, or the reason there are none.
     *
     * @param sources     {@code fileName -> source}, every file, rewritten or not — so a caller writes this
     *                    map instead of the one it rendered and nothing else changes
     * @param unexpressed the substitutions this could not write, by {@link Substitution#element()}'s
     *                    {@code line()}. Non-empty means <b>nothing</b> may be written.
     */
    public record Outcome(Map<String, String> sources, List<String> unexpressed) {

        public boolean canEmit() {
            return unexpressed.isEmpty();
        }
    }

    /**
     * Applies {@code substitutions} to every source in {@code rendered}.
     *
     * <p>A file that names none of them comes back byte-identical, which is what makes this safe to run on
     * every creation: with an SDK at or below Studio's own the check finds nothing and this is never called,
     * and even called with an empty list it is the identity function.
     */
    public static Outcome apply(Map<String, String> rendered, List<Substitution> substitutions) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> unexpressed = new ArrayList<>();
        for (Map.Entry<String, String> file : rendered.entrySet()) {
            String repaired = applyTo(file.getValue(), substitutions, file.getKey(), unexpressed);
            if (repaired != null) out.put(file.getKey(), repaired);
        }
        return unexpressed.isEmpty()
                ? new Outcome(Map.copyOf(out), List.of())
                : new Outcome(Map.of(), List.copyOf(unexpressed));
    }

    /**
     * One file. Null — with a line added to {@code unexpressed} — when the rewrite could not be applied, left
     * the old spelling somewhere it does not reach, or did not parse afterwards.
     */
    private static String applyTo(String source, List<Substitution> substitutions, String fileName,
                                  List<String> unexpressed) {
        CompilationUnit cu = SourceParser.parse(source);
        if (SourceParser.hasSyntaxErrors(cu)) {
            unexpressed.add(fileName + " did not parse before the repair");
            return null;
        }
        EditContext ctx = EditContext.of(cu, null, null);

        for (Substitution substitution : substitutions) {
            if (substitution.memberMoved()) renameCalls(ctx, substitution);
        }
        // Types last: the call rewrite above matches on the receiver as the *old* type still spells it, and a
        // file-wide rename applied first would leave nothing for it to match. Both edits go into one rewriter,
        // so the order here is the order they are recorded, not the order they hit the text.
        for (Substitution substitution : substitutions) {
            if (substitution.typeMoved()) {
                CallMigrator.renameTypeIn(ctx, substitution.element().type(), substitution.type());
            }
        }

        String rewritten = ctx.applyTo(source);
        if (rewritten == null) {
            unexpressed.add("the repair of " + fileName + " could not be applied");
            return null;
        }
        CompilationUnit after = rewritten.equals(source) ? cu : SourceParser.parse(rewritten);
        if (SourceParser.hasSyntaxErrors(after)) {
            unexpressed.add("the repair of " + fileName + " did not parse");
            return null;
        }
        // The check that makes the refusal above honest rather than optimistic. `renameCalls` reaches exactly
        // one shape — a static call on the type's own name — and every *other* way the emitted text can write
        // that member (an `@Override` of it, a method reference, a call through an instance) is left standing.
        // Standing means the file names a member the jar does not have, so the name surviving anywhere it is
        // *called or declared* is the signal that this substitution was beyond the rewriter.
        for (Substitution substitution : substitutions) {
            if (!substitution.memberMoved()) continue;
            String stale = substitution.element().member();
            if (namesMember(after, stale)) {
                unexpressed.add(substitution.element().line() + " (still written as \"" + stale + "\" in "
                        + fileName + ", which is not a call this can retarget)");
                return null;
            }
        }
        return rewritten;
    }

    /**
     * Whether {@code source}'s tree still writes {@code member} as something callable or declared — a call, a
     * method reference, or a declaration of that name. Deliberately name-only: without bindings there is no
     * way to prove the leftover is the SDK's member rather than a coincidence, and the safe reading of a
     * coincidence is to refuse, since scaffolding is all-or-nothing.
     */
    private static boolean namesMember(CompilationUnit unit, String member) {
        boolean[] found = {false};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (node.getName().getIdentifier().equals(member)) found[0] = true;
                return true;
            }

            @Override
            public boolean visit(ExpressionMethodReference node) {
                if (node.getName().getIdentifier().equals(member)) found[0] = true;
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(member)) found[0] = true;
                return true;
            }
        });
        return found[0];
    }

    /**
     * Renames {@code Type.member(…)} to the substitution's member wherever the emitted text writes it as a
     * static call on the type's own simple name.
     *
     * <p>The receiver is left alone: when the type moved too, {@link CallMigrator#renameTypeIn} rewrites every
     * use of that simple name in the file, this call's receiver included. Doing it here as well would record
     * two edits over one node.
     */
    private static void renameCalls(EditContext ctx, Substitution substitution) {
        String owner = simpleNameOf(substitution.element().type());
        String from = substitution.element().member();
        String to = substitution.member();
        ctx.cu().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (!node.getName().getIdentifier().equals(from)) return true;
                if (node.getExpression() instanceof SimpleName receiver
                        && receiver.getIdentifier().equals(owner)) {
                    ctx.rewriter().set(node.getName(), SimpleName.IDENTIFIER_PROPERTY, to, null);
                }
                return true;
            }
        });
    }

    private static String simpleNameOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
