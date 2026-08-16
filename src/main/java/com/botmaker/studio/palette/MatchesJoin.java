package com.botmaker.studio.palette;

import java.util.Optional;

/**
 * How two guards of a {@code Matches} switch branch are joined: {@code &&} or {@code ||}.
 *
 * <p>The companion of {@link MatchesCheck} — that one says what a single check tests, this one says how several
 * of them combine into the branch's condition. Both are here rather than in the parser for the same reason:
 * {@code palette} is JDT-free, so the enum carries the Java <em>spelling</em> and the handler maps it to
 * {@code InfixExpression.Operator}. Keeping the mapping in one direction is what stops "and" and {@code &&}
 * from drifting apart across the block, the handler and the caption.
 */
public enum MatchesJoin {

    /** Every joined guard has to hold. */
    AND("and", "&&"),
    /** At least one joined guard has to hold. */
    OR("or", "||");

    private final String label;
    private final String symbol;

    MatchesJoin(String label, String symbol) {
        this.label = label;
        this.symbol = symbol;
    }

    /** The word the branch row shows between two guards. */
    public String label() {
        return label;
    }

    /** The Java operator, for the rewrite that writes it. */
    public String symbol() {
        return symbol;
    }

    /** The other one — what clicking the join word switches to. */
    public MatchesJoin flipped() {
        return this == AND ? OR : AND;
    }

    /** The join {@code symbol} spells, or empty for any other operator — a guard may use anything. */
    public static Optional<MatchesJoin> fromSymbol(String symbol) {
        for (MatchesJoin join : values()) {
            if (join.symbol.equals(symbol)) {
                return Optional.of(join);
            }
        }
        return Optional.empty();
    }
}
