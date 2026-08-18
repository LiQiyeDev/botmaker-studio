package com.botmaker.studio.project.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A duration is stored as text, so the two properties that matter are: a person can type it loosely, and what
 * comes back is always spelled one way.
 */
class DurationWireTest {

    @Test
    void theUnitsMeanWhatTheySay() {
        assertEquals(250, DurationWire.parse("250ms", -1));
        assertEquals(90_000, DurationWire.parse("90s", -1));
        assertEquals(300_000, DurationWire.parse("5m", -1));
        assertEquals(3_600_000, DurationWire.parse("1h", -1));
        assertEquals(5_400_000, DurationWire.parse("1h30m", -1));
    }

    /** "ms" starts with "m"; reading it as minutes would be a 60,000× error, so it gets its own test. */
    @Test
    void msIsNotMinutes() {
        assertEquals(500, DurationWire.parse("500ms", -1));
        assertEquals(30_500, DurationWire.parse("500ms30s", -1));
        assertEquals(30_000_000, DurationWire.parse("500m", -1));   // the same digits, 60,000× apart
    }

    @Test
    void spacingAndCaseDoNotMatter() {
        assertEquals(5_400_000, DurationWire.parse("1H 30M", -1));
        assertEquals(5_400_000, DurationWire.parse("  1h30m  ", -1));
    }

    /** A bare number is milliseconds, so a value written before units existed still reads. */
    @Test
    void aBareNumberIsMilliseconds() {
        assertEquals(500, DurationWire.parse("500", -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "s", "10x", "-5s", "99999999999999s"})
    void anythingUnusableFallsBack(String text) {
        assertEquals(-1, DurationWire.parse(text, -1));
    }

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
        assertEquals("1m30s", DurationWire.format(DurationWire.parse(text, -1)));
    }
}
