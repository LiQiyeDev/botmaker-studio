package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The calendar for a {@code LocalDate} slot. Both source shapes have to read back, because the two exist for
 * different reasons: {@code LocalDate.of(y, m, d)} is what this control writes, and
 * {@code LocalDate.parse("…")} is what a person writes by hand — and what the editor showed as a bare pill
 * before there was a picker at all.
 */
class DateArgPickerTest {

    private static Expression parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        return (Expression) parser.createAST(null);
    }

    @Test
    void aDateSlotGetsThisPickerRatherThanTheGenericPill() {
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("LocalDate"))));
        assertTrue(PickerRegistry.hasPicker(
                PickerContext.of(null, null, ResolvedType.named("java.time.LocalDate"))));
    }

    @Test
    void bothSourceShapesReadBack() {
        assertEquals(LocalDate.of(2026, 8, 20), DateArgPicker.currentDate(parse("LocalDate.of(2026, 8, 20)")));
        assertEquals(LocalDate.of(2026, 8, 20), DateArgPicker.currentDate(parse("LocalDate.parse(\"2026-08-20\")")));
    }

    @Test
    void anythingElseKeepsItsRawSource() {
        assertNull(DateArgPicker.currentDate(parse("LocalDate.now()")),
                "today is not a fixed date — showing it as one would be a lie the OK button then commits");
        assertNull(DateArgPicker.currentDate(parse("startedOn")));
        assertNull(DateArgPicker.currentDate(parse("LocalDate.parse(\"tomorrow\")")));
        assertNull(DateArgPicker.currentDate(parse("LocalDate.of(2026, 2, 31)")),
                "a day that does not exist compiles; it just is not one a calendar can open on");
        assertNull(DateArgPicker.currentDate(parse("LocalTime.of(5, 30)")));
    }
}
