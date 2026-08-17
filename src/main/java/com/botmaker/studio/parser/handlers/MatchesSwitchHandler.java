package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.palette.SdkType;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SdkNodes;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.GuardedPattern;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.Pattern;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TypePattern;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import com.botmaker.studio.palette.MatchesCheck;
import com.botmaker.studio.palette.MatchesJoin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The writes behind the {@code Matches} switch — a Java 21 guarded switch whose every case is
 * {@code case Matches m when <guard> -> { … }}.
 *
 * <p>Stateless and static-only, like {@link LambdaCallHandler} and {@link SwitchNormalizer}: every input is a
 * parameter and each method is a pure {@code (cu, code) -> newCode} transform its {@code CodeEditor} caller
 * wraps. It is a separate handler rather than a branch inside the ordinary switch machinery because the two
 * shapes share nothing at the label: an ordinary case is {@code case <expression>:} with a trailing
 * {@code break}, this one is an arrow rule whose label is a {@link GuardedPattern}.
 *
 * <p><b>The guard is a boolean expression, not a single check.</b> It started as exactly one
 * {@code m.hasAny(…)} / {@code m.hasAll(…)} call, and a branch that wanted "these two <em>and</em> not that
 * one" had nowhere to say it. {@link Guard} is now the tree that expression really is — checks at the leaves,
 * <b>all of</b> / <b>any of</b> {@linkplain Guard.Container containers} and {@code !} above them — so the block
 * renders composition instead of refusing to claim it. The single-check case is still a {@link Guard.Check} at
 * the root and still renders as the any/all toggle over a chip row, which is the shape almost every branch has.
 *
 * <p><b>Composition is one write, not one per gesture.</b> {@link GuardTree} transforms the tree and
 * {@link #setGuard} writes the whole guard back; {@link #buildGuard} decides the brackets, one per nested
 * container. See {@link #setGuard} for why the per-gesture rewrites this replaced could not keep that promise.
 *
 * <p><b>What the switch is claimed on</b> is therefore the <em>label</em>, not the guard: a rule-form case
 * whose pattern is {@code Matches m}. It has to be, now that the guard can be any expression — and it is the
 * honest test anyway, since anything else in that position is a different switch entirely. Claiming is on
 * shape rather than bindings because Studio does not compile against the SDK, so {@code Matches} routinely
 * resolves to nothing at edit time.
 *
 * <p><b>Two invariants the callers rely on, both of which are compile errors when broken.</b> A statement
 * switch with pattern labels must be exhaustive, so the trailing {@code default} rule is chrome the UI never
 * offers to delete. And a guard is never allowed to become empty: an unguarded {@code case Matches m} is
 * unconditional and would dominate every case after it, so neither the last template of a check nor the last
 * operand of a guard can be removed.
 *
 * <p><b>On {@code TypePattern}:</b> the pattern variable is written with the singular
 * {@code setPatternVariable} rather than the plural {@code patternVariables()} list. Both exist in JDT 3.37 and
 * only the singular one produces a usable node — building through the list yields {@code case int MISSING when
 * …}. Reading works either way; this file uses the singular accessor on both sides so they stay symmetric.
 */
public final class MatchesSwitchHandler {

    /** The pattern variable every case binds. One name for all cases: they are never in scope together. */
    public static final String PATTERN_VAR = "m";

    private MatchesSwitchHandler() {}

    // =================================================================================
    // THE GUARD MODEL
    // =================================================================================

    /**
     * What a branch tests, as the tree its guard expression already is.
     *
     * <p>Every variant carries {@link #node()} — the expression <em>as written</em>, parentheses included.
     * The classification looks through parentheses, so {@code (a && b)} reads as a container while its node
     * stays the bracketed expression. Only {@link Guard.Other} still <em>needs</em> its node, as the thing
     * {@link #buildGuard} copies; the rest are rebuilt from what they mean, which is what lets a tree be
     * edited without an AST at all (see {@link GuardTree}).
     */
    public sealed interface Guard {

        /** The expression this guard is, as written — the node a rewrite targets. */
        Expression node();

        /**
         * One {@code m.hasAny(…)} / {@code m.hasAll(…)} over literal templates — the leaf the block renders as
         * a toggle and a chip row. {@code call} is the invocation itself, which is what the mode and template
         * writes rewrite; it differs from {@code node} only when the check was written parenthesized.
         */
        record Check(MatchesCheck check, List<String> paths, MethodInvocation call,
                     Expression node) implements Guard {}

        /** {@code !g}. */
        record Not(Guard operand, Expression node) implements Guard {}

        /**
         * An <b>all of</b> / <b>any of</b> container: {@code a && b [&& c…]} or the {@code ||} equivalent.
         *
         * <p>The join belongs to the container as a whole — one word above its rows, not one per gap — which
         * is the shape the source already has and the block now draws. Flat by construction where the source
         * is flat: JDT models {@code a && b && c} as one {@link InfixExpression} with extended operands, so
         * that is one container of three rows rather than two nested ones. A container nested inside another
         * is always written parenthesized (see {@link #buildGuard}), so every bracket on disk is a container
         * on screen and back again.
         */
        record Container(MatchesJoin join, List<Guard> operands, Expression node) implements Guard {}

        /**
         * Any other boolean expression — {@code m.isEmpty()}, a check against a template held in a constant, a
         * comparison. Not a failure: it renders as an ordinary expression slot, so source this block cannot
         * describe in chips stays editable instead of falling back to a rendering that misreads it.
         */
        record Other(Expression node) implements Guard {}
    }

    // =================================================================================
    // READING
    // =================================================================================

    /** A case's guard, or empty when the label isn't {@code case Matches m when …}. */
    public static Optional<Guard> guardOf(SwitchCase caseNode) {
        Expression expression = guardExpressionOf(caseNode);
        return expression == null ? Optional.empty() : Optional.of(read(expression));
    }

    /** The raw guard expression of a {@code case Matches m when …} label, or null for any other label. */
    public static Expression guardExpressionOf(SwitchCase caseNode) {
        if (caseNode == null || caseNode.isDefault() || caseNode.expressions().size() != 1) return null;
        if (!(caseNode.expressions().getFirst() instanceof GuardedPattern gp)) return null;
        if (!isMatchesPattern(gp.getPattern())) return null;
        return gp.getExpression();
    }

    /** The guard tree {@code expression} is. Total — anything unrecognised is a {@link Guard.Other}. */
    public static Guard read(Expression expression) {
        Expression inner = unparenthesized(expression);

        if (inner instanceof PrefixExpression prefix
                && prefix.getOperator() == PrefixExpression.Operator.NOT) {
            return new Guard.Not(read(prefix.getOperand()), expression);
        }
        if (inner instanceof InfixExpression infix) {
            MatchesJoin join = joinOf(infix.getOperator()).orElse(null);
            if (join != null) {
                List<Guard> operands = new ArrayList<>();
                operands.add(read(infix.getLeftOperand()));
                operands.add(read(infix.getRightOperand()));
                for (Object extra : infix.extendedOperands()) {
                    operands.add(read((Expression) extra));
                }
                return new Guard.Container(join, List.copyOf(operands), expression);
            }
        }
        if (inner instanceof MethodInvocation call) {
            Guard.Check check = checkOf(call, expression);
            if (check != null) return check;
        }
        return new Guard.Other(expression);
    }

    /** The check {@code call} is, or null when it isn't {@code hasAny}/{@code hasAll} over literal templates. */
    private static Guard.Check checkOf(MethodInvocation call, Expression node) {
        MatchesCheck check = MatchesCheck.fromMethodName(call.getName().getIdentifier()).orElse(null);
        if (check == null) return null;

        List<String> paths = new ArrayList<>();
        for (Object arg : call.arguments()) {
            // Anything that isn't a literal `new ImageTemplate("…")` is a reference the chip row cannot show
            // and must not overwrite — the same rule the image varargs slot applies. The guard is still
            // rendered, as an expression; it just isn't this leaf.
            Optional<String> path = templatePath(arg);
            if (path.isEmpty()) return null;
            paths.add(path.get());
        }
        if (paths.isEmpty()) return null;
        return new Guard.Check(check, List.copyOf(paths), call, node);
    }

    /** The path inside {@code new ImageTemplate("…")}, or empty for anything else. */
    public static Optional<String> templatePath(Object node) {
        return SdkNodes.imageTemplatePathOf(node);
    }

    /** Whether {@code stmt} is a switch this handler owns: a {@code case Matches m when …} in every non-default case. */
    public static boolean isMatchesSwitch(SwitchStatement stmt) {
        if (stmt == null) return false;
        boolean sawGuardedCase = false;
        for (Object o : stmt.statements()) {
            if (!(o instanceof SwitchCase sc)) continue;
            if (sc.isDefault()) continue;
            if (guardExpressionOf(sc) == null) return false;
            sawGuardedCase = true;
        }
        return sawGuardedCase;
    }

    /** Whether {@code pattern} is the {@code Matches m} every case of this shape binds. */
    private static boolean isMatchesPattern(Pattern pattern) {
        if (!(pattern instanceof TypePattern tp)) return false;
        SingleVariableDeclaration variable = tp.getPatternVariable();
        return variable != null && variable.getType() != null
                && namesMatches(variable.getType().toString());
    }

    /** The type text names {@code Matches}, whether the file imported it or qualified it. */
    private static boolean namesMatches(String written) {
        String simple = SdkType.MATCHES.simpleName();
        return written.equals(simple) || written.endsWith("." + simple);
    }

    /** {@code e} with any enclosing parentheses stripped — for classification only, never for rewriting. */
    private static Expression unparenthesized(Expression e) {
        Expression current = e;
        while (current instanceof ParenthesizedExpression p) {
            current = p.getExpression();
        }
        return current;
    }

    // Both directions go through MatchesJoin's own spelling rather than a switch here — JDT's operator token
    // *is* the Java text, so there is no second table to keep in step with the enum.
    private static Optional<MatchesJoin> joinOf(InfixExpression.Operator operator) {
        return operator == null ? Optional.empty() : MatchesJoin.fromSymbol(operator.toString());
    }

    private static InfixExpression.Operator operatorOf(MatchesJoin join) {
        return InfixExpression.Operator.toOperator(join.symbol());
    }

    // =================================================================================
    // WRITING — one check
    // =================================================================================

    /** Rewrites one check's templates to exactly {@code paths}, keeping its any/all mode. */
    public static String setCheckTemplates(CompilationUnit cu, String code, MethodInvocation call,
                                           List<String> paths) {
        if (call == null || paths == null || paths.isEmpty()) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite args = rewriter.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY);
        for (Object existing : call.arguments()) {
            args.remove((ASTNode) existing, null);
        }
        for (String path : paths) {
            args.insertLast(newTemplate(ast, path), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /** Flips one check between {@code hasAny} and {@code hasAll}; its templates are untouched. */
    public static String setCheckMode(CompilationUnit cu, String code, MethodInvocation call,
                                      MatchesCheck check) {
        if (call == null || check == null) return null;
        if (MatchesCheck.fromMethodName(call.getName().getIdentifier()).orElse(null) == check) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.set(call, MethodInvocation.NAME_PROPERTY, ast.newSimpleName(check.methodName()), null);
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    // =================================================================================
    // WRITING — composing guards
    // =================================================================================

    /**
     * Rewrites a branch's whole guard to {@code newTree} — the single write behind every composition the block
     * offers (add a condition, add a group, remove one, flip a container's word, negate, drag between
     * containers). {@link GuardTree} produces the tree; this writes it.
     *
     * <p>It is one write rather than one per gesture because the tree, not the source text, is the thing being
     * edited: a targeted {@code ASTRewrite} per gesture has to decide for itself where a bracket belongs, which
     * is exactly the guessing that let a flip reach a sibling and a bracket round-trip differently than it was
     * written. Rebuilding the expression from the tree makes the brackets a consequence of the containers.
     */
    public static String setGuard(CompilationUnit cu, String code, SwitchCase caseNode, Guard newTree) {
        Expression current = guardExpressionOf(caseNode);
        if (current == null || newTree == null) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.replace(current, buildGuard(ast, newTree), null);
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /**
     * The expression {@code guard} is — checks rebuilt from their mode and paths, {@link Guard.Other} leaves
     * copied verbatim, and <b>a bracket around every nested container</b>.
     *
     * <p>That last rule is the round-trip: {@link #read} treats a bracketed junction as a nested container, so
     * bracketing every container on the way out means the tree written is the tree read back. The old
     * "bracket only when the operators differ" rule was correct Java and still lost the user's grouping —
     * {@code (a && b) && c} came back as one flat container of three.
     */
    public static Expression buildGuard(AST ast, Guard guard) {
        return switch (guard) {
            case Guard.Check check -> newCheck(ast, check.check(), check.paths());
            case Guard.Other other -> copyOf(ast, other.node());
            case Guard.Not not -> {
                PrefixExpression prefix = ast.newPrefixExpression();
                prefix.setOperator(PrefixExpression.Operator.NOT);
                prefix.setOperand(operandOf(ast, not.operand()));
                yield prefix;
            }
            case Guard.Container container -> {
                List<Guard> operands = container.operands();
                if (operands.isEmpty()) yield ast.newBooleanLiteral(true);
                // A container of one is not a junction; it is whatever it holds. Reachable only from a
                // hand-built tree — every edit below collapses to the survivor rather than emitting one.
                if (operands.size() == 1) yield buildGuard(ast, operands.getFirst());

                InfixExpression infix = ast.newInfixExpression();
                infix.setOperator(operatorOf(container.join()));
                infix.setLeftOperand(operandOf(ast, operands.get(0)));
                infix.setRightOperand(operandOf(ast, operands.get(1)));
                for (int i = 2; i < operands.size(); i++) {
                    infix.extendedOperands().add(operandOf(ast, operands.get(i)));
                }
                yield infix;
            }
        };
    }

    /**
     * One operand as it sits under a container or a {@code !}: bracketed whenever it built to an infix. That
     * covers both a nested container and a hand-written {@link Guard.Other} comparison, which needs the bracket
     * for the same reason — {@code !a == b} does not negate what the row above it shows.
     */
    private static Expression operandOf(AST ast, Guard guard) {
        Expression built = buildGuard(ast, guard);
        return built instanceof InfixExpression ? parenthesized(ast, built) : built;
    }

    // =================================================================================
    // WRITING — cases
    // =================================================================================

    /**
     * Appends a case seeded with {@code paths}, before the trailing {@code default} rule so the switch stays
     * exhaustive and the new case is actually reachable.
     */
    public static String addCase(CompilationUnit cu, String code, SwitchStatement switchStmt,
                                 MatchesCheck check, List<String> paths) {
        if (switchStmt == null || paths == null || paths.isEmpty()) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite list = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);

        SwitchCase defaultCase = defaultCaseOf(switchStmt);
        if (defaultCase == null) {
            list.insertLast(newGuardedCase(ast, check, paths), null);
            list.insertLast(ast.newBlock(), null);
        } else {
            list.insertBefore(newGuardedCase(ast, check, paths), defaultCase, null);
            list.insertBefore(ast.newBlock(), defaultCase, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /** Removes a case and its body. The {@code default} rule is not removable — see the class javadoc. */
    public static String removeCase(CompilationUnit cu, String code, SwitchCase caseNode) {
        if (caseNode == null || caseNode.isDefault()) return null;
        if (!(caseNode.getParent() instanceof SwitchStatement switchStmt)) return null;

        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        ListRewrite list = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);
        list.remove(caseNode, null);
        for (Statement body : bodyOf(switchStmt, caseNode)) {
            list.remove(body, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    // =================================================================================
    // BUILDING
    // =================================================================================

    /**
     * A whole {@code switch (<subject>) { case … -> {} default -> {} }} for a fresh insertion. Public because
     * {@code StatementFactory} builds the palette drop with it, and there is no reason for two spellings of the
     * same shape.
     */
    public static SwitchStatement newMatchesSwitch(AST ast, Expression subject, MatchesCheck check,
                                                  List<String> paths) {
        SwitchStatement switchStmt = ast.newSwitchStatement();
        switchStmt.setExpression(subject);
        switchStmt.statements().add(newGuardedCase(ast, check, paths));
        switchStmt.statements().add(ast.newBlock());

        SwitchCase defaultCase = ast.newSwitchCase();
        defaultCase.setSwitchLabeledRule(true);
        switchStmt.statements().add(defaultCase);
        switchStmt.statements().add(ast.newBlock());
        return switchStmt;
    }

    /**
     * A {@link Block} holding nothing but a seeded switch — the body a group-lambda call is born with, so
     * picking {@code whileFindAny} lands you on the question that variant exists to ask instead of an empty
     * block. Only ever used to fill a body that <em>is</em> empty; see {@code LambdaCallHandler.switchVariant}.
     */
    public static Block newSeededBody(AST ast, String subject, String templatePath) {
        Block body = ast.newBlock();
        body.statements().add(
                newMatchesSwitch(ast, ast.newSimpleName(subject), MatchesCheck.ANY, List.of(templatePath)));
        return body;
    }

    /** {@code case Matches m when m.hasAny(new ImageTemplate("…"), …) ->} */
    private static SwitchCase newGuardedCase(AST ast, MatchesCheck check, List<String> paths) {
        SingleVariableDeclaration variable = ast.newSingleVariableDeclaration();
        variable.setType(SdkNodes.type(ast, SdkType.MATCHES));
        variable.setName(ast.newSimpleName(PATTERN_VAR));

        TypePattern pattern = ast.newTypePattern();
        pattern.setPatternVariable(variable);

        GuardedPattern guarded = ast.newGuardedPattern();
        guarded.setPattern(pattern);
        guarded.setExpression(newCheck(ast, check, paths));

        SwitchCase caseNode = ast.newSwitchCase();
        caseNode.setSwitchLabeledRule(true);
        caseNode.expressions().add(guarded);
        return caseNode;
    }

    /** {@code m.hasAny(new ImageTemplate("…"), …)} — one leaf of a guard. */
    private static MethodInvocation newCheck(AST ast, MatchesCheck check, List<String> paths) {
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName(PATTERN_VAR));
        call.setName(ast.newSimpleName(check.methodName()));
        for (String path : paths) {
            call.arguments().add(newTemplate(ast, path));
        }
        return call;
    }

    private static ClassInstanceCreation newTemplate(AST ast, String path) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(SdkNodes.type(ast, SdkType.IMAGE_TEMPLATE));
        cic.arguments().add(SdkNodes.templateArgument(ast, path));
        return cic;
    }

    @SuppressWarnings("unchecked")
    private static <T extends ASTNode> T copyOf(AST ast, T node) {
        return (T) ASTNode.copySubtree(ast, node);
    }

    private static ParenthesizedExpression parenthesized(AST ast, Expression e) {
        ParenthesizedExpression parens = ast.newParenthesizedExpression();
        parens.setExpression(e);
        return parens;
    }

    // =================================================================================
    // NAVIGATION
    // =================================================================================

    /** The switch's {@code default} rule, or null when it has none (hand-edited source). */
    public static SwitchCase defaultCaseOf(SwitchStatement switchStmt) {
        for (Object o : switchStmt.statements()) {
            if (o instanceof SwitchCase sc && sc.isDefault()) return sc;
        }
        return null;
    }

    /** The statements belonging to {@code caseNode} — everything up to the next label. */
    public static List<Statement> bodyOf(SwitchStatement switchStmt, SwitchCase caseNode) {
        List<Statement> body = new ArrayList<>();
        boolean collecting = false;
        for (Object o : switchStmt.statements()) {
            Statement s = (Statement) o;
            if (s instanceof SwitchCase) {
                if (collecting) break;
                collecting = (s == caseNode);
                continue;
            }
            if (collecting) body.add(s);
        }
        return body;
    }

    /** The single {@link Block} a case body is, or null when the case body isn't one braced block. */
    public static Block singleBlockBody(SwitchStatement switchStmt, SwitchCase caseNode) {
        List<Statement> body = bodyOf(switchStmt, caseNode);
        return body.size() == 1 && body.getFirst() instanceof Block b ? b : null;
    }
}
