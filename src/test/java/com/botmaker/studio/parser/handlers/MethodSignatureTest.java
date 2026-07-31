package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio blocks/parser MISSING 5 — the rewrites that can corrupt a method signature.</b>
 *
 * <p>{@code parser.handlers} sat at 25.7% over 482 lines, and these are the operations with the least room for
 * error in the module: a signature edit that is right about the declaration and wrong about everything
 * referring to it leaves a file that no longer compiles, from a single click in the editor.
 *
 * <p>The module already knows this failure mode and has fixed it once. {@code AstRewriteHelper}'s
 * {@code renameForEachVariable} exists because {@code renameSimpleName} "replaces only the single node it is
 * handed; a loop variable also appears in the loop body, and renaming just the declaration leaves those
 * references dangling on the old name". The tests below ask whether the same reasoning was applied to the two
 * other rename paths that go through the same primitive.
 */
class MethodSignatureTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public int scale(int factor) {
                    int doubled = factor * 2;
                    return doubled;
                }

                public void caller() {
                    int result = scale(3);
                }
            }
            """;

    private record Env(CompilationUnit cu, ProjectAnalyzer analyzer) {}

    private static Env env() {
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), SOURCE, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "fixture must parse");
        return new Env(cu, new ProjectAnalyzer(null, new ProjectState()));
    }

    private static MethodDeclaration method(CompilationUnit cu, String name) {
        TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(name)) return m;
        }
        throw new AssertionError("fixture should declare " + name + "()");
    }

    /** Occurrences of a bare identifier, so "the old name is gone" can be asserted without matching substrings. */
    private static int occurrences(String code, String identifier) {
        return code.split("\\b" + identifier + "\\b", -1).length - 1;
    }

    // ---- Parameters ----

    @Test
    void addingAParameterAppendsItAndLeavesTheBodyAlone() {
        Env e = env();
        String result = MethodHandler.addParameterToMethod(
                e.cu(), SOURCE, method(e.cu(), "scale"), ResolvedType.primitive("boolean"), "loud", e.analyzer());

        assertTrue(result.contains("scale(int factor, boolean loud)"), result);
        assertTrue(result.contains("int doubled = factor * 2;"), "the body is untouched");
    }

    @Test
    void deletingAParameterRemovesOnlyThatParameter() {
        Env e = env();
        String result = MethodHandler.deleteParameterFromMethod(e.cu(), SOURCE, method(e.cu(), "scale"), 0);

        assertTrue(result.contains("scale()"), result);
        assertTrue(result.contains("public void caller()"), "an unrelated method is untouched");
    }

    @Test
    void changingAParameterTypeRewritesTheTypeAndNotTheName() {
        Env e = env();
        String result = MethodHandler.changeMethodParameterType(
                e.cu(), SOURCE, method(e.cu(), "scale"), 0, ResolvedType.primitive("double"), e.analyzer());

        assertTrue(result.contains("scale(double factor)"), result);
    }

    /** An out-of-range index is a no-op, not a corruption. */
    @Test
    void anOutOfRangeParameterIndexChangesNothing() {
        Env e = env();
        assertEquals(SOURCE, MethodHandler.deleteParameterFromMethod(e.cu(), SOURCE, method(e.cu(), "scale"), 7));
        assertEquals(SOURCE, MethodHandler.renameMethodParameter(e.cu(), SOURCE, method(e.cu(), "scale"), 7, "x"));
    }

    // ---- Return type ----

    @Test
    void aNewReturnTypeIsAppliedAndTheDefaultReturnFollowsIt() {
        Env e = env();
        String result = MethodHandler.setMethodReturnType(
                e.cu(), SOURCE, method(e.cu(), "caller"), ResolvedType.primitive("boolean"), e.analyzer());

        assertTrue(result.contains("public boolean caller()"), result);
        assertTrue(result.contains("return false;"),
                "a method that gains a return type gains a default return, or it stops compiling: " + result);
    }

    /** A return the user wrote is theirs; only an untouched default may be replaced. */
    @Test
    void switchingToVoidDropsTheTrailingReturn() {
        Env e = env();
        String result = MethodHandler.setMethodReturnType(
                e.cu(), SOURCE, method(e.cu(), "scale"), ResolvedType.primitive("void"), e.analyzer());

        assertTrue(result.contains("public void scale(int factor)"), result);
        assertTrue(!result.contains("return doubled;"),
                "a void method may not keep returning a value: " + result);
        assertTrue(result.contains("int doubled = factor * 2;"), "the rest of the body survives");
    }

    // ---- Method identity ----

    @Test
    void deletingAMethodRemovesOnlyThatMethod() {
        Env e = env();
        String result = MethodHandler.deleteMethodFromClass(e.cu(), SOURCE, method(e.cu(), "scale"));

        assertTrue(!result.contains("int doubled"), "the method's body goes with it");
        assertTrue(result.contains("public void caller()"), "its neighbour stays");
    }

    @Test
    void renamingAMethodRenamesItsDeclaration() {
        Env e = env();
        String result = MethodHandler.renameMethod(e.cu(), SOURCE, method(e.cu(), "scale"), "amplify");

        assertTrue(result.contains("public int amplify(int factor)"), result);
    }

    // ---- What is broken: a rename that stops at the declaration ----

    /**
     * <b>B16.</b> {@code renameMethod} calls {@code renameSimpleName} on the declaration's name node only, so
     * every call site keeps the old name and the file stops compiling — from one rename in the editor, with no
     * warning. This is the defect {@code renameForEachVariable} was written to fix for loop variables; the same
     * primitive is still used raw here.
     */
    @Test
    @Disabled("B16 is unfixed: verified red on this commit — renaming a method leaves its call sites on the "
            + "old name. Delete this line in Phase 4 with the fix.")
    void renamingAMethodAlsoRenamesItsCallSites() {
        Env e = env();
        String result = MethodHandler.renameMethod(e.cu(), SOURCE, method(e.cu(), "scale"), "amplify");

        assertEquals(0, occurrences(result, "scale"),
                "the old method name survives at " + occurrences(result, "scale") + " site(s); the project no "
                        + "longer compiles:\n" + result);
        assertTrue(result.contains("amplify(3)"), "the call must follow the declaration: " + result);
    }

    /**
     * <b>B16, same shape one level down.</b> Renaming a parameter rewrites the declaration and leaves every use
     * of it in the body bound to a name that no longer exists.
     */
    @Test
    @Disabled("B16 as above — a renamed parameter's body references are left dangling. Delete this line in "
            + "Phase 4 with the fix.")
    void renamingAParameterAlsoRenamesItsUsesInTheBody() {
        Env e = env();
        String result = MethodHandler.renameMethodParameter(e.cu(), SOURCE, method(e.cu(), "scale"), 0, "multiplier");

        assertTrue(result.contains("scale(int multiplier)"), "the declaration is renamed: " + result);
        assertEquals(0, occurrences(result, "factor"),
                "the body still multiplies by 'factor', which is no longer declared:\n" + result);
    }
}
