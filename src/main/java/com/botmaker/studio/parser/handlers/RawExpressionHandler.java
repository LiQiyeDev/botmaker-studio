package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * Replaces an expression slot with a parsed Java <em>expression snippet</em> (e.g.
 * {@code com.mybot.BotConfig.defaultSource()}). Used where the exact source text is known up front — the
 * capture-source picker emits a fully-qualified helper call, which is simpler and more robust than building
 * the equivalent {@code MethodInvocation} AST by hand (a fully-qualified name needs no import management).
 */
public final class RawExpressionHandler {

    private RawExpressionHandler() {}

    public static String replaceWithExpression(CompilationUnit cu, String originalCode,
                                               Expression toReplace, String exprCode) {
        if (exprCode == null || exprCode.isBlank()) return originalCode;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(exprCode.toCharArray());
        ASTNode parsed = parser.createAST(null);
        if (!(parsed instanceof Expression parsedExpr)) return originalCode;

        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        Expression copied = (Expression) ASTNode.copySubtree(ast, parsedExpr);
        rewriter.replace(toReplace, copied, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
