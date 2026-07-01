package com.botmaker.parser.handlers;

import com.botmaker.parser.ImportManager;
import com.botmaker.parser.NodeCreator;
import com.botmaker.parser.helpers.AstRewriteHelper;
import com.botmaker.palette.ExpressionType;
import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

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

    /**
     * Inserts an element built from a menu {@code selection} (a {@link ExpressionType} or a
     * {@link com.botmaker.parser.ExpressionChoice}) at {@code insertIndex}. Unlike {@link #addElementToList},
     * which only ever inserts a bare placeholder, this reuses the shared
     * {@link NodeCreator#createExpression} builder so the list's "+" menu can offer the same type-aware
     * picks (variable / method / constructor / enum constant) the replace menu does.
     */
    public static String insertChoiceIntoList(CompilationUnit cu, String originalCode, ASTNode listNode,
                                              int insertIndex, Object selection, ResolvedType contextType,
                                              ProjectAnalyzer analyzer) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        Expression newElement = NodeCreator.createExpression(ast, selection, cu, rewriter, contextType, analyzer);
        if (newElement == null) return originalCode;

        insertElement(rewriter, listNode, newElement, insertIndex);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** Moves the element at {@code fromIndex} to {@code toIndex} within the list, preserving its node. */
    public static String moveElement(CompilationUnit cu, String originalCode, ASTNode listNode,
                                     int fromIndex, int toIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ListTarget target = resolveListTarget(listNode);
        if (target == null) return originalCode;

        int size = target.expressions().size();
        if (fromIndex < 0 || fromIndex >= size || toIndex < 0 || toIndex >= size || fromIndex == toIndex) {
            return originalCode;
        }

        ASTNode moving = (ASTNode) target.expressions().get(fromIndex);
        ListRewrite lr = rewriter.getListRewrite(target.node(), target.property());
        // createMoveTarget removes the node from its original slot; insertAt places the move target at toIndex.
        lr.insertAt(rewriter.createMoveTarget(moving), toIndex, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** Inserts {@code newElement} at {@code insertIndex} into whichever list shape {@code listNode} is. */
    private static void insertElement(ASTRewrite rewriter, ASTNode listNode, Expression newElement, int insertIndex) {
        ListTarget target = resolveListTarget(listNode);
        if (target == null) return;
        rewriter.getListRewrite(target.node(), target.property()).insertAt(newElement, insertIndex, null);
    }

    /**
     * Deletes an element from a list structure at the specified index.
     */
    public static String deleteElementFromList(CompilationUnit cu, String originalCode,
                                               ASTNode listNode, int elementIndex) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ListTarget target = resolveListTarget(listNode);
        if (target == null) return originalCode;

        if (elementIndex >= 0 && elementIndex < target.expressions().size()) {
            rewriter.getListRewrite(target.node(), target.property())
                    .remove((ASTNode) target.expressions().get(elementIndex), null);
        }

        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /** The AST node + child-list property + current element list for one of the three supported list shapes. */
    private record ListTarget(ASTNode node, ChildListPropertyDescriptor property, List<?> expressions) {}

    /** Resolves {@code listNode} (array initializer / {@code List.of(...)} / {@code new ArrayList<>(List.of(...))}) to its element list, or {@code null} if unrecognised. */
    private static ListTarget resolveListTarget(ASTNode listNode) {
        if (listNode instanceof ArrayInitializer ai) {
            return new ListTarget(ai, ArrayInitializer.EXPRESSIONS_PROPERTY, ai.expressions());
        }
        if (listNode instanceof MethodInvocation mi) {
            return new ListTarget(mi, MethodInvocation.ARGUMENTS_PROPERTY, mi.arguments());
        }
        if (listNode instanceof ClassInstanceCreation cic
                && !cic.arguments().isEmpty()
                && cic.arguments().getFirst() instanceof MethodInvocation inner) {
            return new ListTarget(inner, MethodInvocation.ARGUMENTS_PROPERTY, inner.arguments());
        }
        return null;
    }
}