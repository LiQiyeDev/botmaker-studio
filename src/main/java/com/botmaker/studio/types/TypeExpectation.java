package com.botmaker.studio.types;

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

    /**
     * Whether a value of {@code actual} may fill a slot declared {@code slotType} — the question a drag over an
     * expression slot asks, and the reason this lives beside {@link #accepts} rather than in the drag manager:
     * the answer is pure type reasoning, so it is unit-testable without a scene graph.
     *
     * <p>Unknown on either side is accepted, for the same reason {@link #accepts} is fuzzy — a slot on a file
     * that hasn't resolved yet must not start refusing every drop. <b>Void is the exception</b>, and it is
     * refused even by an unresolved slot: a call that produces nothing cannot fill anything. Beyond the four categories, {@link #ANY}
     * means "some object type", where the name is all there is left to compare; the simple name counts because
     * a slot is routinely declared with the bare identifier a lambda parameter wrote.
     */
    public static boolean fits(ResolvedType slotType, ResolvedType actual) {
        // A void call is not a value. It is the one answer that survives an unknown slot: everywhere else
        // "we don't know" means accept, but there is no slot anywhere that a statement can fill, so accepting
        // it would only produce `if (ImageClicker.click(ore))` — which does not compile.
        if (actual != null && actual.isVoid()) return slotType != null && slotType.isVoid();
        if (slotType == null || slotType.isUnknown()) return true;
        if (actual == null || actual.isUnknown()) return true;
        TypeExpectation expected = of(slotType);
        if (expected != ANY) return expected.accepts(actual);
        return actual.isAssignmentCompatible(slotType)
                || slotType.simpleName().equals(actual.simpleName());
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
