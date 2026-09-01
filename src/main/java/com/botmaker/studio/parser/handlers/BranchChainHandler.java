package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes a <b>branch chain</b> — {@code subject.when(x -> test, () -> { … }).otherwise(() -> { … })}
 * — the shape that replaced the guarded {@code switch} the editor used to compose.
 *
 * <h2>Why this is structural and names nobody's type</h2>
 *
 * <p>Its predecessor, {@code MatchesSwitchHandler}, spelled one library's vocabulary on its behalf: the type
 * {@code Matches}, the pattern variable, the guard's method, the mandatory {@code default}. It had to, because
 * a {@code switch} is a <em>language construct</em> and there is no way to compose one out of a catalogue of
 * methods. A chain is made of ordinary calls, so nothing here needs a type name, a method name or a binding —
 * the shape alone says what it is:
 *
 * <ul>
 *   <li>a <b>link</b> is a {@link MethodInvocation} whose arguments are <em>all</em> lambdas;</li>
 *   <li>its trailing block-bodied lambda ({@code () -> { … }}) is the <b>body</b>;</li>
 *   <li>any expression-bodied lambda before it ({@code x -> test}) is a <b>condition</b>;</li>
 *   <li>a link with no condition is <b>terminal</b> — the {@code else} of the chain;</li>
 *   <li>links nest leftward through {@link MethodInvocation#getExpression()} down to the <b>subject</b>, which
 *       is whatever the chain is called on and is not read further.</li>
 * </ul>
 *
 * <p><b>A new link copies the method name of an existing non-terminal one.</b> That is the whole reason no
 * library name appears here: the chain already says what its branch method is called, so the editor never has
 * to know. A chain that has only a terminal link cannot say, and {@link #addLink} declines rather than
 * guessing — see {@link #branchMethodName}.
 *
 * <p><b>At least one condition is required</b> ({@link #isBranchChain}), which is what keeps an ordinary
 * one-lambda call such as {@code runner.submit(() -> { … })} rendering as the method-invocation block it
 * always did. Without that clause every such call would become a one-link chain with nothing to branch on.
 *
 * <p>Stateless, like {@link LambdaCallHandler} and the other handlers in this package.
 */
public final class BranchChainHandler {

    private BranchChainHandler() {}

    /**
     * One link of a chain: the call itself, the conditions it tests and the body it runs.
     *
     * @param call      the invocation this link is, so an edit can address it
     * @param method    its method name — {@code when}, {@code otherwise}, or whatever a plugin calls them
     * @param conditions the expression-bodied lambdas, in argument order; empty for a terminal link
     * @param body      the trailing block-bodied lambda's block, never null for a link that parsed
     */
    public record Link(MethodInvocation call, String method, List<LambdaExpression> conditions, Block body) {

        public Link {
            conditions = List.copyOf(conditions);
        }

        /** A link with nothing to test is the chain's fallback — its {@code else}. */
        public boolean isTerminal() {
            return conditions.isEmpty();
        }

        /**
         * The first condition's <em>body expression</em> — what the user edits.
         *
         * <p>The lambda's parameter is not offered: it names the value the chain is over, which the subject
         * already said, and renaming it would rename it in one branch out of several.
         */
        public Expression conditionExpression() {
            return conditions.isEmpty() ? null : bodyExpression(conditions.getFirst());
        }

        /** The name the first condition's lambda declares — {@code m} — or null when it has none. */
        public String conditionParam() {
            return conditions.isEmpty() ? null : declaredParam(conditions.getFirst());
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------

    /**
     * Whether {@code mi} is the outermost call of a branch chain worth drawing as one.
     *
     * <p>Deliberately not the same question as "could this be a link": a single terminal link is a lambda
     * call like any other, and only a chain that actually branches earns the branching UI.
     */
    public static boolean isBranchChain(MethodInvocation mi) {
        List<Link> links = read(mi);
        return links.size() >= 1 && links.stream().anyMatch(link -> !link.isTerminal());
    }

    /**
     * The chain {@code mi} ends, in source order (leftmost link first), or an empty list when it is not one.
     *
     * <p>Unwinds the receiver chain leftward and reverses, so the list reads the way the user wrote it. The
     * walk stops at the first receiver that is not itself a link; that receiver is the subject.
     */
    public static List<Link> read(MethodInvocation mi) {
        List<Link> reversed = new ArrayList<>();
        Expression cursor = mi;
        while (cursor instanceof MethodInvocation call) {
            Link link = linkOf(call);
            if (link == null) break;
            reversed.add(link);
            cursor = call.getExpression();
        }
        if (reversed.isEmpty() || cursor == null) return List.of();

        List<Link> ordered = new ArrayList<>(reversed);
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    /** What the chain is called on, as source text — {@code found}. Empty when {@code mi} is not a chain. */
    public static String subjectOf(MethodInvocation mi) {
        Expression cursor = mi;
        while (cursor instanceof MethodInvocation call && linkOf(call) != null) {
            cursor = call.getExpression();
        }
        return cursor == null ? "" : cursor.toString();
    }

    /**
     * {@code call} as a link, or null when its shape is not one: every argument must be a lambda, the last of
     * them must have a {@code { … }} block body, and there must be a receiver for the chain to hang off.
     */
    private static Link linkOf(MethodInvocation call) {
        List<?> args = call.arguments();
        if (args.isEmpty() || call.getExpression() == null) return null;

        List<LambdaExpression> lambdas = new ArrayList<>();
        for (Object arg : args) {
            if (!(arg instanceof LambdaExpression lambda)) return null;
            lambdas.add(lambda);
        }
        LambdaExpression last = lambdas.getLast();
        if (!(last.getBody() instanceof Block body)) return null;

        List<LambdaExpression> conditions = new ArrayList<>();
        for (LambdaExpression lambda : lambdas.subList(0, lambdas.size() - 1)) {
            if (lambda.getBody() instanceof Block) return null;   // two bodies is not a shape we can draw
            conditions.add(lambda);
        }
        return new Link(call, call.getName().getIdentifier(), conditions, body);
    }

    /**
     * The method name a new branch should use: the <em>last</em> non-terminal link's, so a chain written with
     * two different branch methods grows the one nearest where the button was pressed. Null when the chain has
     * no non-terminal link to copy, which is the one case {@link #addLink} refuses.
     */
    public static String branchMethodName(List<Link> links) {
        String name = null;
        for (Link link : links) {
            if (!link.isTerminal()) name = link.method();
        }
        return name;
    }

    // ---- edit entry points ------------------------------------------------------------------------------

    /**
     * Inserts a branch after the link at {@code afterIndex} and returns the rewritten source, or {@code null}
     * when the statement is not a chain, the index is out of range, or the chain has no branch method to copy.
     *
     * <p><b>{@code null} and not {@code originalCode}</b>, which is {@link CodeEditor}'s convention and not a
     * style choice: {@code edit} publishes a {@code CodeUpdatedEvent} for any non-null return, so handing back
     * the unchanged source announces an edit that did not happen — repainting the canvas and leaving an undo
     * entry that undoes nothing.
     *
     * <p>Indexed rather than node-addressed for the reason the block is: an index survives a re-parse, and the
     * block that raised the edit may be one repaint behind the tree.
     */
    public static String applyAddLink(EditContext ctx, String originalCode, Statement stmt, int afterIndex) {
        List<Link> links = linksOf(stmt);
        if (afterIndex < 0 || afterIndex >= links.size()) return null;
        if (!addLink(ctx, links, links.get(afterIndex))) return null;
        return AstRewriteHelper.applyRewrite(ctx.rewriter(), originalCode);
    }

    /** Removes the link at {@code index}, closing the chain over it; {@code null} when it cannot. */
    public static String applyRemoveLink(EditContext ctx, String originalCode, Statement stmt, int index) {
        List<Link> links = linksOf(stmt);
        if (index < 0 || index >= links.size()) return null;
        if (!removeLink(ctx, links, links.get(index))) return null;
        return AstRewriteHelper.applyRewrite(ctx.rewriter(), originalCode);
    }

    /** The chain a statement holds, or an empty list when it holds none. */
    public static List<Link> linksOf(Statement stmt) {
        if (stmt instanceof ExpressionStatement es && es.getExpression() instanceof MethodInvocation mi) {
            return read(mi);
        }
        return List.of();
    }

    // ---- writing ---------------------------------------------------------------------------------------

    /**
     * Inserts a fresh branch — {@code .when(x -> false, () -> {})} — after {@code afterLink}, keeping the rest
     * of the chain intact.
     *
     * <p><b>The new call is grafted, not rebuilt.</b> A link's receiver is the link before it, so inserting
     * one means giving the <em>following</em> call a new receiver: the new invocation takes
     * {@code afterLink}'s call as its own receiver (moved across with {@code createMoveTarget}, so the user's
     * formatting survives), and then replaces {@code afterLink}'s call wherever it sat. When {@code afterLink}
     * was the outermost call there is nothing above it and the replacement is the statement's expression.
     *
     * <p>The seeded condition is {@code false} rather than {@code true}: a branch that matches everything the
     * moment it appears would silently stop every branch after it from running, which is the failure the
     * chain's own contract makes invisible.
     *
     * @return false when the chain has no branch method to copy, in which case nothing is written
     */
    public static boolean addLink(EditContext ctx, List<Link> links, Link afterLink) {
        String method = branchMethodName(links);
        if (method == null || afterLink == null) return false;

        AST ast = ctx.ast();
        ASTRewrite rewriter = ctx.rewriter();

        MethodInvocation inserted = ast.newMethodInvocation();
        inserted.setExpression((Expression) rewriter.createMoveTarget(afterLink.call()));
        inserted.setName(ast.newSimpleName(method));
        inserted.arguments().add(newCondition(ast, seedParamName(links)));
        inserted.arguments().add(emptyBodyLambda(ast));

        rewriter.replace(afterLink.call(), inserted, null);
        return true;
    }

    /**
     * Removes one link, closing the chain over the gap: whatever the removed call was called on becomes the
     * receiver of whatever was called on it.
     *
     * <p>Removing the only link would leave a bare subject expression as a statement, which does not compile,
     * so it is refused — deleting the whole statement is the block's own delete button.
     *
     * @return false when nothing was written
     */
    public static boolean removeLink(EditContext ctx, List<Link> links, Link target) {
        if (target == null || links.size() <= 1) return false;

        ASTRewrite rewriter = ctx.rewriter();
        Expression receiver = target.call().getExpression();
        if (receiver == null) return false;

        rewriter.replace(target.call(), rewriter.createMoveTarget(receiver), null);
        return true;
    }

    /**
     * A condition lambda over a fresh parameter: {@code m -> false}.
     *
     * <p>Expression-bodied on purpose — that is what distinguishes a condition from a body in
     * {@link #linkOf}, and a block-bodied {@code m -> { return false; }} would be read back as a second body
     * and the link would stop parsing.
     */
    private static LambdaExpression newCondition(AST ast, String paramName) {
        LambdaExpression lambda = ast.newLambdaExpression();
        VariableDeclarationFragment param = ast.newVariableDeclarationFragment();
        param.setName(ast.newSimpleName(paramName));
        lambda.parameters().add(param);
        lambda.setParentheses(false);
        BooleanLiteral seed = ast.newBooleanLiteral(false);
        lambda.setBody(seed);
        return lambda;
    }

    /** {@code () -> {}} — a body a block can be dropped into, the same empty {@link Block} the loops use. */
    private static LambdaExpression emptyBodyLambda(AST ast) {
        LambdaExpression lambda = ast.newLambdaExpression();
        lambda.setParentheses(true);
        lambda.setBody(ast.newBlock());
        return lambda;
    }

    /**
     * What to call the new condition's parameter: the name an existing condition already uses, so every branch
     * in a chain reads the same, falling back to {@code it} when there is none to copy.
     *
     * <p>{@code it} rather than a name derived from the subject's type, because the type is exactly what this
     * class refuses to know.
     */
    private static String seedParamName(List<Link> links) {
        for (Link link : links) {
            String existing = link.conditionParam();
            if (existing != null && !existing.isBlank()) return existing;
        }
        return "it";
    }

    // ---- shared readers --------------------------------------------------------------------------------

    /** A lambda's body as an expression, or null when it has a block body. */
    private static Expression bodyExpression(LambdaExpression lambda) {
        ASTNode body = lambda.getBody();
        return body instanceof Expression expression ? expression : null;
    }

    /** The single parameter a lambda declares, inferred or explicit, or null when it declares none. */
    private static String declaredParam(LambdaExpression lambda) {
        List<?> params = lambda.parameters();
        if (params.isEmpty()) return null;
        SimpleName declared = LambdaCallHandler.declaredName(params.getFirst());
        return declared == null ? null : declared.getIdentifier();
    }
}
