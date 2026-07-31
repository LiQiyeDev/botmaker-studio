package com.botmaker.studio.suggestions;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio remainder MISSING 5 — {@code ProjectAnalyzer} sections 7–9 in isolation.</b> Gates <b>SC8</b>.
 *
 * <p>Three of {@code ProjectAnalyzer}'s twelve numbered sections are still labelled "(from old TypeManager)":
 * type inference, AST node creation, and utility. Every member of all three is {@code public static} and
 * depends on nothing the class holds — which is the audit's case for extracting them, and is what makes them
 * testable here without a project, an index or a state.
 *
 * <p>This is the safety net for that extraction. It asserts the three sections through their public surface
 * only, so the split can move them to {@code TypeInference} / {@code CompilationUnits} and this file keeps
 * passing with an import change.
 */
class ProjectAnalyzerStaticSectionsTest {

    private static CompilationUnit parse(String source) {
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), source,
                Paths.get("src", "main", "java").toAbsolutePath(), "Demo.java");
        assertNotNull(cu);
        return cu;
    }

    /** The node for a marker literal, so a test can point at an expression slot inside real code. */
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
        assertNotNull(found[0], "marker " + tag + " not found");
        return found[0];
    }

    // =====================================================================
    // 7. Type inference — what type belongs in this hole?
    // =====================================================================

    @Test
    void anInitializerIsExpectedToBeTheDeclaredType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() { int amount = 42; } }
                """);

        assertEquals("int", ProjectAnalyzer.inferExpectedType(marker(cu, "42")).qualifiedName());
    }

    @Test
    void aFieldInitializerIsExpectedToBeTheFieldsType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { private String name = "@here"; }
                """);

        assertTrue(ProjectAnalyzer.inferExpectedType(marker(cu, "@here")).isString());
    }

    @Test
    void theRightHandSideOfAnAssignmentIsExpectedToBeTheLeftHandSidesType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    private double ratio;
                    void run() { ratio = 42; }
                }
                """);

        assertEquals("double", ProjectAnalyzer.inferExpectedType(marker(cu, "42")).qualifiedName());
    }

    @Test
    void aReturnedExpressionIsExpectedToBeTheMethodsReturnType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { boolean ready() { return true; } }
                """);

        ASTNode[] found = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(org.eclipse.jdt.core.dom.BooleanLiteral node) {
                found[0] = node;
                return true;
            }
        });
        assertEquals("boolean", ProjectAnalyzer.inferExpectedType(found[0]).qualifiedName());
    }

    @Test
    void anArgumentIsExpectedToBeItsParametersType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void helper(String label, int count) {}
                    void run() { helper("@here", 1); }
                }
                """);

        assertTrue(ProjectAnalyzer.inferExpectedType(marker(cu, "@here")).isString());
    }

    /**
     * {@code System.out.println} takes anything, so inferring {@code String} from its {@code (String)}
     * overload would filter the expression menu down to text — in the one call where every type is valid.
     * The special case is deliberate; this is what says so.
     */
    @Test
    void anArgumentToPrintlnIsExpectedToBeAnythingAtAll() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() { System.out.println("@here"); } }
                """);

        assertTrue(ProjectAnalyzer.inferExpectedType(marker(cu, "@here")).isUnknown(),
                "println accepts every type; narrowing it would hide most of the menu");
    }

    @Test
    void aSwitchLabelIsExpectedToBeTheSwitchedExpressionsType() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    void run(int k) { switch (k) { case 42: break; default: break; } }
                }
                """);

        assertEquals("int", ProjectAnalyzer.inferExpectedType(marker(cu, "42")).qualifiedName());
    }

    @Test
    void anExpressionInNoMeaningfulPositionInfersNothingRatherThanGuessing() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() { int a = 1; if (a > 0) { String s = "x"; } } }
                """);

        assertTrue(ProjectAnalyzer.inferExpectedType(null).isUnknown(), "a null node must not throw");
        assertTrue(ProjectAnalyzer.inferExpectedType(marker(cu, "0")).isUnknown(),
                "an operand of a comparison carries no declared expectation");
    }

    // =====================================================================
    // 8. AST node creation — the type that goes into generated source
    // =====================================================================

    @Test
    void eachPrimitiveBecomesAPrimitiveTypeNodeRatherThanANamedOne() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        for (String name : List.of("int", "double", "boolean", "char", "long", "float", "short", "byte", "void")) {
            Type node = ProjectAnalyzer.createTypeNode(ast, name);
            assertTrue(node.isPrimitiveType(), name + " must not be written as a simple type");
            assertEquals(name, node.toString());
        }
    }

    @Test
    void anArrayNameBecomesAnArrayTypeOfTheRightDepth() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);

        Type node = ProjectAnalyzer.createTypeNode(ast, "int[][]");
        assertTrue(node.isArrayType());
        assertEquals(2, ((org.eclipse.jdt.core.dom.ArrayType) node).getDimensions());
        assertEquals("int[][]", node.toString());
    }

    /**
     * A generic name would blow up {@code ast.newName} with "Invalid identifier", so the arguments are cut
     * off and the raw type is written. The generated call still compiles; the type argument is inferred.
     */
    @Test
    void aGenericNameIsWrittenAsItsRawType() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);

        assertEquals("Consumer", ProjectAnalyzer.createTypeNode(ast, "java.util.function.Consumer<String>")
                .toString().substring("java.util.function.".length()));
    }

    @Test
    void anUnknownTypeIsWrittenAsObjectRatherThanNothing() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);

        assertEquals("Object", ProjectAnalyzer.createTypeNode(ast, (ResolvedType) null).toString());
        assertEquals("Object", ProjectAnalyzer.createTypeNode(ast, ResolvedType.UNKNOWN).toString());
    }

    /** The simple-name variant is for callers that also add an import — the qualifier must be dropped. */
    @Test
    void theSimpleVariantWritesTheLeafNameAndKeepsTheBrackets() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);

        assertEquals("Point", ProjectAnalyzer.createSimpleTypeNode(
                ast, ResolvedType.named("com.botmaker.sdk.api.Point")).toString());
        assertEquals("Point[]", ProjectAnalyzer.createSimpleTypeNode(
                ast, ResolvedType.named("com.botmaker.sdk.api.Point[]")).toString());
    }

    @Test
    void aQualifiedNameSurvivesIntoTheGeneratedNode() {
        AST ast = AST.newAST(AST.getJLSLatest(), false);

        assertEquals("com.botmaker.sdk.api.Point",
                ProjectAnalyzer.createTypeNode(ast, ResolvedType.named("com.botmaker.sdk.api.Point")).toString());
    }

    // =====================================================================
    // 9. Utility
    // =====================================================================

    @Test
    void aListTypeIsUnwrappedToItsElementType() {
        assertEquals("String", ProjectAnalyzer.unwrapCollectionType("List<String>"));
        assertEquals("String", ProjectAnalyzer.unwrapCollectionType("ArrayList<String>"));
        assertEquals("Map<String,Integer>", ProjectAnalyzer.unwrapCollectionType("List<Map<String,Integer>>"),
                "the outermost list is peeled once, not recursively");
    }

    @Test
    void aNonCollectionNameIsHandedBackUntouched() {
        assertEquals("String", ProjectAnalyzer.unwrapCollectionType("String"));
        assertEquals("Set<String>", ProjectAnalyzer.unwrapCollectionType("Set<String>"),
                "only List/ArrayList are unwrapped — anything else keeps its arguments");
        assertEquals("Object", ProjectAnalyzer.unwrapCollectionType(null));
    }

    @Test
    void aGeneratedOrHiddenNameIsNotAUserVariable() {
        assertFalse(ProjectAnalyzer.isUserVariable("_internal"), "a leading underscore marks generated state");
        assertFalse(ProjectAnalyzer.isUserVariable(null));
        assertFalse(ProjectAnalyzer.isUserVariable(""));
        assertTrue(ProjectAnalyzer.isUserVariable("health"));
    }

    /** The menus label variables {@code "name : Type"}, so the decoration has to be stripped before the check. */
    @Test
    void aDecoratedMenuLabelIsCheckedByItsBareName() {
        assertTrue(ProjectAnalyzer.isUserVariable("health : int"));
        assertFalse(ProjectAnalyzer.isUserVariable("_hidden : int"));
    }

    @Test
    void aPlainExpressionIsItsOwnLeafValue() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() { String s = "@here"; } }
                """);

        List<Expression> leaves = new ArrayList<>();
        ProjectAnalyzer.collectLeafValues((Expression) marker(cu, "@here"), leaves);

        assertEquals(1, leaves.size());
        assertEquals("\"@here\"", leaves.getFirst().toString());
    }

    @Test
    void everyElementOfAListLiteralIsCollectedAsALeaf() {
        CompilationUnit cu = parse("""
                package com.example;
                import java.util.List;
                public class Demo { void run() { List<String> l = List.of("a", "b", "c"); } }
                """);

        Expression[] call = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(org.eclipse.jdt.core.dom.MethodInvocation node) {
                if ("of".equals(node.getName().getIdentifier())) call[0] = node;
                return true;
            }
        });
        List<Expression> leaves = new ArrayList<>();
        ProjectAnalyzer.collectLeafValues(call[0], leaves);

        assertEquals(List.of("\"a\"", "\"b\"", "\"c\""), leaves.stream().map(Object::toString).toList(),
                "the container itself is not a value; its elements are");
    }

    @Test
    void anArrayInitializerIsFlattenedToItsElements() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo { void run() { int[] xs = new int[] { 1, 2 }; } }
                """);

        Expression[] creation = { null };
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(org.eclipse.jdt.core.dom.ArrayCreation node) {
                creation[0] = node;
                return true;
            }
        });
        List<Expression> leaves = new ArrayList<>();
        ProjectAnalyzer.collectLeafValues(creation[0], leaves);

        assertEquals(List.of("1", "2"), leaves.stream().map(Object::toString).toList());
    }

    @Test
    void collectingFromNothingAddsNothingRatherThanThrowing() {
        List<Expression> leaves = new ArrayList<>();
        ProjectAnalyzer.collectLeafValues(null, leaves);
        assertEquals(List.of(), leaves);
    }

    @Test
    void anEnumDeclaredInTheFileIsRecognisedEvenWithoutABinding() {
        CompilationUnit cu = parse("""
                package com.example;
                public class Demo {
                    enum Mode { FAST, SLOW }
                    void run() {}
                }
                """);

        assertTrue(ProjectAnalyzer.isEnumType(ResolvedType.named("Mode"), cu),
                "a name-only type is checked against the file's own enum declarations");
        assertFalse(ProjectAnalyzer.isEnumType(ResolvedType.named("Missing"), cu));

        assertNotNull(ProjectAnalyzer.findEnumDeclaration(cu, "Mode"));
        assertEquals(List.of("FAST", "SLOW"),
                ProjectAnalyzer.getEnumConstantNames(ProjectAnalyzer.findEnumDeclaration(cu, "Mode")));
    }
}
