package com.botmaker.studio.ui.app.dev;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.VariableWire;
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
 */
public class PickerGalleryWindowTest {

    private static final List<String> TEMPLATES = List.of("button", "logo");

    @Test
    void everyPairingIsEitherARowOrNotASentence() {
        for (BotType type : BotType.storableTypes()) {
            for (BotType.Shape shape : BotType.Shape.values()) {
                ActivityVariable variable = PickerGalleryWindow.sample(type, shape, TEMPLATES);
                boolean legal = switch (shape) {
                    case ONE -> true;
                    case ONE_OF -> type.shapeable();
                    case ANY_OF -> type.listable();
                };
                if (legal) {
                    assertNotNull(variable, type + " in " + shape + " is declarable and needs a row");
                    assertEquals(type, variable.type().type());
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
     * and they have to survive {@link VariableWire}'s normaliser, which is where a badly spelled sample dies.
     */
    @Test
    void aDeclaredSetIsAlwaysMoreThanOneDistinctValue() {
        for (BotType type : BotType.storableTypes()) {
            if (!type.shapeable()) continue;   // a closed set answers with its own constants
            ActivityVariable variable = PickerGalleryWindow.sample(type, BotType.Shape.ONE_OF, TEMPLATES);
            assertNotNull(variable);
            assertTrue(variable.options().size() >= 2,
                    type + " offers " + variable.options() + ", which is not a set to choose from");
        }
    }

    /** A closed-set type never reads the table: its choices are the enum's own constants. */
    @Test
    void aClosedSetTypeBringsItsOwnChoices() {
        assertEquals(List.of(), PickerGalleryWindow.options(BotType.DIRECTION, TEMPLATES));
        assertTrue(VariableWire.effectiveOptions(BotType.DIRECTION, List.of()).size() > 1);
    }

    /** Templates cannot be written down — they are whatever the open project happens to have. */
    @Test
    void templateChoicesComeFromTheProject() {
        assertEquals(TEMPLATES, PickerGalleryWindow.options(BotType.IMAGE_TEMPLATE, TEMPLATES));
        assertEquals(List.of(), PickerGalleryWindow.options(BotType.IMAGE_TEMPLATE, List.of()));
    }
}
