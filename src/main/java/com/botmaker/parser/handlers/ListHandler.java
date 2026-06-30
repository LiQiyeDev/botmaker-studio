package com.botmaker.parser.handlers;

import com.botmaker.parser.ImportManager;
import com.botmaker.parser.NodeCreator;
import com.botmaker.parser.helpers.AstRewriteHelper;
import com.botmaker.palette.ExpressionType;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * Handles operations on lists and arrays.
 */
public class ListHandler {

    /**
     * Adds an element to a list structure at the specified index.
     */
    public static String addElementToList(CompilationUnit cu, String originalCode,
                                          ASTNode listNode, ExpressionType type, int insertIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        Expression newElement = NodeCreator.createDefaultExpression(ast, type, cu, rewriter);
        if (newElement == null) return originalCode;

        insertElement(rewriter, listNode, newElement, insertIndex);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Inserts a {@code new ImageTemplate("")} element into the list — the list-aware counterpart of the
     * inline image-template picker. The empty path opens the per-element picker on the freshly added element.
     */
    public static String addImageTemplateElement(CompilationUnit cu, String originalCode,
                                                 ASTNode listNode, int insertIndex, ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(ast.newSimpleType(ast.newSimpleName("ImageTemplate")));
        StringLiteral lit = ast.newStringLiteral();
        lit.setLiteralValue("");
        cic.arguments().add(lit);
        ImportManager.addImportForSimpleName(cu, rewriter, "ImageTemplate", analyzer, null);

        insertElement(rewriter, listNode, cic, insertIndex);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** Inserts {@code newElement} at {@code insertIndex} into whichever list shape {@code listNode} is. */
    private static void insertElement(ASTRewrite rewriter, ASTNode listNode, Expression newElement, int insertIndex) {
        if (listNode instanceof ArrayInitializer) {
            rewriter.getListRewrite(listNode, ArrayInitializer.EXPRESSIONS_PROPERTY)
                    .insertAt(newElement, insertIndex, null);
        } else if (listNode instanceof MethodInvocation) {
            rewriter.getListRewrite(listNode, MethodInvocation.ARGUMENTS_PROPERTY)
                    .insertAt(newElement, insertIndex, null);
        } else if (listNode instanceof ClassInstanceCreation) {
            ClassInstanceCreation cic = (ClassInstanceCreation) listNode;
            if (!cic.arguments().isEmpty() && cic.arguments().getFirst() instanceof MethodInvocation) {
                MethodInvocation mi = (MethodInvocation) cic.arguments().getFirst();
                rewriter.getListRewrite(mi, MethodInvocation.ARGUMENTS_PROPERTY)
                        .insertAt(newElement, insertIndex, null);
            }
        }
    }

    /**
     * Deletes an element from a list structure at the specified index.
     */
    public static String deleteElementFromList(CompilationUnit cu, String originalCode,
                                               ASTNode listNode, int elementIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        List<?> expressions;
        ChildListPropertyDescriptor property;
        ASTNode targetNode = listNode;

        if (listNode instanceof ClassInstanceCreation) {
            ClassInstanceCreation cic = (ClassInstanceCreation) listNode;
            if (!cic.arguments().isEmpty() && cic.arguments().getFirst() instanceof MethodInvocation) {
                MethodInvocation mi = (MethodInvocation) cic.arguments().getFirst();
                targetNode = mi;
                expressions = mi.arguments();
                property = MethodInvocation.ARGUMENTS_PROPERTY;
            } else {
                return originalCode;
            }
        } else if (listNode instanceof ArrayInitializer) {
            expressions = ((ArrayInitializer) listNode).expressions();
            property = ArrayInitializer.EXPRESSIONS_PROPERTY;
        } else if (listNode instanceof MethodInvocation) {
            expressions = ((MethodInvocation) listNode).arguments();
            property = MethodInvocation.ARGUMENTS_PROPERTY;
        } else {
            return originalCode;
        }

        if (elementIndex >= 0 && elementIndex < expressions.size()) {
            rewriter.getListRewrite(targetNode, property)
                    .remove((ASTNode) expressions.get(elementIndex), null);
        }

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}