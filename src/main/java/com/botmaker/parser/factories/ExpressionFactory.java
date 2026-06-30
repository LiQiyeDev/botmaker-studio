package com.botmaker.parser.factories;

import com.botmaker.palette.ExpressionType;
import com.botmaker.palette.ExpressionType.InfixOp;
import com.botmaker.palette.ExpressionType.Literal;
import com.botmaker.palette.ExpressionType.Op;
import com.botmaker.palette.ExpressionType.PrefixOp;
import com.botmaker.palette.ExpressionType.Reference;
import com.botmaker.parser.ImportManager;
import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.types.ResolvedType;
import com.botmaker.util.DefaultNames;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

public class ExpressionFactory {

    public static Expression createDefaultExpression(AST ast, ExpressionType type, CompilationUnit cu,
                                                     ASTRewrite rewriter, ResolvedType contextType,
                                                     ProjectAnalyzer analyzer) {
        return switch (type) {
            case Literal l -> switch (l.kind()) {
                case TEXT -> createStringLiteral(ast, "text");
                case NUMBER -> ast.newNumberLiteral("0");
                case TRUE -> ast.newBooleanLiteral(true);
                case FALSE -> ast.newBooleanLiteral(false);
            };
            case Reference r -> switch (r.kind()) {
                case FUNCTION_CALL -> {
                    MethodInvocation mi = ast.newMethodInvocation();
                    mi.setName(ast.newSimpleName("method"));
                    yield mi;
                }
                case VARIABLE -> ast.newSimpleName(DefaultNames.DEFAULT_VARIABLE);
                // ACTIVITY is only ever inserted as a concrete Activities.<name> field reference (via
                // ExpressionChoice.Field), never created blank from the palette; placeholder for exhaustiveness.
                case ACTIVITY -> ast.newSimpleName(DefaultNames.DEFAULT_VARIABLE);
                case SUB_LIST -> ast.newArrayInitializer();
                case ENUM_CONSTANT -> createEnumConstantExpression(ast, contextType);
                case INSTANTIATION -> createInstantiation(ast, contextType, cu, rewriter, analyzer);
            };
            case InfixOp op -> createInfixExpression(ast, op.operator());
            case PrefixOp op -> createPrefixExpression(ast, mapPrefix(op.operator()));
        };
    }

    private static Expression createInstantiation(AST ast, ResolvedType contextType, CompilationUnit cu,
                                                  ASTRewrite rewriter, ProjectAnalyzer analyzer) {
        ClassInstanceCreation cic = ast.newClassInstanceCreation();
        String typeName = (contextType != null && !contextType.isUnknown()) ? contextType.simpleName() : "Object";
        cic.setType(ast.newSimpleType(ast.newSimpleName(typeName)));
        ImportManager.addImportForSimpleName(cu, rewriter, typeName, analyzer, null);
        return cic;
    }

    private static StringLiteral createStringLiteral(AST ast, String value) {
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(value);
        return literal;
    }

    private static Expression createEnumConstantExpression(AST ast, ResolvedType contextType) {
        if (contextType != null && contextType.isEnum()) {
            java.util.List<String> constants = contextType.enumConstants();
            String constName = constants.isEmpty() ? "VALUE" : constants.getFirst();
            return ast.newQualifiedName(
                    ast.newSimpleName(contextType.simpleName()),
                    ast.newSimpleName(constName)
            );
        }
        return ast.newQualifiedName(ast.newSimpleName("MyEnum"), ast.newSimpleName("VALUE"));
    }

    private static Expression createInfixExpression(AST ast, Op op) {
        InfixExpression infixExpr = ast.newInfixExpression();

        // Logic ops default to boolean operands; math/comparison default to 0.
        if (op == Op.AND || op == Op.OR) {
            infixExpr.setLeftOperand(ast.newBooleanLiteral(true));
            infixExpr.setRightOperand(ast.newBooleanLiteral(true));
        } else {
            infixExpr.setLeftOperand(ast.newNumberLiteral("0"));
            infixExpr.setRightOperand(ast.newNumberLiteral("0"));
        }

        infixExpr.setOperator(mapInfix(op));
        return infixExpr;
    }

    private static InfixExpression.Operator mapInfix(Op op) {
        return switch (op) {
            case PLUS -> InfixExpression.Operator.PLUS;
            case MINUS -> InfixExpression.Operator.MINUS;
            case TIMES -> InfixExpression.Operator.TIMES;
            case DIVIDE -> InfixExpression.Operator.DIVIDE;
            case REMAINDER -> InfixExpression.Operator.REMAINDER;
            case EQUALS -> InfixExpression.Operator.EQUALS;
            case NOT_EQUALS -> InfixExpression.Operator.NOT_EQUALS;
            case GREATER -> InfixExpression.Operator.GREATER;
            case LESS -> InfixExpression.Operator.LESS;
            case GREATER_EQUALS -> InfixExpression.Operator.GREATER_EQUALS;
            case LESS_EQUALS -> InfixExpression.Operator.LESS_EQUALS;
            case AND -> InfixExpression.Operator.CONDITIONAL_AND;
            case OR -> InfixExpression.Operator.CONDITIONAL_OR;
            case NOT -> throw new IllegalArgumentException("NOT is a prefix operator");
        };
    }

    private static PrefixExpression.Operator mapPrefix(Op op) {
        if (op == Op.NOT) return PrefixExpression.Operator.NOT;
        throw new IllegalArgumentException("Not a prefix operator: " + op);
    }

    private static Expression createPrefixExpression(AST ast, PrefixExpression.Operator op) {
        PrefixExpression prefix = ast.newPrefixExpression();
        prefix.setOperator(op);
        prefix.setOperand(ast.newBooleanLiteral(true));
        return prefix;
    }
}
