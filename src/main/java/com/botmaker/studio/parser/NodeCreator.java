package com.botmaker.studio.parser;

import com.botmaker.studio.parser.factories.ExpressionFactory;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.factories.StatementFactory;
import com.botmaker.studio.parser.handlers.MethodHandler;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;


public class NodeCreator {

    /**
     * Builds an {@link Expression} from any expression-menu {@code selection} — a plain {@link ExpressionType}
     * (literal/operator/default reference) or a richer {@link ExpressionChoice} (method call, constructor,
     * enum constant, variable). The single place that maps a user's menu pick to an AST node, so "set into an
     * empty slot" and "replace an existing expression" share one path. Returns {@code null} if unbuildable.
     */
    public static Expression createExpression(EditContext ctx, Object selection, ResolvedType contextType) {
        AST ast = ctx.ast();
        if (selection instanceof ExpressionType type) {
            return createDefaultExpression(ctx, type, contextType);
        }
        if (selection instanceof ExpressionChoice choice) {
            return switch (choice) {
                case ExpressionChoice.Method m -> MethodHandler.createMethodInvocation(ctx, m);
                case ExpressionChoice.Constructor c -> {
                    ClassInstanceCreation creation = ast.newClassInstanceCreation();
                    creation.setType(ProjectAnalyzer.createTypeNode(ast, ResolvedType.named(c.typeName())));
                    ctx.addImportForSimpleName(c.typeName());
                    for (ResolvedType p : c.paramTypes()) {
                        creation.arguments().add(InitializerFactory.createDefaultInitializer(ctx, p));
                        ctx.addImportForType(p);
                    }
                    yield creation;
                }
                case ExpressionChoice.EnumConstant e -> {
                    ctx.addImportForSimpleName(e.typeName());
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
                  ASTParser p = org.eclipse.jdt.core.dom.ASTParser.newParser(AST.getJLSLatest());
                    p.setKind(org.eclipse.jdt.core.dom.ASTParser.K_EXPRESSION);
                    p.setSource(rx.code().toCharArray());
                  ASTNode parsed = p.createAST(null);
                    yield (parsed instanceof Expression pe) ? (Expression) ASTNode.copySubtree(ast, pe) : ast.newNullLiteral();
                }
            };
        }
        return null;
    }

    public static Expression createDefaultExpression(EditContext ctx, ExpressionType type,
                                                     ResolvedType contextType) {
        return ExpressionFactory.createDefaultExpression(ctx, type, contextType);
    }

    public static Expression createDefaultExpression(EditContext ctx, ExpressionType type, String contextTypeName) {
        return createDefaultExpression(ctx, type, ResolvedType.named(contextTypeName));
    }

    public static Expression createDefaultExpression(EditContext ctx, ExpressionType type) {
        return ExpressionFactory.createDefaultExpression(ctx, type, null);
    }

    /**
     * @param context the AST node the block is being dropped into — the scope whose visible variables and methods
     *                the block's default is seeded from (see {@code StatementFactory}). May be {@code null}, in
     *                which case scope-dependent blocks get empty slots instead of real identifiers.
     */
    public static Statement createDefaultStatement(EditContext ctx, BlockType type, ASTNode context) {
        return StatementFactory.createStatement(ctx, type, context);
    }

    public static Expression createDefaultInitializer(AST ast, ResolvedType type) {
        return InitializerFactory.createDefaultInitializer(ast, type);
    }

    /**
     * The no-context default initializer, for the two paths that have neither an analyzer nor a rewriter
     * (a type swap reusing preserved values, a fresh variable's seed). Everything that <em>does</em> hold a
     * write-path context calls {@link InitializerFactory#createDefaultInitializer(EditContext, ResolvedType)}
     * directly — it seeds a real constructor and the project's default capture target, which this cannot.
     */
}
