package com.botmaker.studio.palette;

import com.botmaker.studio.types.ResolvedType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the editor reaches the SDK surface <em>through</em> {@link SdkType} rather than around it — the
 * properties the strings it replaced could not have.
 */
class SdkTypeUseTest {

    @Test
    void everyCatalogCallNamesARealFacade() {
        // Was checked at display time (StatementMenu filtered on SdkType.isFacadeClass(className)) and nowhere
        // else, so a typo'd receiver produced a catalog entry that inserted uncompilable source and merely
        // failed to appear in the SDK submenus. The type makes it unrepresentable; this pins the intent.
        for (BlockType block : BlockCatalog.all()) {
            switch (block) {
                case BlockType.LibraryCall l -> assertTrue(l.facade().isFacade(),
                        l.id() + " calls " + l.facade() + ", which is not a facade");
                case BlockType.LambdaCall l -> assertTrue(l.facade().isFacade(),
                        l.id() + " calls " + l.facade() + ", which is not a facade");
                default -> { }
            }
        }
    }

    @Test
    void aQualifiedNameIsSomethingNoSimpleNameCouldHaveDerived() {
        assertAll(
                () -> assertEquals("com.botmaker.sdk.api.vision.ImageTemplate",
                        ResolvedType.of(SdkType.IMAGE_TEMPLATE).qualifiedName()),
                () -> assertEquals("ImageTemplate", ResolvedType.of(SdkType.IMAGE_TEMPLATE).simpleName()),
                // The three sub-packages that make the FQNs underivable, and the reason ImportManager has to
                // consult this enum first: Point/Window/Text all collide with java.awt.
                () -> assertEquals("com.botmaker.sdk.api.geometry.Point", SdkType.POINT.qualifiedName()),
                () -> assertEquals("com.botmaker.sdk.api.capture.Window", SdkType.WINDOW.qualifiedName()),
                () -> assertEquals("com.botmaker.sdk.api.interaction.Mouse", SdkType.MOUSE.qualifiedName()));
    }

    @Test
    void aTypeMatchesWhetherTheSourceQualifiedItOrNot() {
        // The four pickers that each spelled this test out matched on both forms, because a slot's type
        // reaches them resolved (qualified) from the analyzer and bare from an unresolved lambda parameter.
        assertAll(
                () -> assertTrue(ResolvedType.named("ImageTemplate").is(SdkType.IMAGE_TEMPLATE)),
                () -> assertTrue(ResolvedType.of(SdkType.IMAGE_TEMPLATE).is(SdkType.IMAGE_TEMPLATE)),
                () -> assertFalse(ResolvedType.named("ImageTemplateGroup").is(SdkType.IMAGE_TEMPLATE),
                        "the group is a different slot — the single-template picker must not claim it"),
                () -> assertFalse(ResolvedType.UNKNOWN.is(SdkType.IMAGE_TEMPLATE)));
    }

    @Test
    void theFavouriteOverloadKeyIsUnchangedByTheRetyping() {
        // ProjectSettings persists "<facade>#<method>" keys; the receiver became an SdkType but the key it
        // builds must still be the string already written into existing projects' settings files.
        assertEquals(List.of("Mouse", "Keyboard", "Wait", "Game", "ImageFinder"),
                List.of(SdkType.MOUSE, SdkType.KEYBOARD, SdkType.WAIT, SdkType.GAME, SdkType.IMAGE_FINDER)
                        .stream().map(SdkType::simpleName).toList());
    }
}
