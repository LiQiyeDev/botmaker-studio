package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemVerTest {

    @Test
    void bumpsPatch() {
        assertEquals("1.0.1", SemVer.next("1.0.0"));
        assertEquals("2.3.10", SemVer.next("2.3.9"));
    }

    @Test
    void preservesVPrefix() {
        assertEquals("v2.3.10", SemVer.next("v2.3.9"));
    }

    @Test
    void trimsWhitespace() {
        assertEquals("1.0.1", SemVer.next("  1.0.0 "));
    }

    @Test
    void fallsBackToFirstOnNullBlankOrGarbage() {
        assertEquals(SemVer.FIRST, SemVer.next(null));
        assertEquals(SemVer.FIRST, SemVer.next(""));
        assertEquals(SemVer.FIRST, SemVer.next("not-a-version"));
        assertEquals(SemVer.FIRST, SemVer.next("1.0")); // not MAJOR.MINOR.PATCH
        assertEquals(SemVer.FIRST, SemVer.next("1.0.0-beta"));
    }

    @Test
    void minorAndMajorBumpsResetLowerComponents() {
        assertEquals("1.3.0", SemVer.nextMinor("1.2.3"));
        assertEquals("2.0.0", SemVer.nextMajor("1.2.3"));
        assertEquals("v2.0.0", SemVer.nextMajor("v1.9.9"));
        assertEquals(SemVer.FIRST, SemVer.nextMinor("garbage"));
    }

    @Test
    void comparesByNumericCore() {
        assertTrue(SemVer.compare("1.0.10", "1.0.9") > 0); // numeric, not lexical
        assertTrue(SemVer.compare("2.0.0", "1.9.9") > 0);
        assertEquals(0, SemVer.compare("v1.2.3", "1.2.3")); // prefix ignored
        assertTrue(SemVer.compare("invalid", "1.0.0") < 0);
    }

    @Test
    void isGreaterEnforcesMonotonicVersions() {
        assertTrue(SemVer.isGreater("1.0.1", "1.0.0"));
        assertFalse(SemVer.isGreater("1.0.0", "1.0.0"));
        assertFalse(SemVer.isGreater("0.9.0", "1.0.0"));
        assertTrue(SemVer.isGreater("2.0.0", "")); // no baseline → any valid passes
        assertFalse(SemVer.isGreater("nope", "1.0.0")); // invalid candidate never passes
    }

    @Test
    void validation() {
        assertTrue(SemVer.isValid("1.0.0"));
        assertTrue(SemVer.isValid("v10.20.30"));
        assertFalse(SemVer.isValid("1.0"));
        assertFalse(SemVer.isValid(null));
        assertFalse(SemVer.isValid("1.0.0-rc1"));
    }
}
