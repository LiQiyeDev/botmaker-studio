package com.botmaker.studio.services;

import com.botmaker.studio.services.ImageTemplateLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ImageTemplateLibrary#sanitizeName} — the shared naming rule used by both the single-capture
 * prompt and the batch "name them all" dialog: trim, replace anything outside {@code [A-Za-z0-9_]} with
 * {@code _}, and lowercase. It may still return a blank string (callers reject blanks separately).
 *
 * <p>The lowercase-and-no-hyphen half is newer, and is there because a template's name is also a Java
 * constant in the generated {@code Templates} class ({@code YTUJ = "…/ytuj.png"}). Restricting the name to a
 * lowercase identifier makes name↔constant an exact round trip, which is what lets a block read
 * {@code Templates.YTUJ} back to the file it stands for. See
 * {@link com.botmaker.sdk.authoring.TemplateNames}, which is the SDK's since the generator moved there.
 */
public class ImageTemplateLibraryNameTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("btn_ok", ImageTemplateLibrary.sanitizeName("  btn_ok  "));
    }

    @Test
    void keepsAllowedCharacters() {
        assertEquals("btn_9_ok", ImageTemplateLibrary.sanitizeName("btn_9_ok"));
    }

    /** A name is lowercased and hyphens fold into underscores, so that it is a legal Java constant name. */
    @Test
    void foldsToALowercaseIdentifier() {
        assertEquals("btn_9_ok", ImageTemplateLibrary.sanitizeName("Btn-9_OK"));
        assertEquals("gold_ore", ImageTemplateLibrary.sanitizeName("Gold Ore"));
    }

    @Test
    void replacesDisallowedCharactersWithUnderscore() {
        assertEquals("a_b_c_", ImageTemplateLibrary.sanitizeName("a b.c!"));
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertEquals("", ImageTemplateLibrary.sanitizeName(null));
        assertEquals("", ImageTemplateLibrary.sanitizeName("   "));
    }
}
