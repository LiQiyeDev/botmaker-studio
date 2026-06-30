package com.botmaker.parser.handlers;

import com.botmaker.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

/**
 * Handles replacement of operators in expressions.
 */
public class OperatorReplacementHandler {

    /**
     * Replaces an infix operator (e.g., +, -, *, /)
     */
    public static String replaceInfixOperator(CompilationUnit cu, String originalCode,
                                       InfixExpression infix, InfixExpression.Operator newOp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.set(infix, InfixExpression.OPERATOR_PROPERTY, newOp, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Replaces an assignment operator (e.g., =, +=, -=)
     */
    public static String replaceAssignmentOperator(CompilationUnit cu, String originalCode,
                                            Assignment assignment, Assignment.Operator newOp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        Assignment newAssignment = ast.newAssignment();
        newAssignment.setLeftHandSide((Expression) ASTNode.copySubtree(ast, assignment.getLeftHandSide()));
        newAssignment.setRightHandSide((Expression) ASTNode.copySubtree(ast, assignment.getRightHandSide()));
        newAssignment.setOperator(newOp);

        rewriter.replace(assignment, newAssignment, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Replaces a prefix operator (e.g., ++, --)
     */
    public static String replacePrefixOperator(CompilationUnit cu, String originalCode,
                                        PrefixExpression prefix, PrefixExpression.Operator newOp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        PrefixExpression newPrefix = ast.newPrefixExpression();
        newPrefix.setOperand((Expression) ASTNode.copySubtree(ast, prefix.getOperand()));
        newPrefix.setOperator(newOp);

        rewriter.replace(prefix, newPrefix, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }

    /**
     * Replaces a postfix operator (e.g., ++, --)
     */
    public static String replacePostfixOperator(CompilationUnit cu, String originalCode,
                                         PostfixExpression postfix, PostfixExpression.Operator newOp) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);

        PostfixExpression newPostfix = ast.newPostfixExpression();
        newPostfix.setOperand((Expression) ASTNode.copySubtree(ast, postfix.getOperand()));
        newPostfix.setOperator(newOp);

        rewriter.replace(postfix, newPostfix, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}