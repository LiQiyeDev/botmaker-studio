package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Expression;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Time} facade's three editors, all dispatched by type. That is the whole point of the facade
 * taking {@code java.time} values: the bare-hour {@code isBetween(int, int)} overloads it used to carry could
 * only be reached by a {@code (method, argIndex)} hook, which goes stale silently — a picker that stops
 * appearing breaks nothing that any test would notice. What is pinned here is the seed each slot opens on,
 * that a slot reads its own source back, and that a bare constant (a static import) is still recognised.
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

    @Test
    void aClockSlotIsSeededWithSomethingCompilableAndReadable() {
        // LocalTime has no public constructor, so the generic `new LocalTime()` would not compile; and the
        // seed must be fully qualified, because java.time is not in the SDK jar the analyzer indexes, so the
        // import a simple name relies on would never be added.
        assertEquals("java.time.LocalTime.of(12,0)", seedFor("LocalTime").replace(" ", ""));
        assertEquals(LocalTime.of(12, 0), TimeArgPicker.currentTime(parse(seedFor("LocalTime"))));
        assertEquals(DayOfWeek.MONDAY, TimeArgPicker.currentDay(parse(seedFor("DayOfWeek"))));
        assertEquals(Month.JANUARY, TimeArgPicker.currentMonth(parse(seedFor("Month"))));
    }

    @Test
    void everyTimeSlotIsDispatchedByType() {
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("LocalTime"))));
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("DayOfWeek"))));
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("Month"))));
        // Qualified too: a resolved java.time parameter arrives as its FQN, and matching only the simple name
        // would leave every one of these as a text pill.
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("java.time.Month"))));
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
    void aMonthIsReadQualifiedOrBareButNothingElseIs() {
        assertEquals(Month.DECEMBER, TimeArgPicker.currentMonth(parse("Month.DECEMBER")));
        assertEquals(Month.DECEMBER, TimeArgPicker.currentMonth(parse("DECEMBER")), "a static import is legal");
        assertNull(TimeArgPicker.currentMonth(parse("season")));
        assertNull(TimeArgPicker.currentMonth(parse("DayOfWeek.MONDAY")), "MONDAY is not a month");
    }

    @Test
    void aBareIntArgumentGetsNoTimePicker() {
        // The facade's bare-hour overloads are gone, and with them the only reason a Time argument was ever
        // dispatched by (method, argIndex). If an int slot on Time starts matching again, something has
        // reintroduced a hook — which is how the stale-table problem comes back.
        assertFalse(PickerRegistry.hasPicker(
                new PickerContext(null, null, ResolvedType.named("int"), "Time", "isBetween", 0)));
    }
}
