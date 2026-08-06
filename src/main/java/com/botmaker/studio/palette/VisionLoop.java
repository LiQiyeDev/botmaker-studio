package com.botmaker.studio.palette;

import java.util.Optional;

/**
 * The nine {@code ImageFinder} helpers that take a trailing action lambda — the closed set behind the Find
 * Image block's method dropdown, and the only vision calls that carry a body.
 *
 * <p>Each is one of {@code ifFind}/{@code whileFind}/{@code untilFind} crossed with single / {@code …Any} /
 * {@code …All}, and that cross product decides three things a caller would otherwise re-derive from the
 * method name: whether the leading argument is an {@code ImageTemplateGroup} or a single {@code ImageTemplate}
 * ({@link #group()}), what the lambda is handed ({@link #defaultParamName()}, {@code null} for a
 * {@link Runnable} body), and whether the call returns a boolean ({@link #returnsBoolean()}).
 *
 * <p>It is an enum rather than a list of literals because the set had six spellings. {@code LambdaCallBlock}
 * owned the authoritative table; {@code MatchesGroupScope} kept a second, hand-written
 * {@code Set.of("ifFindAny","whileFindAny","ifFindAll","whileFindAll")} — which is not an independent fact but
 * exactly {@link #handsOverMatches()}, i.e. the group forms that pass a parameter — and {@code BlockCatalog},
 * {@code LambdaCallHandler}, {@code MatchesSwitchHandler} and {@code BlockConverter} each named individual
 * methods. Adding a tenth helper to the SDK meant finding all six.
 *
 * <p>The parse is total ({@link #fromMethodName} → empty) because it is asked of arbitrary source: a call
 * named {@code ifFindNearest} by a future SDK simply isn't one of these, which is a different answer from an
 * error.
 */
public enum VisionLoop {

    IF_FIND("ifFind", false, VisionLoop.MATCH_PARAM),
    IF_FIND_ANY("ifFindAny", true, VisionLoop.MATCHES_PARAM),
    IF_FIND_ALL("ifFindAll", true, VisionLoop.MATCHES_PARAM),
    WHILE_FIND("whileFind", false, VisionLoop.MATCH_PARAM),
    WHILE_FIND_ANY("whileFindAny", true, VisionLoop.MATCHES_PARAM),
    WHILE_FIND_ALL("whileFindAll", true, VisionLoop.MATCHES_PARAM),
    /**
     * The {@code until…} forms loop <em>while nothing is found</em>, so there is nothing to hand the body —
     * they are the only parameterless forms left. The group forms used to be parameterless too, on the
     * reasoning that "every template is present" has no single meaningful {@code MatchResult}; the SDK's
     * {@code Matches} value answered that, and they now take a {@code Consumer<Matches>}.
     */
    UNTIL_FIND("untilFind", false, null),
    UNTIL_FIND_ANY("untilFindAny", true, null),
    UNTIL_FIND_ALL("untilFindAll", true, null);

    /**
     * The single-template forms hand over the one hit; the group forms hand over the whole combination.
     * Referenced above through the type name because a simple name would be an illegal forward reference —
     * the constants are declared after the enum constants that use them, as Java requires.
     */
    static final String MATCH_PARAM = "match";
    static final String MATCHES_PARAM = "found";

    /** The prefix of the forms that return a boolean rather than looping. */
    private static final String CONDITIONAL_PREFIX = "if";

    private final String methodName;
    private final boolean group;
    private final String defaultParamName;

    VisionLoop(String methodName, boolean group, String defaultParamName) {
        this.methodName = methodName;
        this.group = group;
        this.defaultParamName = defaultParamName;
    }

    /** The {@code ImageFinder} method this form calls — what crosses into the generated source. */
    public String methodName() {
        return methodName;
    }

    /** Whether the leading argument is an {@code ImageTemplateGroup} rather than a single {@code ImageTemplate}. */
    public boolean group() {
        return group;
    }

    /** The lambda parameter's default name, or {@code null} when the body takes none. */
    public String defaultParamName() {
        return defaultParamName;
    }

    /** Whether the body is handed a value at all. */
    public boolean hasParam() {
        return defaultParamName != null;
    }

    /**
     * Whether the body is handed a {@code Matches} — the group forms that pass a parameter. This is the set
     * whose body is worth seeding with a combination switch: the single-template forms hand over one
     * {@code MatchResult}, which has no combination to test, and {@code untilFind…} hands over nothing.
     */
    public boolean handsOverMatches() {
        return group && hasParam();
    }

    /** Whether the call evaluates to a boolean ({@code if…}) rather than being a void loop. */
    public boolean returnsBoolean() {
        return methodName.startsWith(CONDITIONAL_PREFIX);
    }

    /** The form named {@code methodName}, or empty — the source being read may call anything at all. */
    public static Optional<VisionLoop> fromMethodName(String methodName) {
        if (methodName == null) {
            return Optional.empty();
        }
        for (VisionLoop loop : values()) {
            if (loop.methodName.equals(methodName)) {
                return Optional.of(loop);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code methodName} is one of the forms that hand their lambda a {@code Matches}. */
    public static boolean handsOverMatches(String methodName) {
        return fromMethodName(methodName).filter(VisionLoop::handsOverMatches).isPresent();
    }
}
