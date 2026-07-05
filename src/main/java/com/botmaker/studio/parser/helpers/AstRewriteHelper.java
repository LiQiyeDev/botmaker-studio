package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
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