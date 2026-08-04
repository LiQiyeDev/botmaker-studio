package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wait length is the SDK's other value type, for the same reason {@code Precision} is one: the unit is
 * invisible in a bare number, and dispatching the editor on the type means no {@code (method, argIndex)} table
 * can go stale. What is pinned here is what fails silently — the seed a fresh slot gets, the source the
 * control commits (now {@code java.time.Duration}, whose factories take whole numbers, so a fraction has to
 * change unit rather than truncate), that reopening it reads back what it wrote in the unit it was written in,
 * and that the random range restructures the call instead of nesting an expression.
 */
class DurationPickerTest {

    private static Expression parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        return (Expression) parser.createAST(null);
    }

    /** The argument at {@code index} of a parsed statement — parented, which {@code K_EXPRESSION} is not. */
    private static Expression argOf(String statement, int index) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(statement.toCharArray());
        Block block = (Block) parser.createAST(null);
        MethodInvocation call =
                (MethodInvocation) ((ExpressionStatement) block.statements().getFirst()).getExpression();
        return (Expression) call.arguments().get(index);
    }

    private static String seedFor(String typeName) {
        AST ast = AST.newAST(AST.getJLSLatest(), true);
        Expression seeded = InitializerFactory.createDefaultInitializer(ast, ResolvedType.named(typeName));
        return seeded == null ? null : seeded.toString();
    }

    @Test
    void aFreshSlotIsSeededWithACompilableDuration() {
        assertEquals("Duration.ofSeconds(1)", seedFor("Duration"));
        // ...and the seed must be one the control can read, or the slot opens showing raw source instead.
        assertNotNull(DurationPicker.parse(parse(seedFor("Duration"))));
    }

    @Test
    void theArgumentIsDispatchedByTypeSoNoOverloadTableCanGoStale() {
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null, ResolvedType.named("Duration"))));
        assertTrue(PickerRegistry.hasPicker(PickerContext.of(null, null,
                ResolvedType.named("java.time.Duration"))));
    }

    @Test
    void theUnitTheSourceNamesIsTheUnitReadBack() {
        // 1500ms and 1.5s are the same wait; showing the second as "1500 ms" would rewrite what the user typed
        // the moment they touched any other field.
        assertEquals("1500 ms", DurationPicker.parse(parse("Duration.ofMillis(1500)")).label());
        assertEquals("2 s", DurationPicker.parse(parse("Duration.ofSeconds(2)")).label());
        assertEquals("2 min", DurationPicker.parse(parse("Duration.ofMinutes(2)")).label());
    }

    @Test
    void aFractionIsCommittedInTheNextUnitDownRatherThanTruncated() {
        // java.time's factories are long-taking: ofSeconds(1.5) does not compile, and rounding it to
        // ofSeconds(1) would quietly turn a 1.5s wait into a 1s one.
        assertEquals("Duration.ofMillis(1500)", new DurationPicker.Value(DurationPicker.Unit.SECONDS, 1.5).code());
        assertEquals("Duration.ofSeconds(90)", new DurationPicker.Value(DurationPicker.Unit.MINUTES, 1.5).code());
        assertEquals("Duration.ofMillis(2)", new DurationPicker.Value(DurationPicker.Unit.MS, 1.6).code());
    }

    @Test
    void aWholeNumberStaysInTheUnitItWasTypedIn() {
        // The inverse of the rule above: 2 seconds must not come back as ofMillis(2000).
        assertEquals("Duration.ofSeconds(2)", DurationPicker.parse(parse("Duration.ofSeconds(2)")).code());
        assertEquals("Duration.ofMinutes(2)", DurationPicker.parse(parse("Duration.ofMinutes(2)")).code());
        assertEquals("Duration.ofMillis(1500)", DurationPicker.parse(parse("Duration.ofMillis(1500)")).code());
    }

    @Test
    void theRandomRangeIsACallAndNotANestedExpression() {
        DurationPicker.Value from = new DurationPicker.Value(DurationPicker.Unit.MS, 800);
        DurationPicker.Value to = new DurationPicker.Value(DurationPicker.Unit.SECONDS, 2);
        assertEquals("Wait.between(Duration.ofMillis(800), Duration.ofSeconds(2))",
                DurationPicker.callCode(new DurationPicker.Span(from, to, true)));
        assertEquals("Wait.time(Duration.ofMillis(800))",
                DurationPicker.callCode(DurationPicker.Span.fixed(from)));
    }

    @Test
    void bothEndsOfABetweenReachTheWholeCall() {
        // Either button edits the range, so the toggle can be turned off from either end.
        String source = "Wait.between(Duration.ofMillis(800), Duration.ofSeconds(2));";
        assertNotNull(DurationPicker.editableWaitCall(argOf(source, 0)));
        assertNotNull(DurationPicker.editableWaitCall(argOf(source, 1)));
        assertNotNull(DurationPicker.editableWaitCall(argOf("Wait.time(Duration.ofSeconds(1));", 0)));
    }

    @Test
    void aCallWithAnEndThisControlCannotShowIsLeftWhole() {
        // Rewriting the call would discard `timeout`; the slot still edits itself, it just can't restructure
        // the statement around it.
        assertNull(DurationPicker.editableWaitCall(argOf("Wait.between(timeout, Duration.ofSeconds(2));", 1)));
        assertNull(DurationPicker.editableWaitCall(argOf("Sleeper.time(Duration.ofSeconds(2));", 0)),
                "only Wait's own calls are ours to restructure");
    }

    @Test
    void anythingItCannotShowIsLeftToTheGenericPill() {
        assertNull(DurationPicker.parse(parse("timeout")), "a variable is not ours to rewrite");
        assertNull(DurationPicker.parse(parse("Duration.ZERO")));
        assertNull(DurationPicker.parse(parse("Precision.DEFAULT")));
    }

    @Test
    void theWaitBlockInsertsTheFormThisPickerCanEdit() {
        // The palette entry and this control have to agree: an inserted Wait whose argument the picker can't
        // read renders as a text pill, which is the whole thing the type was introduced to avoid.
        assertTrue(BlockCatalog.WAIT.toString().contains("time"), BlockCatalog.WAIT.toString());
        assertNotNull(DurationPicker.parse(parse("Duration.ofSeconds(1)")));
    }
}
