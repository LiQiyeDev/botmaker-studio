import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.util.VariableScopeVisitor;
import org.eclipse.jdt.core.dom.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the app's single-file parse path ({@link ProjectAnalyzer#createCompilationUnit}) and asserts the
 * exact scenario the menu relies on: scope resolution at a NON-trigger node (a literal being replaced).
 * Self-contained (one snippet, deterministic) so it isolates the fix from the flaky multi-file harness.
 */
public class ScopeAtLiteralNodeTest {

    private static final String SRC = """
        package com.example;
        public class Demo {
            private int health = 100;
            private String name;
            public int compute(int amount, String label) {
                int local = 0;
                return local;
            }
        }
        """;

    private CompilationUnit parse() {
        return ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(),
                SRC,
                Paths.get("src", "main", "java").toAbsolutePath(),
                "Demo.java");
    }

    /** The `0` initializer of `int local = 0` is a NumberLiteral — never a scope "trigger" node. */
    private NumberLiteral findZeroLiteral(CompilationUnit cu) {
        NumberLiteral[] found = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(NumberLiteral node) {
                if ("0".equals(node.getToken())) found[0] = node;
                return true;
            }
        });
        return found[0];
    }

    @Test
    void bindingsResolveOnSingleFileParse() {
        CompilationUnit cu = parse();
        TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
        assertNotNull(type.resolveBinding(), "Type binding must resolve (setUnitName)");
        // A field's name must resolve to a variable binding — the thing the scope visitor needs.
        VariableDeclarationFragment field = (VariableDeclarationFragment)
                type.getFields()[0].fragments().get(0);
        assertNotNull(field.getName().resolveBinding(), "Field binding must resolve");
    }

    /** The menu passes the block's own node (here the `int local = …` statement) as the context node. */
    @Test
    void scopeAtDeclarationStatementSeesOthersButNotSelf() {
        CompilationUnit cu = parse();
        VariableDeclarationStatement[] decl = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(VariableDeclarationStatement node) {
                if (node.fragments().toString().contains("local")) decl[0] = node;
                return true;
            }
        });
        assertNotNull(decl[0], "snippet should contain the 'local' declaration");

        List<String> visible = VariableScopeVisitor.getAvailableVariables(decl[0]).stream()
                .map(IVariableBinding::getName).collect(Collectors.toList());
        assertTrue(visible.contains("amount"), "param 'amount' should be visible at the declaration, got " + visible);
        assertTrue(visible.contains("health"), "field 'health' should be visible at the declaration, got " + visible);
        assertFalse(visible.contains("local"), "'local' must not see itself at its own declaration");
    }

    @Test
    void scopeAtLiteralSeesFieldsParamsAndLocals() {
        CompilationUnit cu = parse();
        NumberLiteral zero = findZeroLiteral(cu);
        assertNotNull(zero, "test snippet should contain a 0 literal");

        List<String> visible = VariableScopeVisitor.getAvailableVariables(zero).stream()
                .map(IVariableBinding::getName).collect(Collectors.toList());

        assertTrue(visible.contains("health"), "field 'health' should be visible, got " + visible);
        assertTrue(visible.contains("name"), "field 'name' should be visible, got " + visible);
        assertTrue(visible.contains("amount"), "param 'amount' should be visible, got " + visible);
        assertTrue(visible.contains("label"), "param 'label' should be visible, got " + visible);
        // 'local' is being declared by the statement containing this literal — must NOT see itself.
        assertFalse(visible.contains("local"), "'local' must not see itself in its own initializer");
    }
}
