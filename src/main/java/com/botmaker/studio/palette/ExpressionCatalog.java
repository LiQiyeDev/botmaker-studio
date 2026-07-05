package com.botmaker.studio.palette;

import com.botmaker.studio.palette.ExpressionType.InfixOp;
import com.botmaker.studio.palette.ExpressionType.Literal;
import com.botmaker.studio.palette.ExpressionType.Op;
import com.botmaker.studio.palette.ExpressionType.PrefixOp;
import com.botmaker.studio.palette.ExpressionType.Reference;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.types.TypeExpectation;

import java.util.List;
import java.util.Optional;

import static com.botmaker.studio.palette.ExpressionCategory.*;

/**
 * The canonical set of insertable {@link ExpressionType}s — the single source of truth that replaces
 * {@code AddableExpression.values()}. Each {@code id()} equals the former enum constant name, so any
 * persisted token is unchanged. Declaration order is the menu display order (grouped by category
 * downstream). Also owns the type-compatibility filtering that used to live on the enum.
 */
public final class ExpressionCatalog {

    private ExpressionCatalog() {}

    // --- Literals ---
    public static final ExpressionType TEXT = lit("TEXT", "Text", Literal.Kind.TEXT);
    public static final ExpressionType NUMBER = lit("NUMBER", "Number", Literal.Kind.NUMBER);
    public static final ExpressionType TRUE = lit("TRUE", "True", Literal.Kind.TRUE);
    public static final ExpressionType FALSE = lit("FALSE", "False", Literal.Kind.FALSE);

    // --- References ---
    public static final ExpressionType VARIABLE = new Reference("VARIABLE", "Variable", REFERENCE, Reference.Kind.VARIABLE);
    public static final ExpressionType ACTIVITY = new Reference("ACTIVITY", "Activity", REFERENCE, Reference.Kind.ACTIVITY);
    public static final ExpressionType FUNCTION_CALL = new Reference("FUNCTION_CALL", "Function Call", REFERENCE, Reference.Kind.FUNCTION_CALL);
    public static final ExpressionType ENUM_CONSTANT = new Reference("ENUM_CONSTANT", "Enum Value", LITERAL, Reference.Kind.ENUM_CONSTANT);
    public static final ExpressionType LIST = new Reference("LIST", "Sub-List", STRUCTURE, Reference.Kind.SUB_LIST);
    public static final ExpressionType INSTANTIATION = new Reference("INSTANTIATION", "New Object", STRUCTURE, Reference.Kind.INSTANTIATION);

    // --- Math ---
    public static final ExpressionType ADD = infix("ADD", "Addition (+)", Op.PLUS);
    public static final ExpressionType SUBTRACT = infix("SUBTRACT", "Subtraction (-)", Op.MINUS);
    public static final ExpressionType MULTIPLY = infix("MULTIPLY", "Multiplication (*)", Op.TIMES);
    public static final ExpressionType DIVIDE = infix("DIVIDE", "Division (/)", Op.DIVIDE);
    public static final ExpressionType MODULO = infix("MODULO", "Modulo (%)", Op.REMAINDER);

    // --- Comparison ---
    public static final ExpressionType EQUALS = cmp("EQUALS", "Equals (==)", Op.EQUALS);
    public static final ExpressionType NOT_EQUALS = cmp("NOT_EQUALS", "Not Equals (!=)", Op.NOT_EQUALS);
    public static final ExpressionType GREATER = cmp("GREATER", "Greater (>)", Op.GREATER);
    public static final ExpressionType LESS = cmp("LESS", "Less (<)", Op.LESS);
    public static final ExpressionType GREATER_EQUALS = cmp("GREATER_EQUALS", "Greater Or Equal (>=)", Op.GREATER_EQUALS);
    public static final ExpressionType LESS_EQUALS = cmp("LESS_EQUALS", "Less Or Equal (<=)", Op.LESS_EQUALS);

    // --- Logic ---
    public static final ExpressionType AND = new InfixOp("AND", "And (&&)", LOGIC, Op.AND);
    public static final ExpressionType OR = new InfixOp("OR", "Or (||)", LOGIC, Op.OR);
    public static final ExpressionType NOT = new PrefixOp("NOT", "Not (!)", LOGIC, Op.NOT);

    private static final List<ExpressionType> ALL = List.of(
            TEXT, NUMBER, TRUE, FALSE,
            VARIABLE, ACTIVITY, FUNCTION_CALL, ENUM_CONSTANT, LIST, INSTANTIATION,
            ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO,
            EQUALS, NOT_EQUALS, GREATER, LESS, GREATER_EQUALS, LESS_EQUALS,
            AND, OR, NOT);

    /** All insertable expressions in menu display order. */
    public static List<ExpressionType> all() {
        return ALL;
    }

    /** Resolves an expression from its {@link ExpressionType#id()}. */
    public static Optional<ExpressionType> byId(String id) {
        if (id == null) return Optional.empty();
        return ALL.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public static List<ExpressionType> getForType(ResolvedType targetType, ProjectState state) {
        return getForType(targetType, false, state);
    }

    public static List<ExpressionType> getForType(ResolvedType targetType, boolean constantOnly, ProjectState state) {
        return ALL.stream()
                .filter(e -> !constantOnly || e.isConstant())
                .filter(e -> isCompatibleWith(e, targetType, state))
                .toList();
    }

    public static boolean isCompatibleWith(ExpressionType expr, ResolvedType targetType, ProjectState state) {
        if (targetType == null || targetType.isUnknown()) {
            // Instantiation requires a known type to construct; everything else is allowed.
            return !(expr instanceof Reference r && r.kind() == Reference.Kind.INSTANTIATION);
        }

        if (expr instanceof Reference r) {
            return switch (r.kind()) {
                case ENUM_CONSTANT -> targetType.isEnum();
                case SUB_LIST -> targetType.isArray();
                case INSTANTIATION -> {
                    ResolvedType checkType = targetType.isArray() ? targetType.leafType() : targetType;
                    yield !checkType.isPrimitive() && !checkType.isString() && !checkType.isEnum();
                }
                case VARIABLE, ACTIVITY, FUNCTION_CALL -> true;
            };
        }

        // Literals / operators: the expression's result category must satisfy the slot's category.
        TypeExpectation required = TypeExpectation.of(targetType);
        return required == TypeExpectation.ANY || required == returnCategory(expr);
    }

    /** The {@link TypeExpectation} an expression's result falls into (only Literals/operators reach here). */
    private static TypeExpectation returnCategory(ExpressionType expr) {
        return switch (expr) {
            case Literal l -> switch (l.kind()) {
                case TEXT -> TypeExpectation.STRING;
                case NUMBER -> TypeExpectation.NUMERIC;
                case TRUE, FALSE -> TypeExpectation.BOOLEAN;
            };
            case InfixOp op -> switch (op.operator()) {
                case PLUS, MINUS, TIMES, DIVIDE, REMAINDER -> TypeExpectation.NUMERIC;
                default -> TypeExpectation.BOOLEAN;
            };
            case PrefixOp ignored -> TypeExpectation.BOOLEAN;
            case Reference ignored -> TypeExpectation.ANY; // unreached (handled above)
        };
    }

    private static ExpressionType lit(String id, String displayName, Literal.Kind kind) {
        return new Literal(id, displayName, LITERAL, kind);
    }

    private static ExpressionType infix(String id, String displayName, Op op) {
        return new InfixOp(id, displayName, MATH, op);
    }

    private static ExpressionType cmp(String id, String displayName, Op op) {
        return new InfixOp(id, displayName, COMPARISON, op);
    }
}
