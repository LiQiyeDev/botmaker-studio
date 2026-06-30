package com.botmaker.types;

/**
 * What an expression slot expects, for suggestion filtering. This is a UI <em>category</em>, not a
 * concrete type — it replaces the binding-less {@code TypeInfo.INT/BOOLEAN/STRING/...} markers.
 *
 * <p>{@link #accepts(ResolvedType)} is intentionally fuzzy: an unknown actual type is always
 * accepted so the UI never over-filters.
 */
public enum TypeExpectation {
    /** Any numeric type (int/double/long/... or their wrappers). */
    NUMERIC,
    /** boolean or Boolean. */
    BOOLEAN,
    /** String. */
    STRING,
    /** No constraint. */
    ANY,
    /** void (statement context). */
    VOID;

    public boolean accepts(ResolvedType actual) {
        if (this == ANY) return true;
        if (actual == null || actual.isUnknown()) return true;
        return switch (this) {
            case NUMERIC -> actual.isNumeric();
            case BOOLEAN -> actual.isBoolean();
            case STRING  -> actual.isString();
            case VOID    -> actual.isVoid();
            case ANY     -> true;
        };
    }

    /** The category a concrete type falls into (object/unknown types map to {@link #ANY}). */
    public static TypeExpectation of(ResolvedType type) {
        if (type == null || type.isUnknown()) return ANY;
        if (type.isBoolean()) return BOOLEAN;
        if (type.isNumeric()) return NUMERIC;
        if (type.isString())  return STRING;
        if (type.isVoid())    return VOID;
        return ANY;
    }
}
