package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Time} facade's three editors. Two are dispatched by type; the third is the exception this
 * codebase otherwise avoids — a {@code (method, argIndex)} hook — because {@code Time.isBetween(int, int)}
 * takes bare hours with nothing in the type to hang an editor on. That hook is exactly the kind that goes
 * stale silently, so what it matches and what it doesn't is pinned here.
 */
class TimeArgPickerTest {

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

    private static PickerContext hourArg(String method, int index) {
        return new PickerContext(null, null, ResolvedType.named("int"), "Time", method, index);
    }

    @Test
    void aClockSlotIsSeededWithSomethingCompilableAndReadable() {
        // LocalTime has no public constructor, so the generic `new LocalTime()` would not compile; and the
        // seed must be fully qualified, because java.time is not in the SDK jar the analyzer indexes, so the
        // import a simple name relies on would never be added.
        assertEquals("java.time.LocalTime.of(12,0)", seedFor("LocalTime").replace(" ", ""));
        assertEquals(LocalTime.of(12, 0), TimeArgPicker.currentTime(parse(seedFor("LocalTime"))));
        assertEquals(DayOfWeek.MONDAY, TimeArgPicker.currentDay(parse(seedFor("DayOfWeek"))));
    }

    @Test
    void theTypedSlotsAreDispatchedByType() {
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("LocalTime"))));
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("DayOfWeek"))));
    }

    @Test
    void theClockReadsBothArgumentsAndRejectsImpossibleTimes() {
        assertEquals(LocalTime.of(5, 30), TimeArgPicker.currentTime(parse("LocalTime.of(5, 30)")));
        assertEquals(LocalTime.of(5, 3), TimeArgPicker.currentTime(parse("LocalTime.of(5, 3)")),
                "5:03 and 5:30 are one keystroke apart and both compile — the control has to tell them apart");
        assertNull(TimeArgPicker.currentTime(parse("LocalTime.of(25, 0)")));
        assertNull(TimeArgPicker.currentTime(parse("LocalTime.NOON")), "a constant is not ours to rewrite");
        assertNull(TimeArgPicker.currentTime(parse("start")));
    }

    @Test
    void aDayIsReadQualifiedOrBareButNothingElseIs() {
        assertEquals(DayOfWeek.SUNDAY, TimeArgPicker.currentDay(parse("DayOfWeek.SUNDAY")));
        assertEquals(DayOfWeek.SUNDAY, TimeArgPicker.currentDay(parse("SUNDAY")), "a static import is legal");
        assertNull(TimeArgPicker.currentDay(parse("today")));
    }

    @Test
    void theHourHookCoversBothArgumentsOfBothOverloadsAndNothingElse() {
        assertTrue(hourArg("isBetween", 0).isTimeHourArg());
        assertTrue(hourArg("isBetween", 1).isTimeHourArg());
        assertTrue(hourArg("isBetweenUtc", 0).isTimeHourArg());
        assertFalse(hourArg("isBetween", 2).isTimeHourArg(), "there is no third hour");
        assertFalse(hourArg("format", 0).isTimeHourArg());
        assertFalse(new PickerContext(null, null, ResolvedType.named("int"), "Bot", "isBetween", 0)
                .isTimeHourArg(), "another class's isBetween is not this one");
        // The minute-precision overload takes LocalTime, so the hook must not claim it — it would replace the
        // clock with an hours dropdown and quietly drop the minutes.
        assertFalse(new PickerContext(null, null, ResolvedType.named("LocalTime"), "Time", "isBetween", 0)
                .isTimeHourArg());
    }
}
