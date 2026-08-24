package com.botmaker.studio.project.activity;

import com.botmaker.sdk.api.config.Wire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A duration is stored as text, so the two properties that matter are: a person can type it loosely, and what
 * comes back is always spelled one way.
 *
 * <p>Only the second half is tested here now. Reading is {@link Wire#duration}'s, in the SDK, because the
 * generated bot has to read the same text and cannot call into Studio — the grammar's own tests moved there
 * with it. What is left below is the pair working together, which is the property a user actually sees: type
 * it any way you like, the file says {@code 1m30s}.
 */
class DurationWireTest {

    @Test
    void formatEmitsOneCanonicalSpelling() {
        assertEquals("0s", DurationWire.format(0));
        assertEquals("0s", DurationWire.format(-1));
        assertEquals("250ms", DurationWire.format(250));
        assertEquals("1m30s", DurationWire.format(90_000));
        assertEquals("1h30m", DurationWire.format(5_400_000));
        assertEquals("1h1m1s1ms", DurationWire.format(3_661_001));
    }

    /** The point of the canonical form: however it was typed, what lands in the file is stable. */
    @ParameterizedTest
    @ValueSource(strings = {"90s", "1m30s", "  90 S ", "90000ms", "90000"})
    void everySpellingOfNinetySecondsFormatsTheSame(String text) {
        assertEquals("1m30s", DurationWire.format(Wire.duration(text).toMillis()));
    }

    /** Nothing usable spells as {@code 0s} rather than as an exception or an empty field. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "s", "10x", "-5s"})
    void anythingUnusableSpellsAsZero(String text) {
        assertEquals("0s", DurationWire.format(Wire.duration(text).toMillis()));
    }
}
