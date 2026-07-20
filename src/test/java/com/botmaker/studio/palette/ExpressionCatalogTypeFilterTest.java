package com.botmaker.studio.palette;

import com.botmaker.studio.types.ResolvedType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which expressions a slot offers, by the slot's type.
 *
 * <p>The bug this pins down showed up in lists — "maths and logic are offered for things that aren't numbers
 * or booleans" — but it was never list-specific. {@code TypeExpectation.of} folds <em>every</em> reference
 * type into {@code ANY}, and {@code isCompatibleWith} read {@code ANY} as "no constraint", so a slot typed
 * {@code Point} offered {@code Addition (+)} and {@code And (&&)}. Lists just make it obvious, because list
 * elements are usually object-typed. The list machinery itself was fine: {@code ListElementType} infers the
 * element type and {@code ListBlock} passes it.
 */
class ExpressionCatalogTypeFilterTest {

    private static List<ExpressionType> forSlot(String typeName) {
        return ExpressionCatalog.getForType(ResolvedType.named(typeName), null);
    }

    @Test
    void anObjectTypedSlotOffersNoArithmeticOrLogic() {
        List<ExpressionType> offered = forSlot("Point");
        assertFalse(offered.contains(ExpressionCatalog.ADD), "Point + Point is not Java");
        assertFalse(offered.contains(ExpressionCatalog.AND), "Point && Point is not Java");
        assertFalse(offered.contains(ExpressionCatalog.NOT));
        assertFalse(offered.contains(ExpressionCatalog.NUMBER), "a number literal is not a Point");
        assertFalse(offered.contains(ExpressionCatalog.TEXT));
        // ...but you can still put a value there by referring to one, or building one.
        assertTrue(offered.contains(ExpressionCatalog.VARIABLE));
        assertTrue(offered.contains(ExpressionCatalog.FUNCTION_CALL));
        assertTrue(offered.contains(ExpressionCatalog.INSTANTIATION));
    }

    @Test
    void aNumericSlotOffersArithmeticButNotLogic() {
        List<ExpressionType> offered = forSlot("int");
        assertTrue(offered.contains(ExpressionCatalog.ADD));
        assertTrue(offered.contains(ExpressionCatalog.NUMBER));
        assertFalse(offered.contains(ExpressionCatalog.AND));
        assertFalse(offered.contains(ExpressionCatalog.TEXT));
    }

    @Test
    void aBooleanSlotOffersLogicAndComparisonButNotArithmetic() {
        List<ExpressionType> offered = forSlot("boolean");
        assertTrue(offered.contains(ExpressionCatalog.AND));
        assertTrue(offered.contains(ExpressionCatalog.EQUALS));
        assertTrue(offered.contains(ExpressionCatalog.TRUE));
        assertFalse(offered.contains(ExpressionCatalog.ADD));
    }

    @Test
    void aStringSlotOffersTextOnly() {
        List<ExpressionType> offered = forSlot("java.lang.String");
        assertTrue(offered.contains(ExpressionCatalog.TEXT));
        assertFalse(offered.contains(ExpressionCatalog.NUMBER));
        assertFalse(offered.contains(ExpressionCatalog.AND));
    }

    @Test
    void anUnknownSlotIsStillPermissive() {
        // Never over-filter: if the type couldn't be resolved, the menu must not start hiding valid choices.
        List<ExpressionType> offered = ExpressionCatalog.getForType(ResolvedType.UNKNOWN, null);
        assertTrue(offered.contains(ExpressionCatalog.ADD));
        assertTrue(offered.contains(ExpressionCatalog.AND));
        assertTrue(offered.contains(ExpressionCatalog.TEXT));
        assertTrue(offered.contains(ExpressionCatalog.VARIABLE));
    }

    @Test
    void anObjectTypedListSlotStillOffersASubList() {
        // The list itself (as opposed to its elements) is an array slot: a sub-list belongs, maths does not.
        List<ExpressionType> offered = forSlot("Point[]");
        assertTrue(offered.contains(ExpressionCatalog.LIST));
        assertFalse(offered.contains(ExpressionCatalog.ADD));
    }
}
