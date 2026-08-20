package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private record Env(CompilationUnit cu, ProjectAnalyzer analyzer) {
        /** A fresh write-path context over this unit — one per handler call, as CodeEditor builds them. */
        EditContext ctx() {
            return EditContext.of(cu, analyzer, null);
        }
    }

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
    //
    // These used to call setMethodReturnType / addParameterToMethod / deleteParameterFromMethod /
    // changeMethodParameterType, four transforms that rewrote a declaration on its own. They are gone: every
    // signature edit now builds a FunctionDraft and goes through applyFunctionSignature, so that is what the
    // same behaviours are asserted against here. The assertions themselves are unchanged — the file has to end
    // up saying the same thing whichever way the edit was spelled.

    /** The signature this file declares, as a draft, so a test can change one field of it. */
    private static FunctionDraft draftFor(CompilationUnit cu, String name) {
        return MethodSignatures.draftOf(method(cu, name))
                .orElseThrow(() -> new AssertionError(name + "() should be representable as a draft"));
    }

    private static String apply(Env e, String methodName, FunctionDraft draft) {
        return MethodHandler.applyFunctionSignature(e.ctx(), SOURCE, method(e.cu(), methodName), draft);
    }

    private static FunctionDraft withParameters(FunctionDraft draft, List<FunctionDraft.Parameter> parameters) {
        return new FunctionDraft(draft.name(), draft.returnType(), parameters);
    }

    @Test
    void addingAParameterAppendsItAndLeavesTheBodyAlone() {
        Env e = env();
        FunctionDraft draft = draftFor(e.cu(), "scale");
        List<FunctionDraft.Parameter> parameters = new ArrayList<>(draft.parameters());
        parameters.add(new FunctionDraft.Parameter("loud", SignatureType.kept("boolean")));

        String result = apply(e, "scale", withParameters(draft, parameters));

        assertTrue(result.contains("scale(int factor, boolean loud)"), result);
        assertTrue(result.contains("int doubled = factor * 2;"), "the body is untouched");
    }

    @Test
    void deletingAParameterRemovesOnlyThatParameter() {
        Env e = env();
        String result = apply(e, "scale", withParameters(draftFor(e.cu(), "scale"), List.of()));

        assertTrue(result.contains("scale()"), result);
        assertTrue(result.contains("public void caller()"), "an unrelated method is untouched");
    }

    @Test
    void changingAParameterTypeRewritesTheTypeAndNotTheName() {
        Env e = env();
        FunctionDraft draft = draftFor(e.cu(), "scale");
        FunctionDraft.Parameter factor = draft.parameters().getFirst();
        String result = apply(e, "scale", withParameters(draft, List.of(
                new FunctionDraft.Parameter(factor.name(), SignatureType.kept("double"), factor.origin()))));

        assertTrue(result.contains("scale(double factor)"), result);
    }

    /** An out-of-range index is a no-op, not a corruption. */
    @Test
    void anOutOfRangeParameterIndexChangesNothing() {
        Env e = env();
        assertEquals(SOURCE, MethodHandler.renameMethodParameter(e.cu(), SOURCE, method(e.cu(), "scale"), 7, "x"));
    }

    // ---- Return type ----

    @Test
    void aNewReturnTypeIsAppliedAndTheDefaultReturnFollowsIt() {
        Env e = env();
        FunctionDraft draft = draftFor(e.cu(), "caller");
        String result = apply(e, "caller",
                new FunctionDraft(draft.name(), SignatureType.kept("boolean"), draft.parameters()));

        assertTrue(result.contains("public boolean caller()"), result);
        assertTrue(result.contains("return false;"),
                "a method that gains a return type gains a default return, or it stops compiling: " + result);
    }

    /** A return the user wrote is theirs; only an untouched default may be replaced. */
    @Test
    void switchingToVoidDropsTheTrailingReturn() {
        Env e = env();
        FunctionDraft draft = draftFor(e.cu(), "scale");
        String result = apply(e, "scale",
                new FunctionDraft(draft.name(), SignatureType.of(BotType.Choice.of(BotType.NOTHING)),
                        draft.parameters()));

        assertTrue(result.contains("public void scale(int factor)"), result);
        assertTrue(!result.contains("return doubled;"),
                "a void method may not keep returning a value: " + result);
        assertTrue(result.contains("int doubled = factor * 2;"), "the rest of the body survives");
    }

    /**
     * A constructor has no return type, and the draft that describes it says it "gives nothing back" — which is
     * exactly what {@code void} is spelled as for a method. Applying that draft must not act on the likeness:
     * setting RETURN_TYPE2 here turns {@code Subject(int)} into a method named {@code Subject}, which compiles
     * and is then never called by anything.
     */
    @Test
    void editingAConstructorDoesNotGiveItAReturnType() {
        String source = """
                package com.mybot;
                public class Subject {
                    public Subject(int size) {
                    }
                }
                """;
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), source, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "fixture must parse");
        Env e = new Env(cu, new ProjectAnalyzer(null, new ProjectState()));

        MethodDeclaration constructor = method(cu, "Subject");
        FunctionDraft draft = MethodSignatures.draftOf(constructor).orElseThrow();
        FunctionDraft.Parameter size = draft.parameters().getFirst();
        String result = MethodHandler.applyFunctionSignature(e.ctx(), source, constructor,
                withParameters(draft, List.of(
                        new FunctionDraft.Parameter(size.name(), SignatureType.kept("double"), size.origin()))));

        assertTrue(result.contains("public Subject(double size)"), result);
        assertTrue(!result.contains("void Subject"), "a constructor must not gain a return type: " + result);
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
