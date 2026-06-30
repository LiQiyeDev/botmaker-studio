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
                                              ASTRewrite rewriter, ResolvedType contextType,
                                              ProjectAnalyzer analyzer) {
        if (selection instanceof ExpressionType type) {
            return createDefaultExpression(ast, type, cu, rewriter, contextType, analyzer);
        }
        if (selection instanceof ExpressionChoice choice) {
            return switch (choice) {
                case ExpressionChoice.Method m -> MethodHandler.createMethodInvocation(ast, m, cu, rewriter, analyzer);
                case ExpressionChoice.Constructor c -> {
                    ClassInstanceCreation creation = ast.newClassInstanceCreation();
                    creation.setType(ProjectAnalyzer.createTypeNode(ast, ResolvedType.named(c.typeName())));
                    ImportManager.addImportForSimpleName(cu, rewriter, c.typeName(), analyzer, null);
                    for (ResolvedType p : c.paramTypes()) creation.arguments().add(createDefaultInitializer(ast, p));
                    yield creation;
                }
                case ExpressionChoice.EnumConstant e -> {
                    ImportManager.addImportForSimpleName(cu, rewriter, e.typeName(), analyzer, null);
                    yield ast.newQualifiedName(ast.newSimpleName(e.typeName()), ast.newSimpleName(e.constantName()));
                }
                case ExpressionChoice.Variable v -> ast.newSimpleName(v.variableName());
                case ExpressionChoice.Field f -> f.scope() == null || f.scope().isEmpty()
                        ? ast.newSimpleName(f.fieldName())
                        : ast.newQualifiedName(ast.newName(f.scope()), ast.newSimpleName(f.fieldName()));
            };
        }
        return null;
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                              ASTRewrite rewriter, ResolvedType contextType, ProjectAnalyzer analyzer) {
        return ExpressionFactory.createDefaultExpression(ast, type, cu, rewriter, contextType, analyzer);
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                                     ASTRewrite rewriter, String contextTypeName, ProjectAnalyzer analyzer) {
        return createDefaultExpression(ast, type, cu, rewriter, ResolvedType.named(contextTypeName), analyzer);
    }

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                                     ASTRewrite rewriter) {
        return ExpressionFactory.createDefaultExpression(ast, type, cu, rewriter, null, null);
    }

    public static Statement createDefaultStatement(AST ast, BlockType type, CompilationUnit cu, ASTRewrite rewriter,
                                                   ProjectState state, ProjectAnalyzer analyzer) {
        return StatementFactory.createStatement(ast, type, cu, rewriter, state, analyzer);
    }

    public static Expression createDefaultInitializer(AST ast, ResolvedType type) {
        return InitializerFactory.createDefaultInitializer(ast, type);
    }

    public static Expression createRecursiveListInitializer(AST ast, String typeName, CompilationUnit cu,
                                                            ASTRewrite rewriter, List<Expression> leavesToPreserve, ProjectState state) {
        return InitializerFactory.createRecursiveListInitializer(ast, typeName, cu, rewriter, leavesToPreserve, state);
    }
}