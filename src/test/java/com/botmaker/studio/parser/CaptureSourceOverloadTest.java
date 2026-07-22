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
 * Switching a call onto an overload that takes a {@code CaptureSource} (the {@code ⚙} signature picker →
 * {@code CodeEditor.updateMethodInvocation} → {@code InitializerFactory.createDefaultInitializer}) must seed the
 * new slot with the <em>live</em> {@code Source.current()} call. The bug: it seeded
 * {@code CaptureExpr.of(<the project's default target>)}, freezing the argument into a
 * {@code CaptureSource.window("…")} snapshot that stopped following the project's configured source.
 */
class CaptureSourceOverloadTest {

    private static final String SOURCE = """
            public class Subject {
                void run() {
                    Vision.find("gold.png");
                }
            }
            """;

    /** The first {@code Vision.find(…)} invocation in the fixture's parsed unit. */
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
    void switchingOntoACaptureSourceOverloadSeedsSourceCurrent() {
        EditorFixture f = new EditorFixture(SOURCE);
        MethodInvocation call = findCall(f.state.getCompilationUnit().orElseThrow());

        // Switch to the (CaptureSource, String) overload — the new first argument is the one being seeded.
        f.editor.updateMethodInvocation(call, "Vision", "find",
                List.of(ResolvedType.named("CaptureSource"), ResolvedType.named("String")));

        assertNotNull(f.lastCode, "the overload switch should rewrite the source");
        assertTrue(f.lastCode.contains("Source.current()"),
                "a CaptureSource slot must be seeded with the live ambient source, got:\n" + f.lastCode);
        assertFalse(f.lastCode.contains("CaptureSource.window("),
                "the slot must not be frozen to a snapshot of today's default, got:\n" + f.lastCode);
    }
}
