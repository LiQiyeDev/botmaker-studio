// FILE: rs\bgroi\Documents\dev\IntellijProjects\BotMaker\src\main\java\com\botmaker\parser\NodeCreator.java
package com.botmaker.parser;

import com.botmaker.parser.factories.ExpressionFactory;
import com.botmaker.parser.factories.InitializerFactory;
import com.botmaker.parser.factories.StatementFactory;
import com.botmaker.parser.handlers.MethodHandler;
import com.botmaker.project.ProjectState;
import com.botmaker.palette.BlockType;
import com.botmaker.palette.ExpressionType;
import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

public class NodeCreator {

    /**
     * Builds an {@link Expression} from any expression-menu {@code selection} — a plain {@link ExpressionType}
     * (literal/operator/default reference) or a richer {@link ExpressionChoice} (method call, constructor,
     * enum constant, variable). The single place that maps a user's menu pick to an AST node, so "set into an
     * empty slot" and "replace an existing expression" share one path. Returns {@code null} if unbuildable.
     */
    public static Expression createExpression(AST ast, Object selection, CompilationUnit cu,
                                              ASTRewrite rewriter, ResolvedType contextType) {
        if (selection instanceof ExpressionType type) {
            return createDefaultExpression(ast, type, cu, rewriter, contextType);
        }
        if (selection instanceof ExpressionChoice choice) {
            return switch (choice) {
                case ExpressionChoice.Method m -> MethodHandler.createMethodInvocation(ast, m);
                case ExpressionChoice.Constructor c -> {
                    ClassInstanceCreation creation = ast.newClassInstanceCreation();
                    creation.setType(ProjectAnalyzer.createTypeNode(ast, ResolvedType.named(c.typeName())));
                    for (ResolvedType p : c.paramTypes()) creation.arguments().add(createDefaultInitializer(ast, p));
                    yield creation;
                }
                case ExpressionChoice.EnumConstant e ->
                        ast.newQualifiedName(ast.newSimpleName(e.typeName()), ast.newSimpleName(e.constantName()));
                case ExpressionChoice.Variable v -> ast.newSimpleName(v.variableName());
                case ExpressionChoice.Field f -> f.scope() == null || f.scope().isEmpty()
                        ? ast.newSimpleName(f.fieldName())
                        : ast.newQualifiedName(ast.newName(f.scope()), ast.newSimpleName(f.fieldName()));
            };
        }
        return null;
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                              ASTRewrite rewriter, ResolvedType contextType) {
        return ExpressionFactory.createDefaultExpression(ast, type, cu, rewriter, contextType);
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                                     ASTRewrite rewriter, String contextTypeName) {
        return createDefaultExpression(ast, type, cu, rewriter, ResolvedType.named(contextTypeName));
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                                     ASTRewrite rewriter) {
        return ExpressionFactory.createDefaultExpression(ast, type, cu, rewriter, null);
    }

    public static Statement createDefaultStatement(AST ast, BlockType type, CompilationUnit cu, ASTRewrite rewriter, ProjectState state) {
        return StatementFactory.createStatement(ast, type, cu, rewriter, state);
    }

    public static Expression createDefaultInitializer(AST ast, ResolvedType type) {
        return InitializerFactory.createDefaultInitializer(ast, type);
    }

    public static Expression createRecursiveListInitializer(AST ast, String typeName, CompilationUnit cu,
                                                            ASTRewrite rewriter, List<Expression> leavesToPreserve, ProjectState state) {
        return InitializerFactory.createRecursiveListInitializer(ast, typeName, cu, rewriter, leavesToPreserve, state);
    }
}