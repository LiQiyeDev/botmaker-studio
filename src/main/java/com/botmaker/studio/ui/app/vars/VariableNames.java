package com.botmaker.studio.ui.app.vars;

import java.util.Collection;
import java.util.Set;

/**
 * What a local variable may be called, and why a candidate isn't.
 *
 * <p>It lives apart from the screen because the answer is a pure string question and the screen is a JavaFX
 * dialog: the rules are worth testing without a display. It is also the <em>only</em> guard left — the declare
 * block no longer renames in place, so every rename now arrives through
 * {@link com.botmaker.studio.parser.CodeEditor#renameLocalVariable}, which rewrites the use sites and would
 * happily rewrite them to {@code class} or to a name already taken. A rename that doesn't compile is worse
 * than a refused one: the editor's own re-parse fails, and the canvas the user was working on empties.
 */
public final class VariableNames {

    private VariableNames() {}

    /** Every reserved word plus the three literals, which are reserved for the same purpose. */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    /**
     * Why {@code candidate} can't replace {@code current} among {@code declared}, or null when it can.
     * {@code declared} is every name the same method already binds — its parameters as well as its locals —
     * and contains {@code current} itself, which is why a rename to its own name is not a duplicate.
     */
    public static String problem(String candidate, String current, Collection<String> declared) {
        String name = candidate == null ? "" : candidate.trim();
        if (name.isEmpty()) return "Give the variable a name.";
        if (name.equals(current)) return null;
        if (!Character.isJavaIdentifierStart(name.charAt(0)) || name.charAt(0) == '$') {
            return "A name has to start with a letter or _.";
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return "'" + name + "' can only contain letters, digits and _.";
            }
        }
        if (RESERVED.contains(name)) return "'" + name + "' is a Java keyword — pick another name.";
        if (declared != null && declared.contains(name)) {
            return "'" + name + "' is already declared in this activity's method.";
        }
        return null;
    }
}
