package com.botmaker.studio.palette;

import java.util.Optional;

/**
 * What one branch of a {@code Matches} switch tests: {@code m.hasAny(…)} or {@code m.hasAll(…)}.
 *
 * <p>A two-member set that used to travel as a {@code boolean all} — through the {@code Guard} record, three
 * handler signatures and a {@code CodeEditor} method — with {@code all ? "hasAll" : "hasAny"} re-derived at
 * four write sites and the caption {@code all ? "all of" : "any of"} at three more. A boolean parameter also
 * says nothing at the call site: {@code addCase(cu, code, stmt, false, paths)} reads as a flag, where
 * {@code addCase(cu, code, stmt, ANY, paths)} reads as the branch it adds.
 */
public enum MatchesCheck {

    /** Runs when at least one of the branch's images was found. */
    ANY("hasAny", "any of"),
    /** Runs only when every one of the branch's images was found. */
    ALL("hasAll", "all of");

    private final String methodName;
    private final String label;

    MatchesCheck(String methodName, String label) {
        this.methodName = methodName;
        this.label = label;
    }

    /** The {@code Matches} method the guard calls — what crosses into the generated source. */
    public String methodName() {
        return methodName;
    }

    /** The words the block shows on its toggle and in the branch caption. */
    public String label() {
        return label;
    }

    /** The check a UI toggle's selected state means ({@code true} = "all of"). */
    public static MatchesCheck of(boolean all) {
        return all ? ALL : ANY;
    }

    /** The check {@code methodName} names, or empty for any other method — a guard may call anything. */
    public static Optional<MatchesCheck> fromMethodName(String methodName) {
        for (MatchesCheck check : values()) {
            if (check.methodName.equals(methodName)) {
                return Optional.of(check);
            }
        }
        return Optional.empty();
    }
}
