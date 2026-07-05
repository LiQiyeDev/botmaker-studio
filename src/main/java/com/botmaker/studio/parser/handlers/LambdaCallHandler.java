package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
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

        List<String> params = (lambdaParam != null && !lambdaParam.isBlank()) ? List.of(lambdaParam) : List.of();
        mi.arguments().add(emptyBlockLambda(ast, params));
        return mi;
    }

    /**
     * An empty block-bodied lambda ({@code param -> {}} / {@code (a, b) -> {}} / {@code () -> {}}) with the given
     * named parameters. Block-bodied so it round-trips into a droppable {@code BodyBlock} (see
     * {@code BlockConverter.parseLambdaCall}). Shared by {@link #buildLambdaCall} and the functional-interface
     * default-argument path in {@code InitializerFactory}.
     */
    public static LambdaExpression emptyBlockLambda(AST ast, List<String> paramNames) {
        LambdaExpression lambda = ast.newLambdaExpression();
        for (String name : paramNames) {
            VariableDeclarationFragment param = ast.newVariableDeclarationFragment();
            param.setName(ast.newSimpleName(name));
            lambda.parameters().add(param);
        }
        // A single unparenthesised parameter reads best (m -> {}); zero or multiple params require parentheses.
        lambda.setParentheses(paramNames.size() != 1);
        lambda.setBody(ast.newBlock());
        return lambda;
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
