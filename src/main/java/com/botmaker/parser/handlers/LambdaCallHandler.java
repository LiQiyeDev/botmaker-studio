package com.botmaker.parser.handlers;

import com.botmaker.parser.ImportManager;
import com.botmaker.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * Builds and recognises a "static call whose trailing argument is a body lambda" —
 * {@code Class.method(leadingArgs…, param -> { … })}. This is the single place a
 * {@link LambdaExpression} argument is <em>constructed</em> (codegen) or <em>decoded</em>
 * (round-trip parse), so any facade method taking a functional-interface body (today the
 * {@code ImageFinder.whileExists/ifExists/untilExists} vision helpers) reuses it without new machinery.
 *
 * <p>Stateless: every input is a parameter, matching the {@code OperatorReplacementHandler} /
 * {@code EnumManipulationHandler} convention.
 */
public final class LambdaCallHandler {

    private LambdaCallHandler() {}

    /**
     * Assembles {@code Class.method(leadingArgs…, param -> {})}. {@code lambdaParam} names the single
     * inferred-type lambda parameter (rendered {@code param -> {}}); a {@code null}/blank value yields a
     * no-arg {@code () -> {}} (for a {@link Runnable} target). The lambda body is an empty {@link Block},
     * which the round-trip ({@code BlockConverter}) turns into a droppable body — the same {@code newBlock()}
     * while/if loops use. The {@code className} import is added via {@link ImportManager}.
     */
    public static MethodInvocation buildLambdaCall(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                                                   ProjectAnalyzer analyzer, String className, String method,
                                                   List<Expression> leadingArgs, String lambdaParam) {
        MethodInvocation mi = ast.newMethodInvocation();
        mi.setExpression(ast.newSimpleName(className));
        mi.setName(ast.newSimpleName(method));
        ImportManager.addImportForSimpleName(cu, rewriter, className, analyzer, null);

        for (Expression arg : leadingArgs) {
            mi.arguments().add(arg);
        }

        LambdaExpression lambda = ast.newLambdaExpression();
        if (lambdaParam != null && !lambdaParam.isBlank()) {
            VariableDeclarationFragment param = ast.newVariableDeclarationFragment();
            param.setName(ast.newSimpleName(lambdaParam));
            lambda.parameters().add(param);
            lambda.setParentheses(false);
        } else {
            lambda.setParentheses(true);
        }
        lambda.setBody(ast.newBlock());
        mi.arguments().add(lambda);
        return mi;
    }

    /** True when {@code mi}'s last argument is a lambda with a {@code { … }} block body. */
    public static boolean isLambdaCall(MethodInvocation mi) {
        return lambdaArg(mi) != null;
    }

    /** The trailing block-bodied {@link LambdaExpression}, or {@code null} if the call has none. */
    public static LambdaExpression lambdaArg(MethodInvocation mi) {
        List<?> args = mi.arguments();
        if (args.isEmpty()) return null;
        if (args.get(args.size() - 1) instanceof LambdaExpression lambda && lambda.getBody() instanceof Block) {
            return lambda;
        }
        return null;
    }
}
