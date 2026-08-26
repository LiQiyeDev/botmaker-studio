package com.botmaker.studio.palette;

import com.botmaker.sdk.api.util.BotMaker;
import com.botmaker.studio.types.PrimitiveKind;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the three separate spellings of the read set could not check: that they agree, and with the SDK. */
class InputKindTest {

    @Test
    void everyReadNamesAMethodThatExistsOnTheSdkFacade() {
        // Studio does not read *methods* off the SDK jar to work (a bot pins its own SDK version) — but these
        // four names are written into generated source and matched when parsing it back, so a rename in the
        // SDK silently breaks the round trip. Studio compiles against the SDK, so the class literal checks it.
        Class<?> botMaker = BotMaker.class;
        for (InputKind kind : InputKind.values()) {
            boolean found = Arrays.stream(botMaker.getMethods())
                    .map(Method::getName)
                    .anyMatch(n -> n.equals(kind.method()));
            assertTrue(found, botMaker.getName() + " has no " + kind.method() + "()");
        }
    }

    @Test
    void aMarkerTokenRoundTripsAndAnUnknownOneDoesNotThrow() {
        for (InputKind kind : InputKind.values()) {
            assertEquals(Optional.of(kind), InputKind.fromMarkerToken(kind.markerToken()));
            assertEquals(Optional.of(kind), InputKind.fromMethod(kind.method()));
        }
        assertAll(
                // A newer SDK reading something this Studio predates must leave the prompt generic, not fail.
                () -> assertTrue(InputKind.fromMarkerToken("bigdecimal").isEmpty()),
                () -> assertTrue(InputKind.fromMarkerToken(null).isEmpty()),
                () -> assertTrue(InputKind.fromMethod("readLines").isEmpty()));
    }

    @Test
    void theDeclaredTypeAndItsPrimitiveFlagCannotDisagree() {
        // They were two independent constructor arguments on ScannerRead; now one is derived from the other.
        assertAll(
                () -> assertEquals("String", InputKind.LINE.typeName()),
                () -> assertTrue(!InputKind.LINE.isPrimitiveType()),
                () -> assertEquals(PrimitiveKind.INT.keyword(), InputKind.INT.typeName()),
                () -> assertTrue(InputKind.INT.isPrimitiveType()),
                () -> assertTrue(InputKind.DOUBLE.isPrimitiveType()),
                () -> assertTrue(InputKind.BOOLEAN.isPrimitiveType()));
    }

    @Test
    void everyReadCarriesBothLabelsTheEditorAndTheModalNeed() {
        for (InputKind kind : InputKind.values()) {
            assertTrue(!kind.phrase().isBlank(), kind + " has no block phrase");
            assertTrue(kind.prompt().endsWith(":"), kind + " prompt should read as a request: " + kind.prompt());
        }
    }
}
