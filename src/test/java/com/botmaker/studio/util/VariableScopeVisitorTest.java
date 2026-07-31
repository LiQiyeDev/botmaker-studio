package com.botmaker.studio.util;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio remainder MISSING 6 — {@link VariableScopeVisitor} scope resolution.</b>
 *
 * <p>374 lines that decide which variables a block may reference. Get it wrong in one direction and the
 * expression menu hides a variable the user can see on screen; wrong in the other and it offers one that is
 * out of scope, which generates source that does not compile — a bot that fails at build time with a message
 * about code the user never typed.
 *
 * <p>{@code ScopeAtLiteralNodeTest} already pins the single case that motivated the current implementation
 * (resolution at a non-trigger node). This covers the boundaries themselves: blocks, catch clauses, lambdas,
 * parameters, the field/local split, and the rule that a declaration cannot see itself.
 */
class VariableScopeVisitorTest {

    private static CompilationUnit parse(String source) {
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), source,
                Paths.get("src", "main", "java").toAbsolutePath(), "Demo.java");
        assertNotNull(cu, "the snippet must parse with bindings, or every assertion below is vacuous");
        return cu;
    }

    /** The node for a marker literal — {@code "@here"} — so a test can point at a spot inside real code. */
    private static ASTNode marker(CompilationUnit cu, String tag) {
        ASTNode[] found = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(StringLiteral node) {
                if (tag.equals(node.getLiteralValue())) found[0] = node;
                return true;
            }
            @Override public boolean visit(NumberLiteral node) {
                if (tag.equals(node.getToken())) found[0] = node;
                return true;
            }
        });
        assertNotNull(found[0], "marker " + tag + " not found in the snippet");
        return found[0];
    }

    private static Set<String> visibleAt(CompilationUnit cu, String tag) {
        return VariableScopeVisitor.getAvailableVariables(marker(cu, tag)).stream()
                .map(IVariableBinding::getName)
                .collect(Collectors.toSet());
    }

    // --- Fields and locals ---

    @Test
    void aFieldIsVisibleThroughoutItsTypeRegardlessOfDeclarationOrder() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() { String x = "@here"; }
                    private int declaredAfterTheMethod = 7;
                }
                """);

        assertTrue(visibleAt(cu, "@here").contains("declaredAfterTheMethod"),
                "fields are pre-registered for the whole type — Java's own rule, and the menus must match");
    }

    @Test
    void aMethodParameterIsInScopeInItsBody() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run(int amount, String label) { String x = "@here"; }
                }
                """);

        assertTrue(visibleAt(cu, "@here").containsAll(List.of("amount", "label")));
    }

    @Test
    void aLocalDeclaredLaterInTheSameBlockIsNotYetVisible() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() {
                        String here = "@here";
                        int later = 1;
                    }
                }
                """);

        assertFalse(visibleAt(cu, "@here").contains("later"),
                "locals are declared on endVisit, so a forward reference is never offered");
    }

    /** The whole reason declarations are registered on {@code endVisit}: {@code int n = n + 1} is not legal. */
    @Test
    void aDeclarationCannotSeeItselfInsideItsOwnInitializer() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    private int health = 100;
                    void run() { int computed = 42; }
                }
                """);

        assertFalse(visibleAt(cu, "42").contains("computed"),
                "a local must not appear in its own initializer");
    }

    @Test
    void aFieldCannotSeeItselfInsideItsOwnInitializer() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    private int other = 1;
                    private int health = 100;
                }
                """);

        Set<String> atHealthInitializer = visibleAt(cu, "100");
        assertFalse(atHealthInitializer.contains("health"),
                "fields are pre-registered for the type, so the enclosing declaration's own name is "
                        + "dropped explicitly — this is that filter");
        assertTrue(atHealthInitializer.contains("other"), "its siblings are still in scope");
    }

    // --- Block boundaries ---

    @Test
    void aLocalDeclaredInsideANestedBlockDoesNotLeakOutOfIt() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() {
                        if (true) { int inner = 1; }
                        String after = "@here";
                    }
                }
                """);

        assertFalse(visibleAt(cu, "@here").contains("inner"),
                "the block popped its scope; offering 'inner' here would generate uncompilable source");
    }

    @Test
    void aLocalFromAnEnclosingBlockIsVisibleInsideANestedOne() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() {
                        int outer = 1;
                        if (true) { String x = "@here"; }
                    }
                }
                """);

        assertTrue(visibleAt(cu, "@here").contains("outer"));
    }

    @Test
    void aLoopVariableIsInScopeInsideTheLoopBody() {
        CompilationUnit cu = parse("""
                package com.example;
                import java.util.List;
                public class Demo {
                    void run(List<String> items) {
                        for (String item : items) { String inside = "@inside"; }
                    }
                }
                """);

        assertTrue(visibleAt(cu, "@inside").contains("item"));
    }

    /**
     * <b>B22 — a loop or resource variable outlives its statement.</b> Only {@code Block},
     * {@code SwitchStatement}, {@code CatchClause}, the two declaration kinds and {@code LambdaExpression}
     * push a scope. {@code ForStatement}, {@code EnhancedForStatement} and {@code TryStatement} each open one
     * in the JLS and push none here, so their declarations land in the <em>enclosing</em> block and stay
     * there after the statement ends.
     *
     * <p>Measured on this commit, all three leak: {@code for (int i…)} leaves {@code i}, {@code for (String
     * item : …)} leaves {@code item}, {@code try (Reader r = …)} leaves {@code r}. This is the direction of
     * the failure that costs the user a build: the expression menu offers a name that is out of scope, and
     * the generated source does not compile — with an error pointing at code they never typed.
     */
    @Test
    @Disabled("B22 is unfixed: verified red on this commit — ForStatement, EnhancedForStatement and "
            + "TryStatement push no scope, so their declarations leak into the enclosing block. Delete this "
            + "line in Phase 4 with B22's fix.")
    void aLoopOrResourceVariableIsGoneAfterItsStatementEnds() {
        CompilationUnit enhancedFor = parse("""
                package com.example;
                import java.util.List;
                public class Demo {
                    void run(List<String> items) {
                        for (String item : items) { int b = 1; }
                        String outside = "@after";
                    }
                }
                """);
        assertFalse(visibleAt(enhancedFor, "@after").contains("item"), "an enhanced-for variable leaked");

        CompilationUnit classicFor = parse("""
                package com.example;
                public class Demo {
                    void run() {
                        for (int i = 0; i < 3; i++) { int b = 1; }
                        String outside = "@after";
                    }
                }
                """);
        assertFalse(visibleAt(classicFor, "@after").contains("i"), "a classic-for variable leaked");

        CompilationUnit tryResource = parse("""
                package com.example;
                import java.io.Reader;
                public class Demo {
                    void run() throws Exception {
                        try (Reader r = null) { int b = 1; }
                        String outside = "@after";
                    }
                }
                """);
        assertFalse(visibleAt(tryResource, "@after").contains("r"), "a try-with-resources variable leaked");
    }

    /** The boundaries that <em>are</em> pushed, so B22's fix is scoped to the three that are not. */
    @Test
    void aSwitchCasesLocalDoesNotOutliveTheSwitch() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run(int k) {
                        switch (k) { case 1: int inCase = 1; break; default: break; }
                        String outside = "@after";
                    }
                }
                """);

        assertFalse(visibleAt(cu, "@after").contains("inCase"));
    }

    @Test
    void aCaughtExceptionIsScopedToItsCatchClause() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() {
                        try { int a = 1; } catch (RuntimeException e) { String x = "@inside"; }
                        String y = "@after";
                    }
                }
                """);

        assertTrue(visibleAt(cu, "@inside").contains("e"));
        assertFalse(visibleAt(cu, "@after").contains("e"),
                "a catch parameter outliving its clause is the classic scope leak");
    }

    @Test
    void aLambdaParameterIsScopedToTheLambdaBody() {
        CompilationUnit cu = parse("""
                package com.example;
                import java.util.function.Consumer;
                public class Demo {
                    void run() {
                        Consumer<String> c = s -> { String x = "@inside"; };
                        String y = "@after";
                    }
                }
                """);

        assertTrue(visibleAt(cu, "@inside").contains("s"));
        assertFalse(visibleAt(cu, "@after").contains("s"));
    }

    @Test
    void aLambdaStillSeesTheEnclosingMethodsLocals() {
        CompilationUnit cu = parse("""
                package com.example;
                import java.util.function.Consumer;
                public class Demo {
                    void run(int captured) {
                        Consumer<String> c = s -> { String x = "@inside"; };
                    }
                }
                """);

        assertTrue(visibleAt(cu, "@inside").contains("captured"),
                "capture is what a lambda is for; hiding it would break every callback the palette offers");
    }

    /** One method's locals must never surface in another's — the stacks are popped on {@code endVisit}. */
    @Test
    void oneMethodsLocalsAreInvisibleInAnother() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void first() { int onlyInFirst = 1; }
                    void second() { String x = "@here"; }
                }
                """);

        assertFalse(visibleAt(cu, "@here").contains("onlyInFirst"));
    }

    // --- Shadowing ---

    /**
     * A local shadowing a field leaves <em>both</em> bindings in the snapshot — the stacks are flattened, not
     * merged by name. Innermost comes first (the deque is iterated from the top), so a consumer taking the
     * first match by name gets the one Java would resolve to; a consumer building a menu shows the name twice.
     * Pinned because which of those two a caller does is invisible from here.
     */
    @Test
    void aLocalShadowingAFieldLeavesBothBindingsWithTheInnermostFirst() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    private String health = "field";
                    void run() {
                        int health = 1;
                        String x = "@here";
                    }
                }
                """);

        List<IVariableBinding> visible = VariableScopeVisitor.getAvailableVariables(marker(cu, "@here"));
        List<IVariableBinding> named = visible.stream().filter(b -> "health".equals(b.getName())).toList();

        assertEquals(2, named.size(), "both the local and the shadowed field are present");
        assertEquals("int", named.getFirst().getType().getName(),
                "innermost first — the local wins for any caller taking the first match");
    }

    // --- Types and methods ---

    @Test
    void theEnclosingTypeAndItsMethodsAreInScopeInsideIt() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void helper(int a) {}
                    void run() { String x = "@here"; }
                }
                """);

        ASTNode at = marker(cu, "@here");
        assertTrue(VariableScopeVisitor.getAvailableTypes(at).stream()
                        .anyMatch(t -> "Demo".equals(t.getName())),
                "a type must be visible to its own body");
        assertTrue(VariableScopeVisitor.getAvailableMethods(at).stream()
                        .anyMatch(m -> "helper".equals(m.getName())),
                "sibling methods are visible regardless of declaration order");
    }

    @Test
    void inheritedMethodsAreCollectedFromTheSuperclassChain() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run() { String x = "@here"; }
                }
                """);

        assertTrue(VariableScopeVisitor.getAvailableMethods(marker(cu, "@here")).stream()
                        .anyMatch(m -> "toString".equals(m.getName())),
                "Object's members are inherited by everything and must be offerable");
    }

    // --- The whole-file map ---

    @Test
    void theFullFileAnalysisAnswersForEveryRecordedNode() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run(int amount) {
                        int doubled = amount * 2;
                    }
                }
                """);

        VariableScopeVisitor.ScopeResult result = VariableScopeVisitor.analyze(cu);
        assertFalse(result.variables().isEmpty(), "analyze() must record the nodes it visited");

        SimpleName[] use = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName node) {
                if ("amount".equals(node.getIdentifier()) && node.resolveBinding() instanceof IVariableBinding) {
                    use[0] = node;
                }
                return true;
            }
        });
        assertNotNull(use[0]);
        assertTrue(result.at(use[0]).variables().stream().anyMatch(b -> "amount".equals(b.getName())),
                "the map must answer for a node it recorded");
    }

    @Test
    void aNodeTheAnalysisNeverRecordedAnswersEmptyRatherThanNull() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() {} }
                """);

        VariableScopeVisitor.NodeScope scope = VariableScopeVisitor.analyze(cu).at(cu);
        assertEquals(List.of(), scope.variables());
        assertEquals(List.of(), scope.methods());
        assertEquals(List.of(), scope.types());
    }
}
