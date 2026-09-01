package com.botmaker.studio.parser.handlers;

import com.botmaker.sdk.api.vision.ImageTemplateGroup;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SdkNodes;
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
        mi.setExpression(SdkNodes.name(ast, facade));
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

    /**
     * Switches a lambda-call to a sibling overload/variant — the method dropdown on
     * {@code LambdaCallBlock} (e.g. {@code whileFind ↔ whileFindAny ↔ whileFindAll}). It renames the
     * method, converts the leading image argument single↔group ({@code new ImageTemplate("x")} ↔
     * {@code ImageTemplateGroup.of(new ImageTemplate("x"))}), and adds, removes or <em>renames</em> the
     * lambda's parameter to match the target's shape — {@code Consumer<MatchResult>} for the single-template
     * forms, {@code Consumer<Matches>} for the group forms, {@code Runnable} for {@code untilFind…}.
     *
     * <p>The rename matters because the parameter's type changes with the variant: switching
     * {@code whileFind} → {@code whileFindAny} turns a {@code MatchResult match} into a {@code Matches found},
     * and a stale name would read as the wrong thing in the body.
     *
     * @param group      the target takes an {@code ImageTemplateGroup} (an {@code …Any}/{@code …All} variant)
     * @param lambdaParam the name the target's body receives the value under ({@code found -> {}}); {@code null}
     *                    or blank for a bare {@code () -> {}} ({@link Runnable} target)
     */
    public static void switchVariant(EditContext ctx, MethodInvocation mi, String newMethod, boolean group,
                                     String lambdaParam) {
        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();
        rewriter.set(mi, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(newMethod), null);

        List<?> args = mi.arguments();
        Expression leading = args.size() >= 2 ? (Expression) args.get(0) : null;
        if (leading != null) {
            Expression converted = convertLeading(ast, leading, group);
            if (converted != null) {
                rewriter.replace(leading, converted, null);
                if (group) {
                    ctx.addImport(ImageTemplateGroup.class);
                }
            }
        }

        LambdaExpression lambda = lambdaArg(mi);
        if (lambda != null) {
            // The third argument was a seeded body until 2026-09-01: a group form was born holding a guarded
            // switch over the value it hands the body. Nothing seeds a body now — the thing it seeded is
            // deleted, and a chain of branches is a call the palette offers like any other member — so the
            // body is only ever carried across, never replaced.
            adjustLambdaParam(ast, rewriter, lambda, lambdaParam, null);
        }
    }

    // seedIfReady, seededBody and firstTemplatePath stood here until 2026-09-01, and went with the guarded
    // switch they seeded. Together they were the whole "a group form is born holding a combination switch"
    // feature: two entry points, because the user can pick the method first or the pictures first, and either
    // order had to be watched for. What they wrote was a language construct assembled out of one library's
    // vocabulary — Matches, ImageTemplate, the generated Templates class, a pattern variable and a mandatory
    // default rule — which is what made this handler need three SDK imports to switch a method name.
    //
    // Nothing seeds a body now. A group form is born with an empty one, and branching on what it found is a
    // call the palette offers like any other member (see BranchChainHandler for how one is drawn).

    /** Wrap a single image into {@code ImageTemplateGroup.of(...)} or unwrap the group's first element; null = leave as-is. */
    private static Expression convertLeading(AST ast, Expression leading, boolean group) {
        boolean isGroupCall = leading instanceof MethodInvocation gm
                && SdkNodes.isCallOn(gm, ImageTemplateGroup.class)
                && "of".equals(gm.getName().getIdentifier());
        if (group) {
            if (isGroupCall) return null; // already a group — nothing to convert
            MethodInvocation of = ast.newMethodInvocation();
            of.setExpression(SdkNodes.name(ast, ImageTemplateGroup.class));
            of.setName(ast.newSimpleName("of"));
            of.arguments().add(ASTNode.copySubtree(ast, leading));
            return of;
        }
        // group -> single: unwrap the first template of an ImageTemplateGroup.of(...) literal, else leave.
        if (isGroupCall) {
            List<?> ofArgs = ((MethodInvocation) leading).arguments();
            if (!ofArgs.isEmpty()) {
                return (Expression) ASTNode.copySubtree(ast, (Expression) ofArgs.get(0));
            }
        }
        return null;
    }

    /**
     * Adds, removes or renames the single lambda parameter so the body shape matches the target variant.
     * A rename rewrites the declaration only — references in the body are left alone here, because
     * {@code switchVariant} also changes the parameter's <em>type</em>, so a body written against the old
     * value has to be revisited by the user anyway; {@code AstRewriteHelper.renameLambdaParameter} is the
     * path for a pure rename, and it does carry the references.
     *
     * <p><b>A parameter-count change replaces the whole lambda rather than editing it in place.</b> Editing it
     * meant pairing a {@code ListRewrite} on {@code PARAMETERS_PROPERTY} with a {@code PARENTHESES_PROPERTY}
     * flip, and JDT's rewriter cannot do both at once: it scans for the parameter list's tokens at offsets the
     * parentheses change invalidates, and threw {@code "Document does not match the AST"} off the end of the
     * file. {@code AstRewriteHelper.applyRewrite} catches that and keeps the original source, which is why
     * {@code untilFindAll → ifFindAll} appeared to do nothing at all rather than to fail. A fresh
     * {@link LambdaExpression} with the parameters and parentheses already right, its body carried over by
     * {@code copySubtree}, sidesteps the property entirely. The in-place path stays for a pure rename, where
     * there is no parenthesis change and it works.
     */
    private static void adjustLambdaParam(AST ast, ASTRewrite rewriter, LambdaExpression lambda, String wantName,
                                          Block seededBody) {
        boolean wantParam = wantName != null && !wantName.isBlank();
        List<?> params = lambda.parameters();
        boolean hasParam = !params.isEmpty();

        if (wantParam == hasParam) {
            if (wantParam) {
                SimpleName declared = declaredName(params.get(0));
                if (declared != null && !declared.getIdentifier().equals(wantName)) {
                    rewriter.set(declared, SimpleName.IDENTIFIER_PROPERTY, wantName, null);
                }
            }
            // The parameter list is unchanged, so the body can be swapped on its own — and it is only ever
            // swapped when it was empty, so this replaces nothing the user wrote.
            if (seededBody != null) rewriter.replace(lambda.getBody(), seededBody, null);
            return;
        }

        LambdaExpression replacement = ast.newLambdaExpression();
        if (wantParam) {
            VariableDeclarationFragment p = ast.newVariableDeclarationFragment();
            p.setName(ast.newSimpleName(wantName));
            replacement.parameters().add(p);
        }
        replacement.setParentheses(!wantParam);   // found -> {} versus () -> {}
        // createCopyTarget, not copySubtree: it moves the body's ORIGINAL SOURCE TEXT across, so the user's
        // statements keep their formatting, comments and indentation. A copied subtree would be re-printed by
        // the rewriter, reformatting a body the user never touched. A seeded body replaces it outright, which
        // is only ever offered for an empty one.
        replacement.setBody(seededBody != null ? seededBody : (Block) rewriter.createCopyTarget(lambda.getBody()));
        rewriter.replace(lambda, replacement, null);
    }

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
