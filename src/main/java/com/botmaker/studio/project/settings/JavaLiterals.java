package com.botmaker.studio.project.settings;

/**
 * Java source text for a value — the one place a string becomes a quoted literal.
 *
 * <p>It exists because the settings generator writes the same string into two very different positions: the
 * static block ({@code ORE = "Iron";}) and an annotation element ({@code @Setting(value = "Iron")}). Both are
 * Java string literals with identical escaping rules, and both are written from user text that may contain a
 * quote, a backslash, a newline or — a real case, since a setting can be named from a game's own UI — a
 * character outside Latin-1. One escaper means the two cannot disagree, which is what would otherwise produce
 * a generated file that parses in one position and not the other.
 */
public final class JavaLiterals {

    private JavaLiterals() {}

    /** {@code text} as a complete Java string literal, quotes included. {@code null} becomes {@code ""}. */
    public static String string(String text) {
        return '"' + escape(text) + '"';
    }

    /**
     * The escaped <em>contents</em> of a string literal — no surrounding quotes.
     *
     * <p>Non-printable characters become {@code \\uXXXX} rather than being passed through: a raw control
     * character in a source file is legal in some positions and a compile error in others, and it is invisible
     * in a diff either way.
     */
    public static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
