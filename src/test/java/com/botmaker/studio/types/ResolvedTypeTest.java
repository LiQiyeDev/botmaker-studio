package com.botmaker.studio.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio remainder MISSING 7 — {@link ResolvedType} parse/format round-trip.</b>
 *
 * <p>268 pure lines, and the vocabulary every other area speaks: the block palette, the expression menus and
 * the generated-source type nodes all decide what a user may plug into what by asking this interface. Only
 * two of its four variants need a JDT binding or a ClassGraph scan; the name-based pair ({@code Named},
 * {@code Primitive}) is the one the menus fall back to whenever a type is known only as text, and it is
 * entirely testable without either dependency.
 *
 * <p>The round-trip that matters is {@code named(text) → asArray/leafType → qualifiedName()}: a type comes
 * in as a string from a picker, is decomposed and rebuilt, and goes back out as a string into generated
 * source. Anything lost in the middle is a bot that does not compile.
 */
class ResolvedTypeTest {

    // --- Parsing: what named() routes where ---

    @Test
    void aPrimitiveNameParsesAsAPrimitiveRatherThanAName() {
        for (String name : List.of("int", "double", "boolean", "char", "long", "float", "short", "byte", "void")) {
            ResolvedType t = ResolvedType.named(name);
            assertInstanceOf(ResolvedType.Primitive.class, t, name + " must not become a Named");
            assertTrue(t.isPrimitive(), name);
            assertEquals(name, t.qualifiedName());
        }
    }

    @Test
    void anArrayOfPrimitivesIsANamedTypeBecauseTheLeafAloneIsThePrimitive() {
        ResolvedType t = ResolvedType.named("int[]");

        assertInstanceOf(ResolvedType.Named.class, t);
        assertTrue(t.isArray());
        assertEquals(1, t.arrayDimensions());
        assertInstanceOf(ResolvedType.Primitive.class, t.leafType(),
                "peeling the brackets off must land back on the primitive");
    }

    @Test
    void aBlankOrNullNameIsTheUnknownSentinel() {
        assertTrue(ResolvedType.named(null).isUnknown());
        assertTrue(ResolvedType.named("   ").isUnknown());
        assertEquals(ResolvedType.UNKNOWN, ResolvedType.named(""));
    }

    @Test
    void surroundingWhitespaceIsTrimmedRatherThanBakedIntoTheName() {
        assertEquals("com.botmaker.sdk.api.Point", ResolvedType.named("  com.botmaker.sdk.api.Point  ")
                .qualifiedName());
    }

    // --- Formatting: qualified → simple ---

    @Test
    void theSimpleNameIsTheLeafPlusItsBrackets() {
        assertEquals("Point", ResolvedType.named("com.botmaker.sdk.api.Point").simpleName());
        assertEquals("Point[][]", ResolvedType.named("com.botmaker.sdk.api.Point[][]").simpleName());
        assertEquals("String", ResolvedType.named("String").simpleName(), "an unqualified name is its own leaf");
    }

    // --- The round-trip ---

    @Test
    void decomposingAndRebuildingAnArrayGivesBackTheSameName() {
        for (String name : List.of("java.lang.String", "int", "com.botmaker.sdk.api.Point")) {
            for (int dims = 0; dims <= 3; dims++) {
                String written = name + "[]".repeat(dims);
                ResolvedType parsed = ResolvedType.named(written);

                assertEquals(dims, parsed.arrayDimensions(), written);
                assertEquals(name, parsed.leafType().qualifiedName(), written);
                assertEquals(written, parsed.leafType().asArray(dims).qualifiedName(),
                        "leaf → asArray must reproduce " + written);
            }
        }
    }

    @Test
    void asArrayZeroIsHowATypeIsUnwrapped() {
        assertEquals("java.lang.String", ResolvedType.named("java.lang.String[][]").asArray(0).qualifiedName());
        assertEquals(ResolvedType.named("int"), ResolvedType.named("int[]").asArray(0),
                "unwrapping an int[] must land on the Primitive, not a Named 'int'");
    }

    /** A primitive grown into an array becomes a Named — and rounds back to a Primitive on the way down. */
    @Test
    void aPrimitiveGrownIntoAnArrayComesBackDownAPrimitive() {
        ResolvedType arr = ResolvedType.primitive("double").asArray(2);

        assertEquals("double[][]", arr.qualifiedName());
        assertTrue(arr.isArray());
        assertInstanceOf(ResolvedType.Primitive.class, arr.leafType());
    }

    // --- Classification ---

    @Test
    void bothSpellingsOfStringAndBooleanClassifyTheSame() {
        assertTrue(ResolvedType.named("java.lang.String").isString());
        assertTrue(ResolvedType.named("String").isString(), "the menus hand over simple names too");
        assertTrue(ResolvedType.named("java.lang.Boolean").isBoolean());
        assertTrue(ResolvedType.named("boolean").isBoolean());
    }

    @Test
    void aWrapperCountsAsNumericJustAsItsPrimitiveDoes() {
        assertTrue(ResolvedType.named("java.lang.Integer").isNumeric());
        assertTrue(ResolvedType.named("int").isNumeric());
        assertFalse(ResolvedType.named("java.lang.String").isNumeric());
        assertFalse(ResolvedType.named("boolean").isNumeric());
    }

    /** {@code void} is numeric-adjacent nowhere and must never be offered as a value. */
    @Test
    void voidIsItsOwnThingAndIsNeitherNumericNorBoolean() {
        ResolvedType v = ResolvedType.named("void");
        assertTrue(v.isVoid());
        assertFalse(v.isNumeric());
        assertFalse(v.isBoolean());
    }

    // --- Identity ---

    @Test
    void identityIsTheQualifiedNameEvenAcrossVariants() {
        assertEquals(ResolvedType.primitive("int"), ResolvedType.named("int"));
        assertEquals(ResolvedType.primitive("int").hashCode(), ResolvedType.named("int").hashCode(),
                "equal types must hash alike or a Set of ResolvedType silently keeps duplicates");
        assertFalse(ResolvedType.named("java.lang.String").equals(ResolvedType.named("String")),
                "a simple name and a qualified name are different types by identity, "
                        + "even though isString() accepts both");
    }

    // --- Assignability, the name-based path ---

    @Test
    void anythingIsAssignableToOrFromTheUnknownSentinel() {
        assertTrue(ResolvedType.named("java.lang.String").isAssignmentCompatible(ResolvedType.UNKNOWN));
        assertTrue(ResolvedType.UNKNOWN.isAssignmentCompatible(ResolvedType.named("int")));
        assertTrue(ResolvedType.named("java.lang.String").isAssignmentCompatible(null),
                "a null target is a missing expectation, not a rejection");
    }

    /**
     * <b>Any {@code Object} is the unknown sentinel.</b> {@code UNKNOWN} is literally
     * {@code Named("java.lang.Object")}, so a genuinely Object-typed value is indistinguishable from "we could
     * not work out the type" and is accepted everywhere. Pinned because it is load-bearing in the opposite
     * direction from how it reads: the permissiveness is what keeps the menus usable when resolution fails.
     */
    @Test
    void anObjectTypedValueIsTreatedAsUnresolved() {
        assertTrue(ResolvedType.named("java.lang.Object").isUnknown());
        assertTrue(ResolvedType.named("Object").isUnknown());
        assertTrue(ResolvedType.named("Object").isAssignmentCompatible(ResolvedType.named("int")));
    }

    /**
     * The name-based path treats every numeric as interchangeable — a {@code long} is "compatible" with a
     * {@code byte} slot. Real Java assignability lives on the {@code Bound} variant, which defers to JDT; this
     * is the deliberately loose fallback used when no binding resolved, and it is why the menus over-offer
     * rather than under-offer.
     */
    @Test
    void theNameBasedFallbackTreatsEveryNumericAsInterchangeable() {
        assertTrue(ResolvedType.named("long").isAssignmentCompatible(ResolvedType.named("byte")));
        assertTrue(ResolvedType.named("java.lang.Double").isAssignmentCompatible(ResolvedType.named("int")));
        assertFalse(ResolvedType.named("java.lang.String").isAssignmentCompatible(ResolvedType.named("int")));
    }

    @Test
    void aMatchingSimpleNameIsEnoughForTheFallbackToAccept() {
        assertTrue(ResolvedType.named("com.example.Point")
                        .isAssignmentCompatible(ResolvedType.named("com.other.Point")),
                "with no binding, the same simple name is the strongest signal there is");
    }

    // --- Content ---

    @Test
    void aNameAloneNeverClaimsToKnowEnumConstants() {
        assertEquals(List.of(), ResolvedType.named("com.botmaker.sdk.api.Button").enumConstants());
        assertFalse(ResolvedType.named("com.botmaker.sdk.api.Button").isEnum(),
                "isEnum is a binding/index question; a bare name must answer no rather than guess");
    }
}
