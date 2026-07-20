package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.RangeMarker;
import org.eclipse.text.edits.TextEdit;

/**
 * Common utilities for AST rewriting operations.
 */
public class AstRewriteHelper {

    /**
     * Applies an ASTRewrite to source code and returns the modified code.
     * @param rewriter The ASTRewrite to apply
     * @param originalCode The original source code
     * @return The modified code, or original code if rewrite fails
     */
    public static String applyRewrite(ASTRewrite rewriter, String originalCode) {
        IDocument document = new Document(originalCode);
        try {
            TextEdit edits = rewriter.rewriteAST(document, null);
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Applies {@code rewriter}, then inserts {@code text} at what {@code offset} in the <em>original</em> code
     * has become — for edits that must land at a raw source position the AST cannot name.
     *
     * <p>The position is tracked with a {@link RangeMarker} added to the rewrite's own edit tree and applied
     * with {@link TextEdit#UPDATE_REGIONS}, so Eclipse shifts it for us. A plain
     * "{@code offset + (newLength - oldLength)}" delta would only be right when every edit happens to precede
     * the offset; the marker is correct wherever the other edits land.
     *
     * <p>Falls back to a plain {@link #applyRewrite} if the marker can't be attached (it would overlap an
     * edit) — better to lose the extra insertion than to corrupt the file.
     */
    public static String applyRewriteAndInsertAt(ASTRewrite rewriter, String originalCode, int offset, String text) {
        IDocument document = new Document(originalCode);
        try {
            TextEdit edits = rewriter.rewriteAST(document, null);
            RangeMarker marker = new RangeMarker(offset, 0);
            try {
                edits.addChild(marker);
            } catch (MalformedTreeException overlapping) {
                edits.apply(document);
                return document.get();
            }
            edits.apply(document, TextEdit.UPDATE_REGIONS);
            document.replace(marker.getOffset(), 0, text);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Removes an AST node and applies the change.
     */
    public static String removeNode(CompilationUnit cu, String originalCode, ASTNode node) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.remove(node, null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a SimpleName node and applies the change.
     */
    public static String renameSimpleName(CompilationUnit cu, String originalCode,
                                          SimpleName nameNode, String newName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.replace(nameNode, ast.newSimpleName(newName), null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a variable declared by an enhanced-for loop, updating the declaration <em>and</em> every
     * reference to it within the loop, so the result still compiles. {@link #renameSimpleName} replaces only
     * the single node it is handed; a loop variable also appears in the loop body, and renaming just the
     * declaration leaves those references dangling on the old name.
     *
     * <p>The walk is scoped to the enclosing {@link EnhancedForStatement} (the variable's whole scope), so a
     * same-named variable elsewhere in the method is untouched, and matches by binding key rather than by text
     * so shadowing can't misfire. Falls back to renaming the lone declaration node when the loop or the binding
     * can't be resolved (routine while a sibling file is uncompiled) — never worse than the old behaviour.
     */
    public static String renameForEachVariable(CompilationUnit cu, String originalCode,
                                               SimpleName declName, String newName) {
        EnhancedForStatement loop = enclosingEnhancedFor(declName);
        IVariableBinding target = declName.resolveBinding() instanceof IVariableBinding vb ? vb : null;
        if (loop == null || target == null) {
            return renameSimpleName(cu, originalCode, declName, newName);
        }
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        String targetKey = target.getKey();
        loop.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding vb && targetKey.equals(vb.getKey())) {
                    rewriter.replace(node, ast.newSimpleName(newName), null);
                }
                return true;
            }
        });
        return applyRewrite(rewriter, originalCode);
    }

    private static EnhancedForStatement enclosingEnhancedFor(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof EnhancedForStatement efs) return efs;
        }
        return null;
    }

    /**
     * Returns the statement {@link ListRewrite} for a body block, whether it is backed by a
     * {@link Block} or a {@link SwitchCase}.
     */
    public static ListRewrite getListRewriteForBody(ASTRewrite rewriter, BodyBlock body) {
        ASTNode node = body.getAstNode();
        if (node instanceof Block) {
            return rewriter.getListRewrite(node, Block.STATEMENTS_PROPERTY);
        } else if (node instanceof SwitchCase) {
            return rewriter.getListRewrite(node.getParent(), SwitchStatement.STATEMENTS_PROPERTY);
        }
        throw new IllegalArgumentException("Unsupported body node type: " + node.getClass());
    }
}