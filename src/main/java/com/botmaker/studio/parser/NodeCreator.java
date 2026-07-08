package com.botmaker.studio.parser;

import com.botmaker.studio.parser.factories.ExpressionFactory;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.factories.StatementFactory;
import com.botmaker.studio.parser.handlers.MethodHandler;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
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
                // NewVariable is handled specially (declaration + reference) in applyExpressionSelection and
                // does not flow through here; yield just the reference as a safe fallback.
                case ExpressionChoice.NewVariable nv -> ast.newSimpleName(nv.name());
                // A ready-made snippet (e.g. a capture-source helper call): parse and copy into this AST.
                case ExpressionChoice.RawExpression rx -> {
                    org.eclipse.jdt.core.dom.ASTParser p = org.eclipse.jdt.core.dom.ASTParser.newParser(AST.getJLSLatest());
                    p.setKind(org.eclipse.jdt.core.dom.ASTParser.K_EXPRESSION);
                    p.setSource(rx.code().toCharArray());
                    org.eclipse.jdt.core.dom.ASTNode parsed = p.createAST(null);
                    yield (parsed instanceof Expression pe) ? (Expression) ASTNode.copySubtree(ast, pe) : ast.newNullLiteral();
                }
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

    /**
     * State-aware default initializer: passes the project's {@link ProjectState} through so a
     * {@code CaptureSource} slot is seeded from the project's default capture target (see
     * {@code InitializerFactory}) rather than falling back to the whole desktop. Used by the argument-sync
     * and method-creation paths (overload switch, palette insert) where {@code state} is reachable.
     */
    public static Expression createDefaultInitializer(AST ast, ResolvedType type, CompilationUnit cu, ProjectState state) {
        return InitializerFactory.createDefaultInitializer(ast, type, cu, state);
    }

    public static Expression createRecursiveListInitializer(AST ast, String typeName, CompilationUnit cu,
                                                            ASTRewrite rewriter, List<Expression> leavesToPreserve, ProjectState state) {
        return InitializerFactory.createRecursiveListInitializer(ast, typeName, cu, rewriter, leavesToPreserve, state);
    }
}