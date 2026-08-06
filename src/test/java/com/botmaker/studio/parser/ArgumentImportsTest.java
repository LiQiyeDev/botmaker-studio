package com.botmaker.studio.parser;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inserting a call and switching it onto another overload both build a default argument per parameter — and
 * those defaults reference their type by <em>simple</em> name ({@code Color.RED}, {@code new Color()}). The
 * bug: only the call's scope was imported, so {@code Pixel.find(Color)} landed with an unresolvable
 * {@code Color}, and the overload-switch path imported nothing at all — not even the new scope.
 *
 * <p>{@code Color} is the worked example because it is a JDK type the analyzer's index does not carry, so it
 * also exercises {@code ImportManager}'s JDK package probe — and specifically the part of it that only works
 * because {@code java.awt} is probed at all, rather than the name having to be on a hand-written list.
 */
class ArgumentImportsTest {

    private static final String SOURCE = """
            public class Subject {
                void run() {
                    Vision.find("gold.png");
                }
            }
            """;

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
    void switchingOntoAnOverloadImportsTheNewArgumentTypes() {
        EditorFixture f = new EditorFixture(SOURCE);
        MethodInvocation call = findCall(f.state.getCompilationUnit().orElseThrow());

        f.editor.updateMethodInvocation(call, "Pixel", "find",
                List.of(ResolvedType.named("Color")));

        assertNotNull(f.lastCode, "the overload switch should rewrite the source");
        assertTrue(f.lastCode.contains("import java.awt.Color;"),
                "the new argument type must be imported, got:\n" + f.lastCode);
    }

    @Test
    void insertingACallImportsItsArgumentTypes() {
        EditorFixture f = new EditorFixture(SOURCE);

        f.editor.addMethodCallStatement(f.body("run"),
                new ExpressionChoice.Method("Pixel", "find", List.of(ResolvedType.named("Color")), true),
                0);

        assertNotNull(f.lastCode, "inserting a call should rewrite the source");
        assertTrue(f.lastCode.contains("import java.awt.Color;"),
                "an inserted call's argument types must be imported, got:\n" + f.lastCode);
    }
}
