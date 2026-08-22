package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.parser.refactor.SignatureMigration.CallChange;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The writing half of a signature change: it takes the {@linkplain SignatureMigration.Plan plan} the user has
 * just approved and turns it into edits.
 *
 * <p>Two audiences, because a project's calls are not all in the file being edited. The declaration's own file
 * goes through {@link com.botmaker.studio.parser.CodeEditor}'s guarded write like every other edit —
 * {@link #applyIn} records the calls that live there into <em>that same</em> rewrite, so the declaration and
 * its local calls change together or not at all. Every other file is rewritten here, in
 * {@link #rewriteOthers}, and — this is the point — <b>rewritten before anything is published</b>: the new
 * source for all of them is computed and parsed first, and a single file that comes out broken abandons the
 * whole change with nothing written anywhere.
 *
 * <p>Only then does {@link #commit} touch the disk. A migration that half-lands is the failure this whole
 * feature exists to prevent, so the ordering is deliberate rather than incidental.
 */
public final class CallMigrator {

    private CallMigrator() {}

    /** A file the migration changes, and what it should say afterwards. */
    public record Rewritten(ProjectFile file, String newSource) {}

    /**
     * Records into {@code ctx}'s rewrite every call the plan changes that lives in {@code ctx.cu()} — false
     * when one of them cannot be expressed there, which is the caller's cue to abandon the edit.
     *
     * <p>A plan built from a signature dialog can never fail this: its changes are renames and argument
     * shuffles at sites it just found. Only an SDK migration produces a change that can be refused.
     */
    public static boolean applyIn(EditContext ctx, SignatureMigration.Plan plan) {
        if (plan == null) return true;
        boolean applied = true;
        for (CallChange change : plan.calls()) {
            if (change.site().unit() == ctx.cu()) applied &= apply(ctx, change);
        }
        return applied;
    }

    /**
     * The new source of every file the plan touches other than {@code active} — or {@code null} when one of
     * them cannot be rewritten into source that parses, which refuses the migration entirely.
     *
     * <p>Files whose text the change leaves alone (a call standing as its own statement, when only the return
     * type moved) are simply absent from the result rather than rewritten to themselves.
     */
    public static List<Rewritten> rewriteOthers(SignatureMigration.Plan plan, CompilationUnit active,
                                                ProjectAnalyzer analyzer, ProjectState state) {
        Map<CompilationUnit, List<CallChange>> byUnit = new LinkedHashMap<>();
        for (CallChange change : plan.calls()) {
            if (change.site().unit() == active) continue;
            byUnit.computeIfAbsent(change.site().unit(), unit -> new ArrayList<>()).add(change);
        }

        List<Rewritten> rewritten = new ArrayList<>();
        for (Map.Entry<CompilationUnit, List<CallChange>> entry : byUnit.entrySet()) {
            ProjectFile file = entry.getValue().getFirst().site().file();
            EditContext ctx = EditContext.of(entry.getKey(), analyzer, state);
            // A change this cannot express — a constant moved to another class with no type written at the
            // call site to retarget — refuses the migration whole, exactly as an unparseable result does.
            for (CallChange change : entry.getValue()) {
                if (!apply(ctx, change)) return null;
            }

            String source = ctx.applyTo(file.getContent());
            if (source == null) return null;
            if (source.equals(file.getContent())) continue;
            // The one check that stands between a bad rewrite and a file the user cannot open: applyRewrite
            // returns the original text when an edit won't apply, and a rewrite that *does* apply can still
            // produce nonsense. Either way this file is not written, and neither is any other.
            if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return null;
            rewritten.add(new Rewritten(file, source));
        }
        return List.copyOf(rewritten);
    }

    /**
     * Writes the rewritten files to disk and updates the editor's copy of each.
     *
     * <p>The cached AST is dropped rather than re-parsed: {@code ProjectAnalyzer} re-parses a file whose AST is
     * missing and skips one that has any, so leaving yesterday's tree in place would keep every suggestion in
     * Studio reading the pre-migration signature.
     */
    public static void commit(List<Rewritten> files) throws IOException {
        for (Rewritten each : files) {
            Files.writeString(each.file().getPath(), each.newSource());
            each.file().setContent(each.newSource());
            each.file().setAst(null);
        }
    }

    /** A default value for {@code type}, importing whatever naming it needs in {@code ctx}'s file. */
    public static Expression defaultFor(EditContext ctx, SignatureType type) {
        type.described().ifPresent(choice -> {
            choice.type().sdkType().ifPresent(ctx::addImport);
            if (choice.isList()) ctx.addImport("java.util.List");
        });
        ResolvedType resolved = resolvedOf(type);
        Expression value = InitializerFactory.createDefaultInitializer(ctx.ast(), resolved, ctx.cu(), ctx.state());
        return value == null ? ctx.ast().newNullLiteral() : value;
    }

    /**
     * A default for {@code typeName} that compiles whatever the type turns out to be: {@code false},
     * {@code 0}, {@code ""} or {@code null}, and no imports.
     *
     * <p>Deliberately blunter than {@link #defaultFor}. That one asks the palette, which will happily answer
     * {@code new Point()} — correct for a signature edit, where the type is one the editor offers and is
     * certainly on the classpath, and wrong for an SDK upgrade, where the type is often the very one the new
     * jar dropped. Naming a class that no longer exists trades a missing method for a missing class.
     *
     * <p>{@code null} for every reference type, including {@code List} and the boxed primitives. An empty
     * list would sometimes be kinder and sometimes be a lie about what the bot used to do; {@code null} is
     * uniformly honest, and the function it sits in is marked for review either way.
     */
    public static Expression literalDefaultFor(EditContext ctx, String typeName) {
        String type = typeName == null ? "" : typeName.trim();
        return switch (type) {
            case "boolean" -> ctx.ast().newBooleanLiteral(false);
            case "byte", "short", "int", "long", "float", "double", "char" -> ctx.ast().newNumberLiteral("0");
            case "String", "java.lang.String" -> ctx.ast().newStringLiteral();
            default -> ctx.ast().newNullLiteral();
        };
    }

    /** A {@code List<Point>} is a list; anything else is what the signature calls it. */
    private static ResolvedType resolvedOf(SignatureType type) {
        return type.described().filter(BotType.Choice::isList)
                .map(choice -> ResolvedType.named("java.util.List"))
                .orElseGet(() -> ResolvedType.named(type.sourceName()));
    }

    // --- one call ------------------------------------------------------------------------------------------

    /** Records one change into {@code ctx}'s rewrite — false when it cannot be expressed at that site. */
    private static boolean apply(EditContext ctx, CallChange change) {
        return switch (change) {
            case CallChange.ValueReplaced replaced -> {
                ctx.rewriter().replace(replaced.site().node(), defaultFor(ctx, replaced.expected()), null);
                yield true;
            }
            case CallChange.ValueDefaulted defaulted -> {
                ctx.rewriter().replace(defaulted.site().node(),
                        literalDefaultFor(ctx, defaulted.typeName()), null);
                yield true;
            }
            case CallChange.Rewrite rewrite -> applyRewrite(ctx, rewrite);
            case CallChange.CallDeleted deleted -> {
                // The site was only ever recorded as deleted because it *is* a statement; this re-checks
                // rather than casting, because a refusal here is the difference between a whole migration
                // abandoned cleanly and a ClassCastException out of a background thread.
                if (!(deleted.site().node().getParent() instanceof ExpressionStatement statement)) {
                    yield false;
                }
                ctx.rewriter().remove(statement, null);
                yield true;
            }
        };
    }

    private static boolean applyRewrite(EditContext ctx, CallChange.Rewrite rewrite) {
        CallSite site = rewrite.site();
        SimpleName name = site.nameNode();
        // Null for a `new GoHome(…)`: its name is the class's, and renaming that is a different edit.
        if (name != null && !name.getIdentifier().equals(rewrite.newName())) {
            // A bare name reached through a static import is only half-renamed by touching the use: the
            // import still names the old member, so the file stops compiling. `case UP ->` is the one bare
            // name with no import behind it, and rightly gets none invented for it.
            if (site.node() instanceof SimpleName && !isCaseLabel(site.node())
                    && !renameStaticImport(ctx, name.getIdentifier(), rewrite.newName())) {
                return false;
            }
            ctx.rewriter().set(name, SimpleName.IDENTIFIER_PROPERTY, rewrite.newName(), null);
        }
        if (unchanged(site, rewrite.arguments())) return true;

        ListRewrite arguments = ctx.rewriter().getListRewrite(site.node(), site.argumentsProperty());
        List<?> current = site.arguments();
        for (int i = current.size() - 1; i >= 0; i--) arguments.remove((ASTNode) current.get(i), null);
        for (ArgumentEdit edit : rewrite.arguments()) {
            Expression argument = nodeFor(ctx, current, edit);
            if (argument == null) return false;
            arguments.insertLast(argument, null);
        }
        return true;
    }

    /**
     * Renames every use of type {@code fromFqn} in {@code ctx}'s file to {@code toFqn}, imports included.
     *
     * <p>File-level rather than per-call, and that is the whole reason it isn't a {@link CallChange}: a type is
     * written in places no call scan records — {@code Precision p;}, a cast, a type argument — and renaming
     * only the places a call was found leaves a file naming a class that no longer exists.
     *
     * <p>What counts as "a use of the type" is read off the shape of the source, since there are no bindings:
     * a name standing as a {@link SimpleType}, the qualifier of a qualified name, or the receiver of a call.
     * A local variable sharing a class's exact name would be caught too; that is the accepted cost of no
     * bindings, and Java naming makes it vanishingly rare.
     */
    public static void renameTypeIn(EditContext ctx, String fromFqn, String toFqn) {
        String from = simpleNameOf(fromFqn);
        String to = simpleNameOf(toFqn);
        // A package move keeps the simple name, so every use in the body is already correct: only the imports
        // move. Rewriting each name to itself would churn the file for nothing.
        if (!from.equals(to)) ctx.cu().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!node.getIdentifier().equals(from) || withinImport(node)) return true;
                if (node.getLocationInParent() == SimpleType.NAME_PROPERTY
                        || node.getLocationInParent() == QualifiedName.QUALIFIER_PROPERTY
                        || node.getLocationInParent() == MethodInvocation.EXPRESSION_PROPERTY) {
                    ctx.rewriter().set(node, SimpleName.IDENTIFIER_PROPERTY, to, null);
                }
                return true;
            }
        });
        for (Object each : ctx.cu().imports()) {
            ImportDeclaration imp = (ImportDeclaration) each;
            if (!imp.isStatic()) continue;
            // `import static a.b.Tolerance.TIGHT;` — the type is the qualifier, and no other edit reaches it.
            if (imp.getName() instanceof QualifiedName qualified
                    && qualified.getQualifier().getFullyQualifiedName().equals(fromFqn)) {
                ctx.rewriter().replace(qualified.getQualifier(),
                        ctx.ast().newName(toFqn), null);
            }
        }
        ImportManager.removeImport(ctx.cu(), ctx.rewriter(), fromFqn);
        ctx.addImport(toFqn);
    }

    private static boolean isCaseLabel(ASTNode node) {
        return node.getLocationInParent() == SwitchCase.EXPRESSIONS2_PROPERTY;
    }

    private static boolean withinImport(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof ImportDeclaration) return true;
        }
        return false;
    }

    /** Renames the member in the static import that brought {@code member} in — false when there is none. */
    private static boolean renameStaticImport(EditContext ctx, String member, String newName) {
        ImportDeclaration imp = staticImportOf(ctx.cu(), member);
        if (imp == null || !(imp.getName() instanceof QualifiedName qualified)) return false;
        ctx.rewriter().set(qualified.getName(), SimpleName.IDENTIFIER_PROPERTY, newName, null);
        return true;
    }

    /**
     * The {@code import static …} that brings {@code member} into the file by name, or null.
     *
     * <p>An on-demand {@code import static …Key.*} is not one: it says nothing about which names were meant,
     * so there is nothing to rewrite in it and nothing that could be rewritten safely.
     */
    private static ImportDeclaration staticImportOf(CompilationUnit cu, String member) {
        for (Object each : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) each;
            if (!imp.isStatic() || imp.isOnDemand()) continue;
            if (imp.getName() instanceof QualifiedName qualified
                    && qualified.getName().getIdentifier().equals(member)) {
                return imp;
            }
        }
        return null;
    }

    private static String simpleNameOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    /** True when the wanted arguments are the ones already there, in the order they are already in. */
    private static boolean unchanged(CallSite site, List<ArgumentEdit> wanted) {
        if (wanted.size() != site.argumentCount()) return false;
        for (int i = 0; i < wanted.size(); i++) {
            if (!(wanted.get(i) instanceof ArgumentEdit.Keep keep) || keep.from() != i) return false;
        }
        return true;
    }

    /**
     * The node for one argument of the new call: a copy of what the user wrote, or a default value.
     *
     * <p>A copy, not the original node — the list it came from is being removed by the same rewrite, and a node
     * cannot be both removed and re-inserted.
     */
    private static Expression nodeFor(EditContext ctx, List<?> current, ArgumentEdit edit) {
        return switch (edit) {
            case ArgumentEdit.Keep keep ->
                    (Expression) ASTNode.copySubtree(ctx.ast(), (ASTNode) current.get(keep.from()));
            case ArgumentEdit.Fresh fresh -> defaultFor(ctx, fresh.type());
        };
    }
}
