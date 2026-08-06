package com.botmaker.studio.types;

import org.eclipse.jdt.core.dom.AST;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties the four hand-kept name lists this replaced could not have: that the primitive set is the
 * language's, that the JDK names are the JDK's, and that a bare name is trusted only where the language
 * itself would trust it.
 */
class PrimitiveAndJdkTypeTest {

    @Test
    void everyPrimitiveKeywordResolvesBackToItsKind() {
        // Was two Set<String> constants on ResolvedType (PRIMITIVE_NAMES, NUMERIC_PRIMITIVES) that nothing
        // tied to the parse: a keyword listed in one and missing from the other was silently a Named type.
        for (PrimitiveKind kind : PrimitiveKind.values()) {
            ResolvedType type = ResolvedType.named(kind.keyword());
            assertInstanceOf(ResolvedType.Primitive.class, type, kind.keyword() + " must not become a Named");
            assertEquals(kind, ((ResolvedType.Primitive) type).kind());
            assertTrue(type.isPrimitive());
            assertTrue(type.is(kind));
        }
    }

    @Test
    void numericIsTheSixArithmeticKinds() {
        assertEquals(Set.of(PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.DOUBLE,
                        PrimitiveKind.FLOAT, PrimitiveKind.SHORT, PrimitiveKind.BYTE),
                Stream.of(PrimitiveKind.values()).filter(PrimitiveKind::isNumeric)
                        .collect(Collectors.toSet()));
        assertAll(
                () -> assertFalse(ResolvedType.BOOLEAN.isNumeric()),
                () -> assertFalse(ResolvedType.VOID.isNumeric()),
                () -> assertTrue(ResolvedType.INT.isNumeric()),
                () -> assertTrue(ResolvedType.DOUBLE.isNumeric()),
                // The boxes are numeric too — by FQN only, which is what NUMERIC_BOX_NAMES is derived from.
                () -> assertTrue(ResolvedType.of(JdkType.INTEGER).isNumeric()),
                () -> assertFalse(ResolvedType.named("Integer").isNumeric(),
                        "a bare Integer is not proof of java.lang.Integer for arithmetic purposes"));
    }

    @Test
    void everyKindHasAJdtCodeIncludingVoid() {
        // The switch this replaced threw IllegalArgumentException on anything unlisted — and it omitted void,
        // so a void-typed catalog entry would have blown up rather than produced `void`.
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        for (PrimitiveKind kind : PrimitiveKind.values()) {
            assertNotNull(kind.code(), kind + " has no JDT code");
            assertEquals(kind.keyword(), ast.newPrimitiveType(kind.code()).toString());
        }
    }

    @Test
    void aBareNameIsTrustedOnlyForJavaLang() {
        assertAll(
                // java.lang is auto-imported: a source file writing `String` can mean nothing else.
                () -> assertTrue(ResolvedType.named("String").isString()),
                () -> assertTrue(ResolvedType.of(JdkType.STRING).isString()),
                () -> assertTrue(ResolvedType.named("Object").isUnknown()),
                // java.util is not: a project class called List is the user's, not JdkType.LIST.
                () -> assertFalse(ResolvedType.named("List").is(JdkType.LIST)),
                () -> assertTrue(ResolvedType.of(JdkType.LIST).is(JdkType.LIST)));
    }

    @Test
    void theJdkNamesAreTheJdksOwn() {
        // The FQNs come off Class literals, so this pins the ones the editor writes or compares rather than
        // re-asserting the whole enum — the point being that "java.util." + simpleName is gone.
        assertAll(
                () -> assertEquals("java.util.ArrayList", JdkType.ARRAY_LIST.qualifiedName()),
                () -> assertEquals("java.lang.String", JdkType.STRING.qualifiedName()),
                () -> assertEquals("java.lang.Iterable", JdkType.ITERABLE.qualifiedName()),
                () -> assertEquals(JdkType.ARRAY_LIST, JdkType.bySimpleName("ArrayList").orElseThrow()),
                () -> assertTrue(JdkType.bySimpleName("NoSuchType").isEmpty()));
    }

    @Test
    void theBoxesLineUpWithTheirPrimitives() {
        assertEquals(JdkType.NUMERIC_BOXES,
                Stream.of(PrimitiveKind.values())
                        .filter(PrimitiveKind::isNumeric)
                        .map(k -> k.boxed().orElseThrow())
                        .collect(Collectors.toSet()));
        assertTrue(PrimitiveKind.VOID.boxed().isEmpty(), "void has no boxed value");
    }
}
