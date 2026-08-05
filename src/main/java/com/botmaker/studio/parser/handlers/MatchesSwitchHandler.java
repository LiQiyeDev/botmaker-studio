package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.GuardedPattern;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TypePattern;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The writes behind the {@code Matches} switch — a Java 21 guarded switch whose every case is
 * {@code case Matches m when m.hasAny(new ImageTemplate("…"), …) -> { … }}.
 *
 * <p>Stateless and static-only, like {@link LambdaCallHandler} and {@link SwitchNormalizer}: every input is a
 * parameter and each method is a pure {@code (cu, code) -> newCode} transform its {@code CodeEditor} caller
 * wraps. It is a separate handler rather than a branch inside the ordinary switch machinery because the two
 * shapes share nothing at the label: an ordinary case is {@code case <expression>:} with a trailing
 * {@code break}, this one is an arrow rule whose label is a {@link GuardedPattern}.
 *
 * <p><b>Two invariants the callers rely on, both of which are compile errors when broken.</b> A statement
 * switch with pattern labels must be exhaustive, so the trailing {@code default} rule is chrome the UI never
 * offers to delete. And a guard is never allowed to become empty: an unguarded {@code case Matches m} is
 * unconditional and would dominate every case after it, so the last template of a case cannot be removed.
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
    // READING
    // =================================================================================

    /** A case's guard, or empty when the label isn't the shape this handler writes. */
    public static Optional<Guard> guardOf(SwitchCase caseNode) {
        if (caseNode == null || caseNode.isDefault() || caseNode.expressions().size() != 1) return Optional.empty();
        if (!(caseNode.expressions().getFirst() instanceof GuardedPattern gp)) return Optional.empty();
        if (!(gp.getExpression() instanceof MethodInvocation call)) return Optional.empty();

        String method = call.getName().getIdentifier();
        boolean all = "hasAll".equals(method);
        if (!all && !"hasAny".equals(method)) return Optional.empty();

        List<String> paths = new ArrayList<>();
        for (Object arg : call.arguments()) {
            // Anything that isn't a literal `new ImageTemplate("…")` is a reference the chip row cannot show
            // and must not overwrite — the same rule the image varargs slot applies.
            Optional<String> path = templatePath(arg);
            if (path.isEmpty()) return Optional.empty();
            paths.add(path.get());
        }
        if (paths.isEmpty()) return Optional.empty();
        return Optional.of(new Guard(all, List.copyOf(paths), call));
    }

    /**
     * What a case tests.
     *
     * @param all   {@code true} for {@code hasAll}, {@code false} for {@code hasAny}
     * @param paths the template paths, in source order — never empty
     * @param call  the guard invocation itself, which is what the writes below rewrite
     */
    public record Guard(boolean all, List<String> paths, MethodInvocation call) {}

    /** The path inside {@code new ImageTemplate("…")}, or empty for anything else. */
    private static Optional<String> templatePath(Object node) {
        if (node instanceof ClassInstanceCreation cic
                && "ImageTemplate".equals(cic.getType().toString())
                && !cic.arguments().isEmpty()
                && cic.arguments().getFirst() instanceof StringLiteral sl) {
            return Optional.of(sl.getLiteralValue());
        }
        return Optional.empty();
    }

    /** Whether {@code stmt} is a switch this handler owns: a guarded arrow rule in every non-default case. */
    public static boolean isMatchesSwitch(SwitchStatement stmt) {
        if (stmt == null) return false;
        boolean sawGuardedCase = false;
        for (Object o : stmt.statements()) {
            if (!(o instanceof SwitchCase sc)) continue;
            if (sc.isDefault()) continue;
            if (guardOf(sc).isEmpty()) return false;
            sawGuardedCase = true;
        }
        return sawGuardedCase;
    }

    // =================================================================================
    // WRITING
    // =================================================================================

    /** Rewrites a case's templates to exactly {@code paths}, keeping its any/all mode. */
    public static String setCaseTemplates(CompilationUnit cu, String code, SwitchCase caseNode,
                                          List<String> paths) {
        Guard guard = guardOf(caseNode).orElse(null);
        if (guard == null || paths == null || paths.isEmpty()) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite args = rewriter.getListRewrite(guard.call(), MethodInvocation.ARGUMENTS_PROPERTY);
        for (Object existing : guard.call().arguments()) {
            args.remove((ASTNode) existing, null);
        }
        for (String path : paths) {
            args.insertLast(newTemplate(ast, path), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /** Flips a case between {@code hasAny} and {@code hasAll}; its templates are untouched. */
    public static String setCaseMode(CompilationUnit cu, String code, SwitchCase caseNode, boolean all) {
        Guard guard = guardOf(caseNode).orElse(null);
        if (guard == null || guard.all() == all) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.set(guard.call(), MethodInvocation.NAME_PROPERTY,
                ast.newSimpleName(all ? "hasAll" : "hasAny"), null);
        return AstRewriteHelper.applyRewrite(rewriter, code);
    }

    /**
     * Appends a case seeded with {@code paths}, before the trailing {@code default} rule so the switch stays
     * exhaustive and the new case is actually reachable.
     */
    public static String addCase(CompilationUnit cu, String code, SwitchStatement switchStmt,
                                 boolean all, List<String> paths) {
        if (switchStmt == null || paths == null || paths.isEmpty()) return null;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite list = rewriter.getListRewrite(switchStmt, SwitchStatement.STATEMENTS_PROPERTY);

        SwitchCase defaultCase = defaultCaseOf(switchStmt);
        if (defaultCase == null) {
            list.insertLast(newGuardedCase(ast, all, paths), null);
            list.insertLast(ast.newBlock(), null);
        } else {
            list.insertBefore(newGuardedCase(ast, all, paths), defaultCase, null);
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
    public static SwitchStatement newMatchesSwitch(AST ast, Expression subject, boolean all, List<String> paths) {
        SwitchStatement switchStmt = ast.newSwitchStatement();
        switchStmt.setExpression(subject);
        switchStmt.statements().add(newGuardedCase(ast, all, paths));
        switchStmt.statements().add(ast.newBlock());

        SwitchCase defaultCase = ast.newSwitchCase();
        defaultCase.setSwitchLabeledRule(true);
        switchStmt.statements().add(defaultCase);
        switchStmt.statements().add(ast.newBlock());
        return switchStmt;
    }

    /** {@code case Matches m when m.hasAny(new ImageTemplate("…"), …) ->} */
    private static SwitchCase newGuardedCase(AST ast, boolean all, List<String> paths) {
        SingleVariableDeclaration variable = ast.newSingleVariableDeclaration();
        variable.setType(ast.newSimpleType(ast.newSimpleName("Matches")));
        variable.setName(ast.newSimpleName(PATTERN_VAR));

        TypePattern pattern = ast.newTypePattern();
        pattern.setPatternVariable(variable);

        MethodInvocation guard = ast.newMethodInvocation();
        guard.setExpression(ast.newSimpleName(PATTERN_VAR));
        guard.setName(ast.newSimpleName(all ? "hasAll" : "hasAny"));
        for (String path : paths) {
            guard.arguments().add(newTemplate(ast, path));
        }

        GuardedPattern guarded = ast.newGuardedPattern();
        guarded.setPattern(pattern);
        guarded.setExpression(guard);

        SwitchCase caseNode = ast.newSwitchCase();
        caseNode.setSwitchLabeledRule(true);
        caseNode.expressions().add(guarded);
        return caseNode;
    }

    private static ClassInstanceCreation newTemplate(AST ast, String path) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        cic.setType(ast.newSimpleType(ast.newSimpleName("ImageTemplate")));
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(path == null ? "" : path);
        cic.arguments().add(literal);
        return cic;
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

    /** The switch's selector name, or null when it isn't a plain variable read. */
    public static String subjectOf(SwitchStatement switchStmt) {
        Expression selector = switchStmt == null ? null : switchStmt.getExpression();
        return selector instanceof SimpleName name ? name.getIdentifier() : null;
    }

    /** The single {@link Block} a case body is, or null when the case body isn't one braced block. */
    public static Block singleBlockBody(SwitchStatement switchStmt, SwitchCase caseNode) {
        List<Statement> body = bodyOf(switchStmt, caseNode);
        return body.size() == 1 && body.getFirst() instanceof Block b ? b : null;
    }
}
