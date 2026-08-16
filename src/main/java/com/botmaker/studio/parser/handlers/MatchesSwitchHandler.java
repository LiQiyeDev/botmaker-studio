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
import org.eclipse.jdt.core.dom.StringLiteral;
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
 * {@code &&} / {@code ||} junctions and {@code !} above them — so the block renders composition instead of
 * refusing to claim it. The single-check case is still a {@link Guard.Check} at the root and still renders as
 * the any/all toggle over a chip row, which is the shape almost every branch has.
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
     * <p>Every variant carries {@link #node()} — the expression <em>as written</em>, parentheses included —
     * because that is what a rewrite replaces. The classification looks through parentheses, so
     * {@code (a && b)} reads as a junction while still being replaceable as one node.
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
         * {@code a && b [&& c…]} or the {@code ||} equivalent. Flat by construction: JDT models a chain as one
         * {@link InfixExpression} with extended operands, and the writes below keep it that way rather than
         * nesting a new junction per added operand.
         */
        record Junction(MatchesJoin join, List<Guard> operands, InfixExpression infix,
                        Expression node) implements Guard {}

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
                return new Guard.Junction(join, List.copyOf(operands), infix, expression);
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
        if (SdkNodes.isInstantiationOf(node, SdkType.IMAGE_TEMPLATE)
                && node instanceof ClassInstanceCreation cic
                && !cic.arguments().isEmpty()
                && cic.arguments().getFirst() instanceof StringLiteral sl) {
            return Optional.of(sl.getLiteralValue());
        }
        return Optional.empty();
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
     * Joins {@code target} with a fresh check on {@code seedPath}: {@code target && m.hasAny(…)}.
     *
     * <p>A junction of the same kind is <em>extended</em> rather than nested, so "A and B and C" stays one flat
     * group of rows instead of stepping right with each addition — the same reason JDT models it that way.
     */
    public static String joinWithCheck(CompilationUnit cu, String code, Expression target, MatchesJoin join,
                                       String seedPath) {
        if (target == null || join == null || seedPath == null) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        MethodInvocation addition = newCheck(ast, MatchesCheck.ANY, List.of(seedPath));

        Expression inner = unparenthesized(target);
        if (inner instanceof InfixExpression infix && joinOf(infix.getOperator()).orElse(null) == join) {
            rewriter.getListRewrite(infix, InfixExpression.EXTENDED_OPERANDS_PROPERTY)
                    .insertLast(addition, null);
        } else {
            InfixExpression combined = ast.newInfixExpression();
            combined.setOperator(operatorOf(join));
            combined.setLeftOperand(groupedUnder(ast, copyOf(ast, target), join));
            combined.setRightOperand(addition);
            rewriter.replace(target, combined, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /** Flips a junction between {@code &&} and {@code ||}. */
    public static String setJoin(CompilationUnit cu, String code, InfixExpression infix, MatchesJoin join) {
        if (infix == null || join == null || joinOf(infix.getOperator()).orElse(null) == join) return null;

        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.set(infix, InfixExpression.OPERATOR_PROPERTY, operatorOf(join), null);
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /**
     * Negates {@code guard}, or removes the negation when it already is one — the {@code not} control is a
     * toggle, so {@code !!g} is never written.
     */
    public static String toggleNegation(CompilationUnit cu, String code, Guard guard) {
        if (guard == null) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        if (guard instanceof Guard.Not not) {
            rewriter.replace(guard.node(), copyOf(ast, not.operand().node()), null);
        } else {
            PrefixExpression prefix = ast.newPrefixExpression();
            prefix.setOperator(PrefixExpression.Operator.NOT);
            Expression operand = copyOf(ast, guard.node());
            prefix.setOperand(operand instanceof InfixExpression ? parenthesized(ast, operand) : operand);
            rewriter.replace(guard.node(), prefix, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /**
     * Drops one operand from a junction, collapsing the junction to the survivor when only one is left.
     *
     * <p>Refused when it would empty the guard — see the class javadoc: a case with no guard is unconditional
     * and silently dominates every case after it.
     */
    public static String removeOperand(CompilationUnit cu, String code, Guard.Junction junction, Guard operand) {
        if (junction == null || operand == null) return null;

        List<Guard> survivors = new ArrayList<>();
        for (Guard g : junction.operands()) {
            if (g.node() != operand.node()) survivors.add(g);
        }
        if (survivors.size() == junction.operands().size() || survivors.isEmpty()) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        if (survivors.size() == 1) {
            rewriter.replace(junction.node(), copyOf(ast, survivors.getFirst().node()), null);
        } else {
            InfixExpression rebuilt = ast.newInfixExpression();
            rebuilt.setOperator(operatorOf(junction.join()));
            rebuilt.setLeftOperand(copyOf(ast, survivors.get(0).node()));
            rebuilt.setRightOperand(copyOf(ast, survivors.get(1).node()));
            for (int i = 2; i < survivors.size(); i++) {
                rebuilt.extendedOperands().add(copyOf(ast, survivors.get(i).node()));
            }
            rewriter.replace(junction.node(), rebuilt, null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
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
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(path == null ? "" : path);
        cic.arguments().add(literal);
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

    /**
     * {@code e} ready to sit under a {@code join}: an infix of a <em>different</em> operator is parenthesized,
     * because {@code a || b && c} does not mean what the rows above it would be showing.
     */
    private static Expression groupedUnder(AST ast, Expression e, MatchesJoin join) {
        return e instanceof InfixExpression infix && infix.getOperator() != operatorOf(join)
                ? parenthesized(ast, e)
                : e;
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
