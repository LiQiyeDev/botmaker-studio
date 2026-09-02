package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.List;

/**
 * Builds and recognises a "static call whose trailing argument is a body lambda" —
 * {@code Class.method(leadingArgs…, param -> { … })}. This is the single place a
 * {@link LambdaExpression} argument is <em>constructed</em> (codegen) or <em>decoded</em>
 * (round-trip parse), so any facade method taking a functional-interface body (today the
 * {@code ImageFinder.whileFind/ifFind/untilFind} vision helpers) reuses it without new machinery.
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
    public static MethodInvocation buildLambdaCall(EditContext ctx, Class<?> facade, String method,
                                                   List<Expression> leadingArgs, String lambdaParam) {
        AST ast = ctx.ast();
        MethodInvocation mi = ast.newMethodInvocation();
        mi.setExpression(ast.newSimpleName(facade.getSimpleName()));
        mi.setName(ast.newSimpleName(method));
        ctx.addImport(facade);

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

    // switchVariant, convertLeading and adjustLambdaParam went on 2026-09-01, with the block that was their
    // only caller. They implemented the method dropdown on the old LambdaCallBlock: rename the method, convert
    // the leading argument between `new ImageTemplate("x")` and `ImageTemplateGroup.of(...)`, and add, remove
    // or rename the lambda's parameter so a body written for a MatchResult was not left named after one when
    // it now received a Matches. Every one of those rules is a fact about one library's nine vision helpers,
    // and spelling ImageTemplateGroup here is what made a generic "build and read a call with a body lambda"
    // handler carry an SDK import.
    //
    // What is left is the part that really was generic, and BlockConverter and BranchChainHandler both use it:
    // assemble a call with a trailing block lambda, and recognise one.
    //
    // Two things learned the hard way are recorded here rather than lost with the code, because the next
    // person to edit a LambdaExpression in place will meet both. A parameter-count change had to REPLACE the
    // whole lambda rather than edit it: pairing a ListRewrite on PARAMETERS_PROPERTY with a
    // PARENTHESES_PROPERTY flip made JDT scan for tokens at offsets the parentheses change had invalidated,
    // and it threw "Document does not match the AST" off the end of the file — which AstRewriteHelper.
    // applyRewrite catches, so the edit appeared to do nothing at all rather than to fail. And a body carried
    // across must move by createCopyTarget, not copySubtree: the first moves the original source text, the
    // second re-prints it and reformats statements the user never touched.
    // AstRewriteHelper.renameLambdaParameter is the surviving path for a pure rename, and it carries the
    // body's references, which switchVariant deliberately did not.


    /** The {@link SimpleName} a lambda parameter declares, inferred ({@code found}) or explicit ({@code Matches found}). */
    public static SimpleName declaredName(Object parameter) {
        return switch (parameter) {
            case VariableDeclarationFragment frag -> frag.getName();
            case SingleVariableDeclaration svd -> svd.getName();
            default -> null;
        };
    }

    /** The {@link SimpleName} declared by {@code mi}'s trailing lambda, or {@code null} if it has no parameter. */
    public static SimpleName lambdaParamName(MethodInvocation mi) {
        LambdaExpression lambda = lambdaArg(mi);
        if (lambda == null || lambda.parameters().isEmpty()) return null;
        return declaredName(lambda.parameters().get(0));
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
