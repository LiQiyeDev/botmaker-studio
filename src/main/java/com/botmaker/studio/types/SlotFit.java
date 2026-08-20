package com.botmaker.studio.types;

/**
 * Why a value may not fill a slot, phrased for the user — the sentence the drag-over tooltip shows and, now,
 * the one the status bar repeats when the drop itself is refused.
 *
 * <p>The two have to be the same words. The cue is drawn from a type the dragboard advertises, which is
 * whatever the analyzer could work out while the file was still unresolved; the drop is judged with the AST in
 * hand, where the answer is authoritative. So a drag can be waved through and still be refused on landing —
 * and when that happens the only tolerable outcome is the user reading the same reason they would have read
 * had the cue been right, rather than watching the block spring back for no stated reason.
 */
public final class SlotFit {

    private SlotFit() {}

    /**
     * Null when a value of {@code actual} may fill a slot of {@code slotType}; the reason it may not,
     * otherwise. Unknown on either side fits — see {@link TypeExpectation#fits} — so a file that has not
     * resolved refuses nothing except the one answer that survives it, a line producing no value at all.
     */
    public static String refusal(ResolvedType slotType, ResolvedType actual) {
        if (TypeExpectation.fits(slotType, actual)) return null;
        if (actual != null && actual.isVoid()) return "This line produces nothing, so it cannot fill a slot.";
        String gives = actual == null ? "nothing" : actual.simpleName();
        return "This slot needs " + expectationText(slotType) + ", and that line gives " + gives + ".";
    }

    /** How a slot's own type reads in that sentence. */
    public static String expectationText(ResolvedType slotType) {
        return switch (TypeExpectation.of(slotType)) {
            case BOOLEAN -> "a yes/no";
            case NUMERIC -> "a number";
            case STRING -> "text";
            case VOID -> "nothing";
            case ANY -> "a " + slotType.simpleName();
        };
    }
}
