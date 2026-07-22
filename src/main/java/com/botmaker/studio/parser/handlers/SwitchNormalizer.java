package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives every {@code switch} case a terminating {@code break}.
 *
 * <p>Studio renders a case's trailing {@code break} as case chrome, not as a deletable child block — so in the
 * editor fall-through cannot be created. Source can still arrive without one (hand-edited, or written outside
 * Studio), and then the chrome would be claiming something the code doesn't do. Normalising on open keeps the
 * two honest, and eliminates the class of bug fall-through causes for users who didn't intend it.
 *
 * <p>Two shapes are deliberately left alone. An <b>empty</b> case chunk ({@code case A: case B: …}) is the
 * multi-label idiom — inserting a break there would change what the code means, not tidy it. And a chunk already
 * ending in {@code return}/{@code throw}/{@code continue} can't fall through either, so it needs nothing.
 */
public final class SwitchNormalizer {

    private SwitchNormalizer() {}

    /**
     * @return the source with a {@code break} appended to every falling-through case, or {@code null} when
     *         nothing needed changing (the overwhelmingly common case — callers skip the edit entirely).
     */
    public static String addMissingBreaks(CompilationUnit cu, String originalCode) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        boolean[] changed = {false};

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SwitchStatement node) {
                // Arrow form (`case X -> …`) doesn't fall through and doesn't take a break — leave it be.
                if (usesArrowLabels(node)) return true;
                ListRewrite list = rewriter.getListRewrite(node, SwitchStatement.STATEMENTS_PROPERTY);
                for (List<Statement> chunk : caseChunks(node)) {
                    // chunk[0] is the SwitchCase label itself; anything after it is the case body.
                    if (chunk.size() <= 1) continue;
                    Statement last = chunk.getLast();
                    if (terminates(last)) continue;
                    list.insertAfter(ast.newBreakStatement(), last, null);
                    changed[0] = true;
                }
                return true;
            }
        });

        return changed[0] ? AstRewriteHelper.applyRewrite(rewriter, originalCode) : null;
    }

    private static boolean usesArrowLabels(SwitchStatement node) {
        for (Object o : node.statements()) {
            if (o instanceof SwitchCase sc && sc.isSwitchLabeledRule()) return true;
        }
        return false;
    }

    /** The switch's statement list split at each {@code case}/{@code default} label. */
    private static List<List<Statement>> caseChunks(SwitchStatement node) {
        List<List<Statement>> chunks = new ArrayList<>();
        List<Statement> current = null;
        for (Object o : node.statements()) {
            Statement s = (Statement) o;
            if (s instanceof SwitchCase) {
                current = new ArrayList<>();
                chunks.add(current);
            }
            if (current != null) current.add(s);
        }
        return chunks;
    }

    /** Whether control provably leaves the case at {@code s} — no {@code break} needed after it. */
    private static boolean terminates(Statement s) {
        return s instanceof BreakStatement || s instanceof ReturnStatement
                || s instanceof ThrowStatement || s instanceof ContinueStatement;
    }
}
