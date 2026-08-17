package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name↔constant mapping the generated {@code Templates} class rests on.
 *
 * <p>The property that matters is the round trip: Studio writes {@code Templates.YTUJ} into the source and has
 * to read the same file back out of it when it renders the block. Anything that only maps one way would give a
 * picker with no thumbnail and a chip with no name, so both directions are asserted together — and a name that
 * cannot round-trip is required to have <em>no</em> constant rather than a lossy one.
 */
class TemplateConstantsTest {

    @Test
    void aLowercaseNameRoundTripsThroughItsConstant() {
        assertAll(
                () -> assertEquals("YTUJ", TemplateConstants.constantFor("ytuj")),
                () -> assertEquals("ytuj", TemplateConstants.baseNameFor("YTUJ")),
                () -> assertEquals("GOLD_ORE", TemplateConstants.constantFor("gold_ore")),
                () -> assertEquals("gold_ore", TemplateConstants.baseNameFor("GOLD_ORE")),
                () -> assertEquals("BUTTON2", TemplateConstants.constantFor("button2")),
                () -> assertEquals("src/main/resources/images/ytuj.png",
                        TemplateConstants.pathForConstant("YTUJ")),
                () -> assertEquals("YTUJ",
                        TemplateConstants.constantForPath("src/main/resources/images/ytuj.png")));
    }

    /**
     * A name from before the lowercase rule gets no constant at all. That is the whole design: rather than
     * inventing a lossy constant and guessing the file back, such a template keeps its string literal, which
     * still reads and writes correctly.
     */
    @Test
    void aNameThatCannotRoundTripHasNoConstant() {
        assertAll(
                () -> assertNull(TemplateConstants.constantFor("Ytuj"), "mixed case would not come back"),
                () -> assertNull(TemplateConstants.constantFor("gold-ore"), "a hyphen is not an identifier"),
                () -> assertNull(TemplateConstants.constantFor("2fast"), "an identifier cannot start with a digit"),
                () -> assertNull(TemplateConstants.constantFor("")),
                () -> assertNull(TemplateConstants.constantFor(null)),
                () -> assertNull(TemplateConstants.baseNameFor("Mixed"), "not a constant we would have written"),
                () -> assertNull(TemplateConstants.constantForPath("src/main/resources/other/ytuj.png")),
                () -> assertNull(TemplateConstants.constantForPath("src/main/resources/images/ytuj.jpg")));
    }

    @Test
    void theGeneratedClassDeclaresOneConstantPerNameableTemplate() {
        String source = TemplateConstants.generateSource("mybot", List.of("gold_ore", "ytuj", "Legacy-One"));

        assertAll(
                () -> assertTrue(source.startsWith("package com.mybot;"), source),
                () -> assertTrue(source.contains(
                        "public static final String GOLD_ORE = \"src/main/resources/images/gold_ore.png\";")),
                () -> assertTrue(source.contains(
                        "public static final String YTUJ = \"src/main/resources/images/ytuj.png\";")),
                // Sorted, so regenerating after a capture is a one-line diff rather than a reshuffle.
                () -> assertTrue(source.indexOf("GOLD_ORE") < source.indexOf("YTUJ")),
                // The one it could not name is accounted for in the file, not silently missing.
                () -> assertTrue(source.contains("// No constant for: Legacy-One"), source));
    }

    /** An empty class, not a missing one — a hand-written import must survive deleting the last template. */
    @Test
    void aProjectWithNoTemplatesStillGetsTheClass() {
        String source = TemplateConstants.generateSource("mybot", List.of());

        assertAll(
                () -> assertTrue(source.contains("public final class Templates {")),
                () -> assertTrue(source.contains("private Templates() {}")),
                () -> assertTrue(source.trim().endsWith("}")));
    }
}
