package com.botmaker.studio.palette;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the facade set and its order across the {@code SdkApi} → {@link SdkType} migration.
 *
 * <p>The compiler already guarantees every constant names a class that exists — that is the point of holding
 * a {@code Class<?>}. What it cannot catch is a wrong {@link SdkType.Role}: marking a facade {@code VALUE}
 * drops it out of the menus and out of SDK-call recognition silently, and reordering the constants silently
 * reorders the insert menu. Both are one-character edits, so both are spelled out here.
 */
class SdkTypeTest {

    /** The exact list the removed {@code SdkApi.FACADE_CLASSES} carried, in its order. */
    private static final List<String> EXPECTED_FACADES = List.of(
            "Mouse", "Keyboard", "Wait", "ImageFinder", "ImageClicker", "ImageWaiter", "Pixel", "Text",
            "Vision", "BotSettings", "Debug", "Session", "Game", "Target", "Emulators", "Bot",
            "Watchdog", "PopupGuard", "Activity", "Source", "Window", "Time");

    /**
     * The set the removed {@code SdkApi.MENU_HIDDEN} carried, minus {@code Bots}: SDK 1.1.0 moved the whole
     * observation stack into {@code com.botmaker.sdk.internal}, so there is no longer an SDK call to hide.
     */
    private static final List<String> EXPECTED_HIDDEN =
            List.of("Window", "Watchdog", "PopupGuard", "Debug", "Session");

    @Test
    void facadeSetAndOrderAreUnchanged() {
        assertEquals(EXPECTED_FACADES, SdkType.FACADE_NAMES);
    }

    @Test
    void menuFacadesAreTheFacadesMinusTheHiddenOnes() {
        List<String> expected = EXPECTED_FACADES.stream()
                .filter(name -> !EXPECTED_HIDDEN.contains(name))
                .toList();
        assertEquals(expected, SdkType.MENU_FACADES.stream().map(SdkType::simpleName).toList());
    }

    @Test
    void hiddenFacadesAreStillRecognizedAsSdkCalls() {
        for (String hidden : EXPECTED_HIDDEN) {
            assertTrue(SdkType.isFacadeClass(hidden), hidden + " must still be recognized as an SDK call");
        }
    }

    @Test
    void valueTypesAreNotFacades() {
        assertFalse(SdkType.isFacadeClass("ImageTemplate"));
        assertFalse(SdkType.isFacadeClass("Point"));
        // Screen is in the enum (it is a real api class) but is deliberately not a user-facing facade.
        assertFalse(SdkType.isFacadeClass("Screen"));
    }

    /**
     * The FQNs the import path depends on. These cannot be derived from the simple name — that is the whole
     * reason the enum holds a {@link Class} — so a spot-check of one sub-packaged facade, one sub-packaged
     * value type and one root type is worth having.
     */
    @Test
    void qualifiedNamesCarryTheRealSubPackage() {
        assertEquals("com.botmaker.sdk.api.vision.ImageFinder",
                SdkType.byName("ImageFinder").orElseThrow().qualifiedName());
        assertEquals("com.botmaker.sdk.api.vision.ImageTemplate",
                SdkType.byName("ImageTemplate").orElseThrow().qualifiedName());
        assertEquals("com.botmaker.sdk.api.geometry.Point",
                SdkType.byName("Point").orElseThrow().qualifiedName());
    }

    @Test
    void byNameIsTotalAtTheUserSourceBoundary() {
        assertTrue(SdkType.byName(null).isEmpty());
        assertTrue(SdkType.byName("  ").isEmpty());
        assertTrue(SdkType.byName("MyOwnHelper").isEmpty());
        assertTrue(SdkType.byName("  Mouse  ").isPresent());
    }
}
