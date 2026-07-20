package com.botmaker.studio.palette;

/**
 * A value-producing expression the user can insert/replace from the type-aware menu. Replaces the old
 * weakly-typed {@code AddableExpression} enum (operators-as-strings, nullable return-type/operator
 * fields, a parallel name-decoding switch in {@code ExpressionFactory}) — mirroring the
 * {@code AddableBlock → BlockType} refactor.
 *
 * <p>Each variant <em>carries its own creation data</em>, so {@code ExpressionFactory} dispatches by
 * pattern-matching on the sealed type (exhaustive, compiler-checked) instead of switching on a name.
 * {@link #id()} is the stable token (equals the former enum constant name).
 *
 * <p>The {@link Op} enum is intentionally JDT-free so this package keeps no dependency on Eclipse JDT
 * (mirrors {@link Initializer}); {@code ExpressionFactory} maps {@code Op} to the JDT operator enums.
 */
public sealed interface ExpressionType
        permits ExpressionType.Literal, ExpressionType.Reference,
                ExpressionType.InfixOp, ExpressionType.PrefixOp {

    String id();
    String displayName();
    ExpressionCategory category();

    /**
     * A one-glyph icon for the menus. Per-variant rather than per-category because the operators are exactly
     * where it pays: {@code +}, {@code &&} and {@code ==} are recognised as shapes far faster than the words
     * "Addition", "And", "Equals" are read.
     *
     * <p>Emoji/symbols rather than image assets — nothing to load, and it matches the toolbar's existing
     * style. Rendered as a menu item's <em>graphic</em>, never appended to its text, because the menu's
     * search filters on the text.
     */
    default String icon() {
        return switch (this) {
            case Literal l -> switch (l.kind()) {
                case TEXT -> "\"\"";
                case NUMBER -> "#";
                case TRUE, FALSE -> "◑";
            };
            case Reference r -> switch (r.kind()) {
                case VARIABLE -> "𝑥";
                case ACTIVITY -> "◆";
                case FUNCTION_CALL -> "ƒ";
                case ENUM_CONSTANT -> "◈";
                case SUB_LIST -> "☰";
                case INSTANTIATION -> "✦";
            };
            case InfixOp op -> op.operator().symbol();
            case PrefixOp op -> op.operator().symbol();
        };
    }

    /** A self-contained constant value (no sub-slots to fill) — eligible in {@code constantOnly} menus. */
    boolean isConstant();

    /** JDT-free operator token; mapped to {@code InfixExpression.Operator}/{@code PrefixExpression.Operator}. */
    enum Op {
        PLUS("+"), MINUS("−"), TIMES("×"), DIVIDE("÷"), REMAINDER("%"),
        EQUALS("="), NOT_EQUALS("≠"), GREATER(">"), LESS("<"), GREATER_EQUALS("≥"), LESS_EQUALS("≤"),
        AND("&&"), OR("||"), NOT("!");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        /** How the operator is drawn in a menu — the maths glyph, not the Java spelling. */
        public String symbol() {
            return symbol;
        }
    }

    /** A literal value: text / number / true / false. Always constant. */
    record Literal(String id, String displayName, ExpressionCategory category, Kind kind) implements ExpressionType {
        public enum Kind { TEXT, NUMBER, TRUE, FALSE }
        @Override public boolean isConstant() { return true; }
    }

    /** A reference-shaped expression: variable, activity, function call, enum constant, sub-list, or instantiation. */
    record Reference(String id, String displayName, ExpressionCategory category, Kind kind) implements ExpressionType {
        public enum Kind { VARIABLE, ACTIVITY, FUNCTION_CALL, ENUM_CONSTANT, SUB_LIST, INSTANTIATION }
        @Override public boolean isConstant() { return kind == Kind.ENUM_CONSTANT; }
    }

    /** A binary operator expression ({@code a + b}, {@code a && b}, …). */
    record InfixOp(String id, String displayName, ExpressionCategory category, Op operator) implements ExpressionType {
        @Override public boolean isConstant() { return false; }
    }

    /** A unary prefix operator expression ({@code !a}). */
    record PrefixOp(String id, String displayName, ExpressionCategory category, Op operator) implements ExpressionType {
        @Override public boolean isConstant() { return false; }
    }
}
