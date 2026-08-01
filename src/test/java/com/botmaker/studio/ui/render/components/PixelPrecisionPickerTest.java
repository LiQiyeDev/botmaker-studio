package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Pixel} strictness argument is an SDK value type so its editor can be dispatched by <em>type</em>.
 * What is worth pinning is the part that fails quietly: the seed a fresh slot gets (a record has no no-arg
 * constructor, so a wrong seed is uncompilable Java in the user's project, exactly the bug {@code new Color()}
 * caused), the source text the picker commits, and that reopening it reads back what it wrote.
 */
class PixelPrecisionPickerTest {

    private static String seedFor(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), true);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named(typeName));
        return seeded == null ? null : seeded.toString();
    }

    /** Parses a bare expression the way the picker will meet it in a user's file. */
    private static PrecisionArgPicker.Settings read(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        return PrecisionArgPicker.settingsOf((Expression) parser.createAST(null));
    }

    @Test
    void aFreshSlotIsSeededWithTheNamedDefaultNotAnUncompilableConstructor() {
        // `new Precision()` would not compile — the record has required components. It also has to be the
        // *constant*: seeding a bare 12.0 would defeat the type, which exists so the call site says what the
        // number means.
        assertEquals("Precision.DEFAULT", seedFor("Precision"));
    }

    @Test
    void theArgumentIsDispatchedByTypeSoNoOverloadTableCanGoStale() {
        // Deliberately with no enclosing-call info at all: if this passed only for a known class+index, the
        // picker would stop firing the day the SDK adds an overload.
        assertTrue(PickerRegistry.hasPicker(
                PickerContext.of(null, null, ResolvedType.named("Precision"))));
    }

    @Test
    void theShortestExactFormIsCommitted() {
        // An anchor alone when the quantity gates are the ones the anchor already carries…
        assertEquals("Precision.EXACT", PrecisionArgPicker.literalFor(0, 4, 0));
        assertEquals("Precision.TIGHT", PrecisionArgPicker.literalFor(5, 4, 0));
        assertEquals("Precision.DEFAULT", PrecisionArgPicker.literalFor(12, 4, 0));
        assertEquals("Precision.LOOSE", PrecisionArgPicker.literalFor(25, 4, 0));
        // …the anchor plus a wither for whatever differs from it…
        assertEquals("Precision.TIGHT.minArea(400)", PrecisionArgPicker.literalFor(5, 400, 0));
        assertEquals("Precision.DEFAULT.minCount(2000)", PrecisionArgPicker.literalFor(12, 4, 2000));
        assertEquals("Precision.LOOSE.minArea(1).minCount(2000)", PrecisionArgPicker.literalFor(25, 1, 2000));
        // …and the factory when the tolerance is off-anchor, three-argument when both gates are non-standard.
        assertEquals("Precision.of(18)", PrecisionArgPicker.literalFor(18, 4, 0));
        assertEquals("Precision.of(18, 400, 2000)", PrecisionArgPicker.literalFor(18, 400, 2000));
    }

    @Test
    void everyCommittedFormReadsBackAsTheValuesItWasGiven() {
        // The property that makes the editor safe to open on hand-written code: it must not quietly reset a
        // setting it failed to parse. A wither chain is the form most likely to be typed by hand, and the one
        // a naive "is it a named constant?" reader would silently flatten back to the anchor.
        assertEquals(new PrecisionArgPicker.Settings(5.0, 4, 0), read("Precision.TIGHT"));
        assertEquals(new PrecisionArgPicker.Settings(5.0, 400, 0), read("Precision.TIGHT.minArea(400)"));
        assertEquals(new PrecisionArgPicker.Settings(18.0, 400, 2000), read("Precision.of(18, 400, 2000)"));
        assertEquals(new PrecisionArgPicker.Settings(18.0, 400, 2000),
                read("Precision.of(18).minArea(400).minCount(2000)"));
        assertEquals(new PrecisionArgPicker.Settings(3.0, 1, 50),
                read("Precision.DEFAULT.tolerance(3).minArea(1).minCount(50)"));
        // Fully qualified, as a hand-written file may well be.
        assertEquals(new PrecisionArgPicker.Settings(25.0, 40, 0),
                read("com.botmaker.sdk.api.vision.Precision.LOOSE.minArea(40)"));
        // Something the editor cannot read falls back to the SDK's own default rather than to zeros, which
        // would be a tolerance of "exact" and an area of "any" — the two most damaging values to invent.
        assertEquals(new PrecisionArgPicker.Settings(12.0, 4, 0), read("someVariable"));
    }

    @Test
    void onlyTheKnobsTheCallCanActOnAreOffered() {
        // The SDK collapsed colour and quantity into one type, which means some calls are handed fields with
        // no effect. Their javadoc says so; this is where it becomes something the user cannot get wrong.
        assertTrue(PrecisionArgPicker.knobsFor("matchesAt").tolerance());
        assertFalse(PrecisionArgPicker.knobsFor("matchesAt").quantity());
        assertFalse(PrecisionArgPicker.knobsFor("coverage").quantity());

        assertFalse(PrecisionArgPicker.knobsFor("findInRange").tolerance());
        assertTrue(PrecisionArgPicker.knobsFor("findInRange").quantity());

        // find/findAll/waitFor use all three — and so does an unrecognised name, because hiding a knob we are
        // unsure about would silently strand a setting the user cannot then reach.
        for (String m : new String[]{"find", "findAll", "waitFor", "waitForGone", "somethingNew", null}) {
            assertTrue(PrecisionArgPicker.knobsFor(m).tolerance(), m + " should offer the tolerance");
            assertTrue(PrecisionArgPicker.knobsFor(m).quantity(), m + " should offer the quantity gates");
        }
    }

    @Test
    void theAreaReadoutDescribesAnAreaNotAWidth() {
        // The misreading the preview exists to correct: 400 is a 20x20 patch, not a 400-wide one.
        String readout = PrecisionArgPicker.readoutFor(400);
        assertTrue(readout.contains("px²"), readout);
        assertTrue(readout.contains("20×20"), readout);
    }
}
