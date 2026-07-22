package com.botmaker.studio.parser;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code java.awt.Color} argument must be seeded with a compilable, fully-qualified white.
 *
 * <p>The bug this pins down shipped twice. {@code InitializerFactory}'s generic object branch emitted a bare
 * {@code new Color()}, which fails to compile on two counts — nothing imports {@code Color}, and
 * {@code java.awt.Color} has no no-arg constructor — producing the user-visible
 * {@code cannot find symbol: class Color} on every {@code Pixel.find} block. It also made the editor lie:
 * {@code ColorArgPicker} only reads RGB back out of a {@code new Color(r, g, b)} literal and returns null for
 * anything else, so its swatch fell back to the JavaFX {@code ColorPicker} default — white — while the code
 * said something that wouldn't build. Seeding white is what makes the swatch and the source agree.
 */
class ColorArgumentSeedTest {

    private static final String SOURCE = """
            public class Subject {
                void run() {
                    Pixel.find();
                }
            }
            """;

    /** The first {@code Pixel.find(…)} invocation in the fixture's parsed unit. */
    private static MethodInvocation findCall(CompilationUnit cu) {
        MethodInvocation[] found = new MethodInvocation[1];
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (found[0] == null && "find".equals(node.getName().getIdentifier())) found[0] = node;
                return true;
            }
        });
        assertNotNull(found[0], "fixture should contain a find(…) call");
        return found[0];
    }

    @Test
    void aColorArgumentIsSeededWithFullyQualifiedWhite() {
        EditorFixture f = new EditorFixture(SOURCE);
        MethodInvocation call = findCall(f.state.getCompilationUnit().orElseThrow());

        f.editor.updateMethodInvocation(call, "Pixel", "find",
                List.of(ResolvedType.named("Color"), ResolvedType.named("double")));

        assertNotNull(f.lastCode, "the overload switch should rewrite the source");
        assertTrue(f.lastCode.contains("new java.awt.Color(255, 255, 255)"),
                "a Color slot must be seeded with fully-qualified white, got:\n" + f.lastCode);
        // The exact shape that shipped broken: a no-arg construction of a class with no no-arg constructor.
        assertFalse(f.lastCode.contains("new Color()"),
                "a Color slot must never be seeded with an uncompilable new Color(), got:\n" + f.lastCode);
    }
}
