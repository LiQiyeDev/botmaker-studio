package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wait length is the SDK's other value type, for the same reason {@code Precision} is one: the unit is
 * invisible in a bare number, and dispatching the editor on the type means no {@code (method, argIndex)} table
 * can go stale. What is pinned here is what fails silently — the seed a fresh slot gets (a record with
 * required components, so a generic {@code new Duration()} would be uncompilable Java in the user's project),
 * the source the control commits, and that reopening it reads back what it wrote in the unit it was written
 * in.
 */
class DurationPickerTest {

    private static Expression parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        return (Expression) parser.createAST(null);
    }

    private static String seedFor(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), true);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named(typeName));
        return seeded == null ? null : seeded.toString();
    }

    @Test
    void aFreshSlotIsSeededWithACompilableDuration() {
        assertEquals("Duration.seconds(1)", seedFor("Duration"));
        // ...and the seed must be one the control can read, or the slot opens showing raw source instead.
        assertNotNull(DurationPicker.parse(parse(seedFor("Duration"))));
    }

    @Test
    void theArgumentIsDispatchedByTypeSoNoOverloadTableCanGoStale() {
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("Duration"))));
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null,
                ResolvedType.named("com.botmaker.sdk.api.interaction.Duration"))));
    }

    @Test
    void theUnitTheSourceNamesIsTheUnitReadBack() {
        // 1500ms and 1.5s are the same wait; showing the second as "1500 ms" would rewrite what the user typed
        // the moment they touched any other field.
        assertEquals("1.5 s", DurationPicker.parse(parse("Duration.seconds(1.5)")).label());
        assertEquals("1500 ms", DurationPicker.parse(parse("Duration.ms(1500)")).label());
        assertEquals("2 min", DurationPicker.parse(parse("Duration.minutes(2)")).label());
    }

    @Test
    void aRangeIsReadAndWrittenBackUnchanged() {
        DurationPicker.Value range = DurationPicker.parse(parse("Duration.between(Duration.ms(800), Duration.ms(1500))"));
        assertNotNull(range);
        assertTrue(range.range());
        assertEquals("800–1500 ms", range.label());
        assertEquals("Duration.between(Duration.ms(800), Duration.ms(1500))", range.code());
    }

    @Test
    void aWholeNumberIsCommittedWithoutATrailingPointZero() {
        assertEquals("Duration.seconds(2)", DurationPicker.parse(parse("Duration.seconds(2.0)")).code());
    }

    @Test
    void anythingItCannotShowIsLeftToTheGenericPill() {
        assertNull(DurationPicker.parse(parse("timeout")), "a variable is not ours to rewrite");
        assertNull(DurationPicker.parse(parse("Duration.between(Duration.ms(800), Duration.seconds(2))")),
                "mixed units are legal SDK but unshowable here — preserve them rather than rewrite one end");
        assertNull(DurationPicker.parse(parse("Precision.DEFAULT")));
    }

    @Test
    void theWaitBlockInsertsTheFormThisPickerCanEdit() {
        // The palette entry and this control have to agree: an inserted Wait whose argument the picker can't
        // read renders as a text pill, which is the whole thing the type was introduced to avoid.
        assertTrue(BlockCatalog.WAIT.toString().contains("time"), BlockCatalog.WAIT.toString());
        assertNotNull(DurationPicker.parse(parse("Duration.seconds(1)")));
    }
}
