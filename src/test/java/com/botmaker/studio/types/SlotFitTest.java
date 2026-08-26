package com.botmaker.studio.types;

import com.botmaker.sdk.api.vision.ImageTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TypeExpectation#fits} — the verdict a drag over an expression slot needs.
 *
 * <p>Tested here rather than through the drag manager because that is the whole point of the split: the
 * question "may this value fill this slot" is pure type reasoning, so it can be pinned down without a scene
 * graph, a dragboard or an FX thread. The manager keeps only the parts that genuinely need JavaFX.
 *
 * <p>The two directions both matter. Refusing too much is the worse failure — a slot that says no to a valid
 * block reads as broken, which is exactly the state this phase found the expression slots in — so the
 * unresolved cases are pinned as accepted, deliberately.
 */
class SlotFitTest {

    @Test
    void anUnresolvedTypeOnEitherSideIsAccepted() {
        // A file that hasn't resolved yet must not start refusing every drop.
        assertTrue(TypeExpectation.fits(ResolvedType.UNKNOWN, ResolvedType.of(JdkType.STRING)));
        assertTrue(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.UNKNOWN));
        assertTrue(TypeExpectation.fits(null, null));
    }

    @Test
    void aConditionSlotTakesOnlyBooleans() {
        assertTrue(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.BOOLEAN));
        assertTrue(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.of(JdkType.BOOLEAN)), "the box counts");
        assertFalse(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.of(JdkType.STRING)));
        assertFalse(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.INT));
    }

    @Test
    void aNumericSlotTakesAnyNumberAndNothingElse() {
        assertTrue(TypeExpectation.fits(ResolvedType.INT, ResolvedType.DOUBLE), "the category is numeric, not int");
        assertFalse(TypeExpectation.fits(ResolvedType.INT, ResolvedType.of(JdkType.STRING)));
    }

    /**
     * The reported bug: {@code ImageClicker.click(ore);} dropped into an {@code if} condition. Its statement is
     * an expression statement, so it advertises itself as slot-fillable — and until the return type was
     * resolved it advertised {@code UNKNOWN}, which every slot accepts. Void is now refused by all of them,
     * including a slot whose own type is unknown, because there is nothing a statement could be the value of.
     */
    @Test
    void nothingTakesAVoidCall() {
        assertFalse(TypeExpectation.fits(ResolvedType.BOOLEAN, ResolvedType.VOID));
        assertFalse(TypeExpectation.fits(ResolvedType.UNKNOWN, ResolvedType.VOID), "not even an unknown slot");
        assertFalse(TypeExpectation.fits(null, ResolvedType.VOID));
        assertFalse(TypeExpectation.fits(ResolvedType.of(ImageTemplate.class), ResolvedType.named("void")),
                "the dragboard carries the name, so the name has to answer the same way");
    }

    @Test
    void anObjectSlotComparesNames() {
        // Neither side falls into one of the four categories, so the name is all there is to go on. The
        // simple name counts because a slot is routinely declared with the bare identifier the source wrote.
        ResolvedType template = ResolvedType.of(ImageTemplate.class);
        assertTrue(TypeExpectation.fits(template, ResolvedType.named(ImageTemplate.class.getSimpleName())));
        assertFalse(TypeExpectation.fits(template, ResolvedType.of(JdkType.STRING)));
        assertFalse(TypeExpectation.fits(template, ResolvedType.named("com.example.Other")));
    }

    /**
     * The wording, and the fact that there is only one copy of it. The drag-over tooltip and the status line
     * the drop publishes read from {@link SlotFit#refusal} — a drag can be waved through on a type the file
     * had not resolved and still be refused on landing, and when that happens the two have to say the same
     * thing or the second one reads as a different, unexplained failure.
     */
    @Test
    void everyRefusalIsASentenceAndFittingIsSilence() {
        assertNull(SlotFit.refusal(ResolvedType.BOOLEAN, ResolvedType.BOOLEAN));
        assertNull(SlotFit.refusal(ResolvedType.UNKNOWN, ResolvedType.of(JdkType.STRING)));
        assertEquals("This line produces nothing, so it cannot fill a slot.",
                SlotFit.refusal(ResolvedType.BOOLEAN, ResolvedType.VOID));
        assertEquals("This slot needs a yes/no, and that line gives int.",
                SlotFit.refusal(ResolvedType.BOOLEAN, ResolvedType.named("int")));
        assertEquals("This slot needs a ImageTemplate, and that line gives String.",
                SlotFit.refusal(ResolvedType.of(ImageTemplate.class), ResolvedType.of(JdkType.STRING)));
    }
}
