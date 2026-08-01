package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Pixel} precision arguments are SDK value types so their editors can be dispatched by
 * <em>type</em>. What is worth pinning is the part that fails quietly: the seed a fresh slot gets (a record
 * has no no-arg constructor, so a wrong seed is uncompilable Java in the user's project, exactly the bug
 * {@code new Color()} caused), and the source text the pickers commit.
 */
class PixelPrecisionPickerTest {

    private static String seedFor(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), true);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named(typeName));
        return seeded == null ? null : seeded.toString();
    }

    @Test
    void aFreshSlotIsSeededWithTheNamedDefaultNotAnUncompilableConstructor() {
        // `new Tolerance()` would not compile — the record has a required component. It also has to be the
        // *constant*: seeding a bare 12.0 would defeat the type, which exists so the call site says what the
        // number means.
        assertEquals("Tolerance.DEFAULT", seedFor("Tolerance"));
        assertEquals("MinPixels.DEFAULT", seedFor("MinPixels"));
    }

    @Test
    void bothArgumentsAreDispatchedByTypeSoNoOverloadTableCanGoStale() {
        // Deliberately with no enclosing-call info at all: if this passed only for a known class+index, the
        // picker would stop firing the day the SDK adds an overload.
        assertTrue(PickerRegistry.hasPicker(
                PickerContext.of(null, null, ResolvedType.named("Tolerance"))));
        assertTrue(PickerRegistry.hasPicker(
                PickerContext.of(null, null, ResolvedType.named("MinPixels"))));
    }

    @Test
    void theNamedValuesCommitAsConstantsAndTheRestAsExplicitCalls() {
        assertEquals("Tolerance.EXACT", ToleranceArgPicker.literalFor(0));
        assertEquals("Tolerance.TIGHT", ToleranceArgPicker.literalFor(5));
        assertEquals("Tolerance.DEFAULT", ToleranceArgPicker.literalFor(12));
        assertEquals("Tolerance.LOOSE", ToleranceArgPicker.literalFor(25));
        assertEquals("Tolerance.of(18)", ToleranceArgPicker.literalFor(18));

        assertEquals("MinPixels.DEFAULT", MinPixelsArgPicker.literalFor(4));
        assertEquals("MinPixels.ANY", MinPixelsArgPicker.literalFor(1));
        assertEquals("MinPixels.of(400)", MinPixelsArgPicker.literalFor(400));
    }

    @Test
    void theMinPixelsReadoutDescribesAnAreaNotAWidth() {
        // The misreading the picker exists to correct: 400 is a 20x20 patch, not a 400-wide one.
        String readout = MinPixelsArgPicker.readoutFor(400);
        assertTrue(readout.contains("px²"), readout);
        assertTrue(readout.contains("20×20"), readout);
    }
}
