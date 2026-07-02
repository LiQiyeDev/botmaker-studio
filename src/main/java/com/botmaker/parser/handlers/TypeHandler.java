package com.botmaker.parser.handlers;

import com.botmaker.parser.ImportManager;
import com.botmaker.parser.NodeCreator;
import com.botmaker.parser.helpers.AstRewriteHelper;
import com.botmaker.project.ProjectState;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.List;

public class TypeHandler {

    public static String replaceVariableType(CompilationUnit cu, String originalCode, VariableDeclarationStatement varDecl,
                                      ResolvedType newType, ProjectState state) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ImportManager.addImport(cu, rewriter, newType.leafType(), state);

        Type newTypeNode = ProjectAnalyzer.createSimpleTypeNode(ast, newType);
        rewriter.replace(varDecl.getType(), newTypeNode, null);

        if (!varDecl.fragments().isEmpty()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) varDecl.fragments().getFirst();
            Expression currentInitializer = fragment.getInitializer();

            // Check old type to see if we can preserve values
            ResolvedType oldType = ProjectAnalyzer.resolveType(varDecl.getType());

            Expression newInitializer = createInitializerForNewType(ast, cu, rewriter, oldType, newType, currentInitializer,state);
            if (newInitializer != null && currentInitializer != null) rewriter.replace(currentInitializer, newInitializer, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    public static String replaceFieldType(CompilationUnit cu, String originalCode, FieldDeclaration fieldDecl,
                                   ResolvedType newType, ProjectState state) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        ImportManager.addImport(cu, rewriter, newType, state);

        Type newTypeNode = ProjectAnalyzer.createSimpleTypeNode(ast, newType);
        rewriter.replace(fieldDecl.getType(), newTypeNode, null);

        if (!fieldDecl.fragments().isEmpty()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) fieldDecl.fragments().getFirst();
            Expression currentInitializer = fragment.getInitializer();
            ResolvedType oldType = ProjectAnalyzer.resolveType(fieldDecl.getType());

            Expression newInitializer = createInitializerForNewType(ast, cu, rewriter, oldType, newType, currentInitializer,state);
            if (newInitializer != null && currentInitializer != null) rewriter.replace(currentInitializer, newInitializer, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    private static Expression createInitializerForNewType(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                                                   ResolvedType oldType, ResolvedType newType,
                                                   Expression currentInitializer, ProjectState state) {
        List<Expression> valuesToPreserve = new ArrayList<>();
        String oldLeaf = oldType.leafType().simpleName();
        String newLeaf = newType.leafType().simpleName();

        if (oldLeaf.equals(newLeaf) && currentInitializer != null) {
            ProjectAnalyzer.collectLeafValues(currentInitializer, valuesToPreserve);
        }

        if (newType.isArray()) {
            return NodeCreator.createRecursiveListInitializer(ast, newType.qualifiedName(), cu, rewriter, valuesToPreserve,state);
        } else {
            return !valuesToPreserve.isEmpty() ?
                    (Expression) ASTNode.copySubtree(ast, valuesToPreserve.getFirst()) :
                    NodeCreator.createDefaultInitializer(ast, newType);
        }
    }
}