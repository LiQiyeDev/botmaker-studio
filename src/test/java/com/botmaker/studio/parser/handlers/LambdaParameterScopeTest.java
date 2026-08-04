package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of "the user can actually reach the value the vision loop found": the parameter is
 * <b>in scope</b> for the body (so the expression menu offers it), and <b>renaming</b> it carries the body's
 * references along (so the rename doesn't break the bot).
 *
 * <p>Both are asserted <em>without</em> resolved bindings, which is the case that matters and the one that was
 * broken: an inferred-type lambda parameter ({@code found -> …}) only gets an {@code IVariableBinding} once JDT
 * has resolved the target type from the SDK jar, and in the editor that resolution is routinely absent. The
 * binding-backed scope walker then reports no such variable at all — which is exactly why the body of a
 * {@code whileFindAny} offered nothing to click.
 */
class LambdaParameterScopeTest {

    /** Parses without a classpath — so no bindings resolve, which is the point (see the class javadoc). */
    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static final String POPUP_LOOP = """
            package test;
            public class Subject {
                void run() {
                    ImageFinder.whileFindAny(POPUPS, found -> {
                        if (found.has(mail)) {
                            ImageClicker.click(found.get(mail));
                        }
                    });
                }
            }
            """;

    private static MethodInvocation callNamed(CompilationUnit cu, String name) {
        AtomicReference<MethodInvocation> out = new AtomicReference<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation mi) {
                if (mi.getName().getIdentifier().equals(name) && out.get() == null) out.set(mi);
                return true;
            }
        });
        return out.get();
    }

    // ---- In scope ----

    @Test
    void theLambdaParameterIsVisibleInsideTheBodyWithoutBindings() {
        CompilationUnit cu = parse(POPUP_LOOP);
        MethodInvocation click = callNamed(cu, "click");
        assertNotNull(click, "fixture should contain the ImageClicker.click call inside the body");

        ProjectAnalyzer analyzer = new ProjectAnalyzer(null, new ProjectState());
        List<String> names = analyzer.getVisibleVariables(click, ResolvedType.UNKNOWN).stream()
                .map(ProjectAnalyzer.VariableOption::name).toList();

        assertTrue(names.contains("found"),
                "the value the loop hands the body must be an in-scope variable, else the expression menu "
                        + "inside the body has nothing to offer: " + names);
    }

    @Test
    void theLambdaParameterIsNotVisibleOutsideItsBody() {
        CompilationUnit cu = parse(POPUP_LOOP);
        MethodInvocation outer = callNamed(cu, "whileFindAny");

        ProjectAnalyzer analyzer = new ProjectAnalyzer(null, new ProjectState());
        List<String> names = analyzer.getVisibleVariables(outer, ResolvedType.UNKNOWN).stream()
                .map(ProjectAnalyzer.VariableOption::name).toList();

        assertFalse(names.contains("found"), "the parameter's scope is the lambda body, not the call: " + names);
    }

    // ---- Rename ----

    @Test
    void renamingTheParameterCarriesEveryReferenceInTheBody() {
        CompilationUnit cu = parse(POPUP_LOOP);
        SimpleName declared = LambdaCallHandler.lambdaParamName(callNamed(cu, "whileFindAny"));
        assertNotNull(declared);

        String result = AstRewriteHelper.renameLambdaParameter(cu, POPUP_LOOP, declared, "popups");

        assertTrue(result.contains("popups -> {"), "the declaration must be renamed: " + result);
        assertTrue(result.contains("popups.has(mail)") && result.contains("popups.get(mail)"),
                "every reference must follow, or the rename leaves the bot uncompilable: " + result);
        assertFalse(result.contains("found"), "no trace of the old name may survive: " + result);
    }

    /** A method named the same as the parameter is not a reference to it. */
    @Test
    void renamingDoesNotTouchSameNamedMethodsOrFields() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(POPUPS, found -> {
                            other.found();
                            BotMaker.print(config.found);
                        });
                    }
                }
                """;
        CompilationUnit cu = parse(source);
        SimpleName declared = LambdaCallHandler.lambdaParamName(callNamed(cu, "whileFindAny"));

        String result = AstRewriteHelper.renameLambdaParameter(cu, source, declared, "popups");

        assertTrue(result.contains("popups -> {"), "the declaration is still renamed: " + result);
        assertTrue(result.contains("other.found()"), "a method call is not a variable reference: " + result);
        assertTrue(result.contains("config.found"), "a field access is not a variable reference: " + result);
    }

    /** A nested lambda that redeclares the name shadows the outer one; its body must be left alone. */
    @Test
    void renamingStopsAtAShadowingNestedLambda() {
        String source = """
                package test;
                public class Subject {
                    void run() {
                        ImageFinder.whileFindAny(POPUPS, found -> {
                            ImageClicker.click(found.best());
                            ImageFinder.ifFindAny(OTHER, found -> {
                                ImageClicker.click(found.best());
                            });
                        });
                    }
                }
                """;
        CompilationUnit cu = parse(source);
        SimpleName declared = LambdaCallHandler.lambdaParamName(callNamed(cu, "whileFindAny"));

        String result = AstRewriteHelper.renameLambdaParameter(cu, source, declared, "popups");

        assertTrue(result.contains("popups -> {"), "the outer declaration is renamed: " + result);
        assertTrue(result.contains("popups.best()"), "the outer body's reference follows: " + result);
        assertEquals(1, result.split("found -> \\{", -1).length - 1,
                "the shadowing inner lambda keeps its own name: " + result);
        assertTrue(result.contains("""
                ifFindAny(OTHER, found -> {
                                ImageClicker.click(found.best());"""),
                "the inner body binds to the inner parameter and must not be rewritten: " + result);
    }
}
