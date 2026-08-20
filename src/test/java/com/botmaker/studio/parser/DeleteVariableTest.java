package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a variable that is <em>used</em> — the half the ✕ never had.
 *
 * <p>What is pinned here is that the file still compiles afterwards: removing the declaration alone leaves every
 * use pointing at a name that is gone, which is a broken project produced by one press of a delete cross. The
 * answer is the user's ({@link UseFix}), and whichever they pick it lands as one write.
 */
class DeleteVariableTest {

    private EditorFixture fx;

    private void open(String source) {
        fx = new EditorFixture(source);
    }

    private VariableDeclarationStatement declaration(String methodName, String variableName) {
        CompilationUnit unit = fx.state.getCompilationUnit().orElseThrow();
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        for (MethodDeclaration method : type.getMethods()) {
            if (!method.getName().getIdentifier().equals(methodName)) continue;
            @SuppressWarnings("unchecked")
            List<Statement> body = method.getBody().statements();
            for (Statement statement : body) {
                if (statement instanceof VariableDeclarationStatement decl
                        && decl.toString().contains(variableName)) {
                    return decl;
                }
            }
        }
        throw new AssertionError("no declaration of " + variableName + " in " + methodName);
    }

    @Test
    void everyUseBecomesTheTypesDefault() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        int attempts = 3;
                        BotMaker.print(attempts);
                        BotMaker.print(attempts + 1);
                    }
                }
                """);

        fx.editor.deleteVariable(declaration("run", "attempts"), UseFix.DEFAULT);

        assertNotNull(fx.lastCode, "the delete should have produced a code update");
        assertFalse(fx.lastCode.contains("attempts"),
                "neither the declaration nor its uses survive:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(0)"),
                "a use takes the declared type's default:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(0 + 1)"),
                "including a use inside a larger expression:\n" + fx.lastCode);
    }

    @Test
    void everyUseCanPointAtAnotherVariableInstead() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        int kept = 7;
                        int attempts = 3;
                        BotMaker.print(attempts);
                        BotMaker.print(attempts + 1);
                    }
                }
                """);

        fx.editor.deleteVariable(declaration("run", "attempts"), new UseFix.Rename("kept"));

        assertNotNull(fx.lastCode, "the delete should have produced a code update");
        assertFalse(fx.lastCode.contains("attempts"),
                "the deleted name is gone from the file:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(kept)"), fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(kept + 1)"), fx.lastCode);
        assertTrue(fx.lastCode.contains("int kept = 7;"),
                "the variable pointed at is untouched:\n" + fx.lastCode);
    }

    @Test
    void aLineThatOnlyWroteTheVariableGoesWithIt() {
        // `attempts = attempts + 1;` cannot take a value in the variable's place — `0 = 0 + 1;` is not source —
        // so the line that wrote it is removed. It also holds two uses, and the one on the right must not be
        // separately replaced inside a statement that is already going.
        open("""
                package test;

                public class Subject {
                    public void run() {
                        int attempts = 3;
                        attempts = attempts + 1;
                        attempts++;
                        BotMaker.print(attempts);
                    }
                }
                """);

        fx.editor.deleteVariable(declaration("run", "attempts"), UseFix.DEFAULT);

        assertNotNull(fx.lastCode, "the delete should have produced a code update");
        assertFalse(fx.lastCode.contains("attempts"),
                "no trace of the variable is left:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(0)"),
                "the use that reads it still becomes the default:\n" + fx.lastCode);
    }

    @Test
    void aVariableInsideAnIfBodyIsStillOneWrite() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        if (true) {
                            String name = "x";
                            BotMaker.print(name);
                        }
                    }
                }
                """);
        CompilationUnit unit = fx.state.getCompilationUnit().orElseThrow();
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        MethodDeclaration run = type.getMethods()[0];
        VariableDeclarationStatement decl = (VariableDeclarationStatement)
                ((org.eclipse.jdt.core.dom.Block) ((org.eclipse.jdt.core.dom.IfStatement)
                        ((List<?>) run.getBody().statements()).getFirst()).getThenStatement())
                        .statements().getFirst();

        fx.editor.deleteVariable(decl, UseFix.DEFAULT);

        assertNotNull(fx.lastCode, "the delete should have produced a code update");
        assertFalse(fx.lastCode.contains("String name"),
                "the declaration is gone from the nested body:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("BotMaker.print(\"\")"),
                "a text variable's uses become the empty string:\n" + fx.lastCode);
    }
}
