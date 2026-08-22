package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.ProjectVcs;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The three things a refactor has to do around a {@linkplain ReviewMarks review mark} that are about the
 * <em>project</em> rather than about one syntax tree: make sure the annotation exists, snapshot the project
 * before touching files the user is not looking at, and — for a rewrite done in text rather than in an AST —
 * mark the functions a set of changed lines fall inside.
 *
 * <p>{@link ReviewMarks} deliberately knows nothing of any of this. It edits one tree through one
 * {@link EditContext}, which is what makes it testable without a project on disk; everything that needs a
 * {@link ProjectConfig} lives here instead.
 *
 * <h2>Why {@link #prepare} may answer null, and why that is not a failure</h2>
 *
 * <p>A mark is a reference to a generated annotation. If that annotation cannot be written, a mark would not
 * compile — so the choice is between refusing the refactor and doing it unmarked, and unmarked is plainly the
 * better of the two: the user asked for the rename, not for the bookkeeping. {@code prepare} therefore returns
 * the package to import <em>or null</em>, and every marking path treats null as "do the change, record
 * nothing". It has to be called <b>before</b> the change is written, so the answer is known while refusing is
 * still cheap.
 *
 * <h2>Why the snapshot is best-effort</h2>
 *
 * <p>{@link #snapshot} is the "a refactor that rewrites files other than the active one is undoable" rule.
 * The editor's own ↶ covers the active file and the files a migration carried along with it, but only until
 * the session ends; Project History is what survives, and it is also the only undo for a rewrite done outside
 * the editor entirely (a template repoint). It is best-effort because a project whose history has never been
 * initialised, or whose git is wedged, must not lose the ability to rename a function.
 */
public final class ReviewMarker {

    private ReviewMarker() {}

    /**
     * Makes sure the bot has a {@code NeedsReview} annotation to be marked with, and answers the package to
     * import it from — or null when there is nowhere to write one, in which case the caller does its change
     * and records nothing.
     */
    public static String prepare(ProjectConfig config) {
        if (config == null || config.mainPackageDir() == null) return null;
        try {
            ReviewMarks.ensureFile(config.mainPackageDir(), config.mainPackage());
            return config.mainPackage();
        } catch (IOException e) {
            System.err.println("Couldn't write the review marker, so this change won't be recorded: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Commits the project as it stands, so what the refactor is about to do to files the user is not looking
     * at can be rolled back from Project History. Silent when there is no history to commit into.
     */
    public static void snapshot(ProjectConfig config, String label) {
        if (config == null || config.projectPath() == null) return;
        try {
            new ProjectVcs(config.projectPath()).commit(label);
        } catch (IOException e) {
            System.err.println("Couldn't snapshot the project before " + label + ": " + e.getMessage());
        }
    }

    /**
     * Whether a mark written into {@code file} would still be there tomorrow.
     *
     * <p>False for anything Studio generates. Those files <em>are</em> rewritten by a refactor — a call in the
     * activity registry has to be renamed with everything else or the bot stops compiling — but they are
     * regenerated from the project's shape on the next save, which silently erases a mark. A review row that
     * disappears on its own is worse than no row: the user is not told the thing they were meant to look at
     * has stopped being listed. The change lands; only the bookkeeping is skipped.
     */
    public static boolean marksSurvive(ProjectConfig config, ProjectState state, Path file) {
        if (config == null || file == null) return false;
        return !FileRole.isDerived(config, state == null ? null : state.getTemplate(), file);
    }

    /**
     * Marks, in {@code source}, every function that one of {@code lines} falls inside — the AST-free rewrite's
     * way in.
     *
     * <p>A text rewrite (see {@code TemplateReferences}) knows which lines it changed and nothing else, so the
     * function is worked out afterwards by asking each declaration whether it spans the line. That is the
     * whole reason this is separate from {@link ReviewMarks#mark}: everywhere else the node being changed is
     * in hand, and walking up from it is exact.
     *
     * @param lines 1-based line numbers in {@code source}, as {@code TemplateReferences.Use} reports them
     * @return the marked source, or {@code source} unchanged when nothing could be marked
     */
    public static String markLines(String source, Collection<Integer> lines, String markerPackage,
                                   String entry) {
        if (source == null || lines == null || lines.isEmpty() || markerPackage == null) return source;
        CompilationUnit unit = SourceParser.parse(source);
        if (unit == null || SourceParser.hasSyntaxErrors(unit)) return source;

        Set<Integer> wanted = Set.copyOf(lines);
        List<MethodDeclaration> enclosing = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (spansAny(unit, node, wanted)) enclosing.add(node);
                return true;
            }
        });
        if (enclosing.isEmpty()) return source;

        EditContext ctx = EditContext.of(unit, null, null);
        for (MethodDeclaration method : enclosing) ReviewMarks.mark(ctx, method, markerPackage, List.of(entry));
        String marked = ctx.applyTo(source);
        // A mark that does not parse is worth less than no mark at all: the file the user has to review is the
        // one they now cannot open. Same rule the migration runner applies to its own rewrites.
        return marked == null || SourceParser.hasSyntaxErrors(SourceParser.parse(marked)) ? source : marked;
    }

    /** Whether {@code method}'s own text — body included — covers any of {@code lines}. */
    private static boolean spansAny(CompilationUnit unit, MethodDeclaration method, Set<Integer> lines) {
        int start = unit.getLineNumber(method.getStartPosition());
        int end = unit.getLineNumber(method.getStartPosition() + method.getLength() - 1);
        if (start < 0 || end < 0) return false;
        for (int line : lines) {
            if (line >= start && line <= end) return true;
        }
        return false;
    }
}
