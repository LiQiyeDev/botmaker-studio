package com.botmaker.blocks.expr;

import com.botmaker.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ListElementType} derives the correct element type for arrays (including multi-dimensional,
 * where the outer initializer must yield a one-dimension-smaller array) and generic {@code List<T>} literals.
 * Parsing without binding resolution is enough — the inference falls back to the syntactic declared type.
 */
class ListElementTypeTest {

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static List<ArrayInitializer> arrayInitializers(CompilationUnit cu) {
        List<ArrayInitializer> found = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(ArrayInitializer node) { found.add(node); return true; }
        });
        return found;
    }

    private static MethodInvocation firstInvocation(CompilationUnit cu, String name) {
        MethodInvocation[] result = new MethodInvocation[1];
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) {
                if (result[0] == null && node.getName().getIdentifier().equals(name)) result[0] = node;
                return true;
            }
        });
        return result[0];
    }

    @Test
    void oneDimensionalArrayYieldsLeafElement() {
        CompilationUnit cu = parse("class C { void m() { int[] a = {1, 2, 3}; } }");
        List<ArrayInitializer> inits = arrayInitializers(cu);
        assertEquals("int", ListElementType.of(inits.getFirst()).simpleName());
    }

    @Test
    void twoDimensionalArrayOuterYieldsArrayInnerYieldsLeaf() {
        CompilationUnit cu = parse("class C { void m() { String[][] a = {{\"x\"}, {\"y\"}}; } }");
        List<ArrayInitializer> inits = arrayInitializers(cu);
        // First visited initializer is the outer one; its elements are String[].
        assertEquals("String[]", ListElementType.of(inits.get(0)).simpleName());
        // The nested initializer's elements are String scalars.
        assertEquals("String", ListElementType.of(inits.get(1)).simpleName());
    }

    @Test
    void genericListYieldsTypeArgument() {
        CompilationUnit cu = parse("import java.util.List; class C { void m() { List<String> a = List.of(\"x\"); } }");
        MethodInvocation listOf = firstInvocation(cu, "of");
        assertEquals("String", ListElementType.of(listOf).simpleName());
    }
}
