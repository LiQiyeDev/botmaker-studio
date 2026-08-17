package com.botmaker.studio.project.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The escaper is the difference between a generated file that compiles and one that does not, for text the
 * editor typed rather than text we chose. Every case below has produced a broken generated file somewhere.
 */
class JavaLiteralsTest {

    @Test
    void ordinaryTextIsJustQuoted() {
        assertEquals("\"Iron\"", JavaLiterals.string("Iron"));
        assertEquals("\"\"", JavaLiterals.string(""));
        assertEquals("\"\"", JavaLiterals.string(null));
    }

    @Test
    void theTwoCharactersThatWouldEndTheLiteralAreEscaped() {
        assertEquals("\"say \\\"hi\\\"\"", JavaLiterals.string("say \"hi\""));
        assertEquals("\"C:\\\\games\"", JavaLiterals.string("C:\\games"));
    }

    /** A backslash before a quote must not consume the escape of the quote. */
    @Test
    void aBackslashBeforeAQuoteSurvives() {
        assertEquals("\"\\\\\\\"\"", JavaLiterals.string("\\\""));
    }

    @Test
    void whitespaceThatWouldBreakTheLineIsEscaped() {
        assertEquals("\"a\\nb\"", JavaLiterals.string("a\nb"));
        assertEquals("\"a\\r\\nb\"", JavaLiterals.string("a\r\nb"));
        assertEquals("\"a\\tb\"", JavaLiterals.string("a\tb"));
    }

    /** A control character is legal in some positions and a compile error in others — and invisible in a diff. */
    @Test
    void controlCharactersBecomeUnicodeEscapes() {
        assertEquals("\"a\\u0000b\"", JavaLiterals.string("a\u0000b"));
        assertEquals("\"\\u007f\"", JavaLiterals.string("\u007f"));
    }

    /** Printable non-ASCII is left alone: the generated files are written and read as UTF-8. */
    @Test
    void accentsAndSymbolsPassThrough() {
        assertEquals("\"café\"", JavaLiterals.string("café"));
        assertEquals("\"⛏\"", JavaLiterals.string("⛏"));
    }
}
