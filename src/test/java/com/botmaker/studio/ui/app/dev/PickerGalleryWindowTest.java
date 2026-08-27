package com.botmaker.studio.ui.app.dev;

import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ValueWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's rows, without a scene: which type-and-shape pairings get a row, and what each row is seeded
 * with. The widgets themselves are what the screen is for — this only guards the seed, because a row whose
 * choices all normalise to the same string looks like a working picker and tests nothing.
 *
 * <p>The types come from {@link ValueWire#registered()} rather than a constant list, for the same reason the
 * screen enumerates them that way: the vocabulary is open, and a fixed list would stop covering the editors
 * the moment a second plugin registers one.
 */
public class PickerGalleryWindowTest {

    private static final List<String> TEMPLATES = List.of("button", "logo");

    @Test
    void everyPairingIsEitherARowOrNotASentence() {
        for (ValueType type : ValueWire.registered()) {
            for (ValueShape shape : ValueShape.values()) {
                ActivityVariable variable = PickerGalleryWindow.sample(type, shape, TEMPLATES);
                // The only pairing ValueChoice corrects away — a declared subset of a type that is already a
                // closed set. Everything else is a sentence, lists of a closed set included.
                boolean legal = shape != ValueShape.ONE_OF || type.shapeable();
                if (legal) {
                    assertNotNull(variable, type + " in " + shape + " is declarable and needs a row");
                    assertEquals(type.id(), variable.type().type().id());
                    assertEquals(shape, variable.type().shape());
                } else {
                    assertNull(variable, type + " in " + shape + " is not a sentence anyone can write");
                }
            }
        }
    }

    /**
     * The point of the hand-written table: three radio buttons all reading {@code "0,0"} would look like a
     * working picker. Every type that can carry an author-written set has to offer distinguishable values —
     * and they have to survive {@link ValueWire}'s normaliser, which is where a badly spelled sample dies.
     *
     * <p>A type nothing here has a sample for is skipped rather than failed: the table is keyed by id over an
     * open vocabulary, so a plugin's own type legitimately falls through to no samples.
     */
    @Test
    void aDeclaredSetIsAlwaysMoreThanOneDistinctValue() {
        for (ValueType type : ValueWire.registered()) {
            if (!type.shapeable()) continue;   // a closed set answers with its own constants
            if (PickerGalleryWindow.options(type, TEMPLATES).isEmpty()) continue;
            ActivityVariable variable = PickerGalleryWindow.sample(type, ValueShape.ONE_OF, TEMPLATES);
            assertNotNull(variable);
            assertTrue(variable.options().size() >= 2,
                    type + " offers " + variable.options() + ", which is not a set to choose from");
        }
    }

    /** A closed-set type never reads the table: its choices are the enum's own constants. */
    @Test
    void aClosedSetTypeBringsItsOwnChoices() {
        ValueType direction = ValueWire.type("DIRECTION");
        assertEquals(List.of(), PickerGalleryWindow.options(direction, TEMPLATES));
        assertTrue(ValueWire.effectiveOptions(direction, List.of()).size() > 1);
    }

    /** Templates cannot be written down — they are whatever the open project happens to have. */
    @Test
    void templateChoicesComeFromTheProject() {
        ValueType template = ValueWire.type("IMAGE_TEMPLATE");
        assertEquals(TEMPLATES, PickerGalleryWindow.options(template, TEMPLATES));
        assertEquals(List.of(), PickerGalleryWindow.options(template, List.of()));
    }
}
