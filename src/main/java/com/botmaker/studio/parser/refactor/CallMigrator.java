package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.EditContext;
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
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.SimpleName;
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

    /** Records into {@code ctx}'s rewrite every call the plan changes that lives in {@code ctx.cu()}. */
    public static void applyIn(EditContext ctx, SignatureMigration.Plan plan) {
        if (plan == null) return;
        for (CallChange change : plan.calls()) {
            if (change.site().unit() == ctx.cu()) apply(ctx, change);
        }
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
            for (CallChange change : entry.getValue()) apply(ctx, change);

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

    /** A {@code List<Point>} is a list; anything else is what the signature calls it. */
    private static ResolvedType resolvedOf(SignatureType type) {
        return type.described().filter(BotType.Choice::isList)
                .map(choice -> ResolvedType.named("java.util.List"))
                .orElseGet(() -> ResolvedType.named(type.sourceName()));
    }

    // --- one call ------------------------------------------------------------------------------------------

    private static void apply(EditContext ctx, CallChange change) {
        switch (change) {
            case CallChange.ValueReplaced replaced -> ctx.rewriter()
                    .replace(replaced.site().node(), defaultFor(ctx, replaced.expected()), null);
            case CallChange.Rewrite rewrite -> applyRewrite(ctx, rewrite);
        }
    }

    private static void applyRewrite(EditContext ctx, CallChange.Rewrite rewrite) {
        CallSite site = rewrite.site();
        SimpleName name = site.nameNode();
        // Null for a `new GoHome(…)`: its name is the class's, and renaming that is a different edit.
        if (name != null && !name.getIdentifier().equals(rewrite.newName())) {
            ctx.rewriter().set(name, SimpleName.IDENTIFIER_PROPERTY, rewrite.newName(), null);
        }
        if (unchanged(site, rewrite.arguments())) return;

        ListRewrite arguments = ctx.rewriter().getListRewrite(site.node(), site.argumentsProperty());
        List<?> current = site.arguments();
        for (int i = current.size() - 1; i >= 0; i--) arguments.remove((ASTNode) current.get(i), null);
        for (ArgumentEdit edit : rewrite.arguments()) {
            arguments.insertLast(nodeFor(ctx, current, edit), null);
        }
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
