package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.NodeCreator;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.List;

public class TypeHandler {

    public static String replaceVariableType(EditContext ctx, String originalCode,
                                             VariableDeclarationStatement varDecl, ResolvedType newType) {
        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();

        ctx.addImport(newType.leafType());

        Type newTypeNode = ProjectAnalyzer.createSimpleTypeNode(ast, newType);
        rewriter.replace(varDecl.getType(), newTypeNode, null);

        if (!varDecl.fragments().isEmpty()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) varDecl.fragments().getFirst();
            Expression currentInitializer = fragment.getInitializer();

            // Check old type to see if we can preserve values
            ResolvedType oldType = ProjectAnalyzer.resolveType(varDecl.getType());

            Expression newInitializer = createInitializerForNewType(ctx, oldType, newType, currentInitializer);
            if (newInitializer != null && currentInitializer != null) rewriter.replace(currentInitializer, newInitializer, null);
        }
        return ctx.applyTo(originalCode);
    }

    public static String replaceFieldType(EditContext ctx, String originalCode, FieldDeclaration fieldDecl,
                                          ResolvedType newType) {
        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();

        ctx.addImport(newType);

        Type newTypeNode = ProjectAnalyzer.createSimpleTypeNode(ast, newType);
        rewriter.replace(fieldDecl.getType(), newTypeNode, null);

        if (!fieldDecl.fragments().isEmpty()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) fieldDecl.fragments().getFirst();
            Expression currentInitializer = fragment.getInitializer();
            ResolvedType oldType = ProjectAnalyzer.resolveType(fieldDecl.getType());

            Expression newInitializer = createInitializerForNewType(ctx, oldType, newType, currentInitializer);
            if (newInitializer != null && currentInitializer != null) rewriter.replace(currentInitializer, newInitializer, null);
        }
        return ctx.applyTo(originalCode);
    }

    private static Expression createInitializerForNewType(EditContext ctx, ResolvedType oldType,
                                                          ResolvedType newType, Expression currentInitializer) {
        AST ast = ctx.ast();
        List<Expression> valuesToPreserve = new ArrayList<>();
        String oldLeaf = oldType.leafType().simpleName();
        String newLeaf = newType.leafType().simpleName();

        if (oldLeaf.equals(newLeaf) && currentInitializer != null) {
            ProjectAnalyzer.collectLeafValues(currentInitializer, valuesToPreserve);
        }

        if (newType.isArray()) {
            return InitializerFactory.createRecursiveListInitializer(
                    ast, newType.qualifiedName(), ctx.cu(), valuesToPreserve, ctx.state());
        } else {
            return !valuesToPreserve.isEmpty() ?
                    (Expression) ASTNode.copySubtree(ast, valuesToPreserve.getFirst()) :
                    NodeCreator.createDefaultInitializer(ast, newType);
        }
    }
}
