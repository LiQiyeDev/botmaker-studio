import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.types.ResolvedType;
import com.botmaker.util.VariableScopeVisitor;
import org.eclipse.jdt.core.dom.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that variable and method suggestions are correctly filtered by the
 * expected type at a given AST node.
 *
 * Uses a self-contained synthetic Java class so tests are deterministic and
 * independent of the project source changing over time.
 */
public class TypeAwareSuggestionTest {

    // -----------------------------------------------------------------------
    // Synthetic test source
    // -----------------------------------------------------------------------

    private static final String SOURCE = """
            package test;

            import java.util.List;
            import java.util.ArrayList;

            public class Subject {
                private String  name;
                private int     count;
                private boolean active;

                public String  getName()    { return name; }
                public int     getCount()   { return count; }
                public boolean isActive()   { return active; }

                /** All three primitive categories present — used for type-filter assertions. */
                public void mixed(String inputStr, int inputInt, boolean inputBool) {
                    String  localStr  = inputStr;
                    int     localInt  = inputInt;
                    boolean localBool = inputBool;

                    String  result = localStr;   // ← target A: initializer, expected = String
                    int     number = localInt;   // ← target B: initializer, expected = int
                    boolean flag   = localBool;  // ← target C: initializer, expected = boolean
                }

                /** Used for return-statement inference. */
                public String returnsString() {
                    String x = "hello";
                    return x;   // ← target D: return expr, expected = String
                }

                /** Used for subtype (isAssignmentCompatible) assertion. */
                public void subtypeParam(List<String> list) {
                    ArrayList<String> concrete = new ArrayList<>();
                    List<String> result2 = concrete;   // ← target E: ArrayList fits List
                }

                /** Used for method-argument inference. */
                public void callSite() {
                    String arg = "test";
                    takesString(arg);   // ← target F: arg inside call, expected = String
                }

                private void takesString(String s) {}
            }
            """;

    private CompilationUnit cu;

    @BeforeEach
    void parse() {
        cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), SOURCE, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "Synthetic source must parse without error");
    }

    // -----------------------------------------------------------------------
    // inferExpectedType — structural inference
    // -----------------------------------------------------------------------

    @Test
    void inferStringForStringVariableInitializer() {
        ASTNode init = initializerOf("result");
        assertNotNull(init, "Should locate 'result' initializer");
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(init);
        assertTrue(expected.isString(),
                "Expected type at String variable initializer should be String, got: " + expected);
    }

    @Test
    void inferNumericForIntVariableInitializer() {
        ASTNode init = initializerOf("number");
        assertNotNull(init);
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(init);
        assertTrue(expected.isNumeric(),
                "Expected type at int variable initializer should be numeric, got: " + expected);
    }

    @Test
    void inferBooleanForBooleanVariableInitializer() {
        ASTNode init = initializerOf("flag");
        assertNotNull(init);
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(init);
        assertTrue(expected.isBoolean(),
                "Expected type at boolean variable initializer should be boolean, got: " + expected);
    }

    @Test
    void inferStringForReturnStatementInStringMethod() {
        ASTNode returnExpr = returnExprIn("returnsString");
        assertNotNull(returnExpr, "Should locate return expression in returnsString()");
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(returnExpr);
        assertTrue(expected.isString(),
                "Expected type at return in String-returning method should be String, got: " + expected);
    }

    @Test
    void inferStringForMethodCallArgument() {
        ASTNode arg = firstCallArgIn("callSite");
        assertNotNull(arg, "Should locate first argument of method call in callSite()");
        ResolvedType expected = ProjectAnalyzer.inferExpectedType(arg);
        assertFalse(expected.isUnknown(),
                "Expected type at method call argument should be resolvable");
        assertTrue(expected.isString(),
                "Expected type at takesString(arg) argument should be String, got: " + expected);
    }

    // -----------------------------------------------------------------------
    // Type-filtered variable suggestions
    // -----------------------------------------------------------------------

    @Test
    void onlyStringVariablesSuggestedAtStringPosition() {
        ASTNode init = initializerOf("result");
        assertNotNull(init);

        ITypeBinding strType = bindingOf("String");
        assumeTrue(strType != null, "String binding must resolve for this test");

        List<String> names = VariableScopeVisitor.getAvailableVariables(init, strType)
                .stream().map(IVariableBinding::getName).toList();

        assertAll("String variables should be included",
                () -> assertTrue(names.contains("name"),     "field 'name' (String)"),
                () -> assertTrue(names.contains("inputStr"), "param 'inputStr' (String)"),
                () -> assertTrue(names.contains("localStr"), "local 'localStr' (String)")
        );
        assertAll("Non-String variables must be excluded",
                () -> assertFalse(names.contains("count"),     "int field 'count'"),
                () -> assertFalse(names.contains("inputInt"),  "int param 'inputInt'"),
                () -> assertFalse(names.contains("localInt"),  "int local 'localInt'"),
                () -> assertFalse(names.contains("active"),    "boolean field 'active'"),
                () -> assertFalse(names.contains("inputBool"), "boolean param 'inputBool'"),
                () -> assertFalse(names.contains("localBool"), "boolean local 'localBool'")
        );
    }

    @Test
    void onlyIntVariablesSuggestedAtIntPosition() {
        ASTNode init = initializerOf("number");
        assertNotNull(init);

        ITypeBinding intType = bindingOf("int");
        assumeTrue(intType != null, "int binding must resolve");

        List<String> names = VariableScopeVisitor.getAvailableVariables(init, intType)
                .stream().map(IVariableBinding::getName).toList();

        assertAll("int variables should be included",
                () -> assertTrue(names.contains("count"),    "field 'count' (int)"),
                () -> assertTrue(names.contains("inputInt"), "param 'inputInt' (int)"),
                () -> assertTrue(names.contains("localInt"), "local 'localInt' (int)")
        );
        assertAll("Non-int variables must be excluded",
                () -> assertFalse(names.contains("name"),     "String field 'name'"),
                () -> assertFalse(names.contains("inputStr"), "String param 'inputStr'"),
                () -> assertFalse(names.contains("active"),   "boolean field 'active'")
        );
    }

    // -----------------------------------------------------------------------
    // Type-filtered method suggestions (by return type)
    // -----------------------------------------------------------------------

    @Test
    void onlyStringMethodsSuggestedAtStringPosition() {
        ASTNode init = initializerOf("result");
        assertNotNull(init);

        ITypeBinding strType = bindingOf("String");
        assumeTrue(strType != null);

        List<String> methodNames = VariableScopeVisitor.getAvailableMethods(init, strType)
                .stream().map(IMethodBinding::getName).toList();

        assertTrue(methodNames.contains("getName"),
                "getName() returns String — must be suggested");
        assertFalse(methodNames.contains("getCount"),
                "getCount() returns int — must not be suggested for String position");
        assertFalse(methodNames.contains("isActive"),
                "isActive() returns boolean — must not be suggested for String position");
    }

    @Test
    void onlyIntMethodsSuggestedAtIntPosition() {
        ASTNode init = initializerOf("number");
        assertNotNull(init);

        ITypeBinding intType = bindingOf("int");
        assumeTrue(intType != null);

        List<String> methodNames = VariableScopeVisitor.getAvailableMethods(init, intType)
                .stream().map(IMethodBinding::getName).toList();

        assertTrue(methodNames.contains("getCount"),
                "getCount() returns int — must be suggested");
        assertFalse(methodNames.contains("getName"),
                "getName() returns String — must not be suggested for int position");
        assertFalse(methodNames.contains("isActive"),
                "isActive() returns boolean — must not be suggested for int position");
    }

    // -----------------------------------------------------------------------
    // Subtype compatibility (isAssignmentCompatible, not isEqualTo)
    // -----------------------------------------------------------------------

    @Test
    void arrayListVariableSuggestedWhereListExpected() {
        // 'List<String> result2 = concrete' — concrete is ArrayList, expected is List
        ASTNode init = initializerOf("result2");
        assertNotNull(init, "Should locate 'result2' initializer");

        ITypeBinding listType = bindingOf("List");
        assumeTrue(listType != null, "List binding must resolve");

        List<String> names = VariableScopeVisitor.getAvailableVariables(init, listType)
                .stream().map(IVariableBinding::getName).toList();

        assertTrue(names.contains("concrete"),
                "ArrayList variable 'concrete' must be suggested where List is expected (subtype)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns the initializer expression of the first variable named {@code varName}. */
    private ASTNode initializerOf(String varName) {
        ASTNode[] found = {null};
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (found[0] == null
                        && node.getName().getIdentifier().equals(varName)
                        && node.getInitializer() != null) {
                    found[0] = node.getInitializer();
                }
                return true;
            }
        });
        return found[0];
    }

    /** Returns the expression of the first {@code return} statement in {@code methodName}. */
    private ASTNode returnExprIn(String methodName) {
        ASTNode[] found = {null};
        cu.accept(new ASTVisitor() {
            private boolean inside = false;

            @Override
            public boolean visit(MethodDeclaration node) {
                inside = node.getName().getIdentifier().equals(methodName);
                return true;
            }

            @Override
            public void endVisit(MethodDeclaration node) { inside = false; }

            @Override
            public boolean visit(ReturnStatement node) {
                if (inside && found[0] == null && node.getExpression() != null)
                    found[0] = node.getExpression();
                return true;
            }
        });
        return found[0];
    }

    /** Returns the first argument expression of the first method call inside {@code methodName}. */
    private ASTNode firstCallArgIn(String methodName) {
        ASTNode[] found = {null};
        cu.accept(new ASTVisitor() {
            private boolean inside = false;

            @Override
            public boolean visit(MethodDeclaration node) {
                inside = node.getName().getIdentifier().equals(methodName);
                return true;
            }

            @Override
            public void endVisit(MethodDeclaration node) { inside = false; }

            @Override
            public boolean visit(MethodInvocation node) {
                if (inside && found[0] == null && !node.arguments().isEmpty())
                    found[0] = (ASTNode) node.arguments().get(0);
                return true;
            }
        });
        return found[0];
    }

    /**
     * Resolves the {@link ITypeBinding} for a simple type name by scanning the
     * variable declarations in the synthetic source.
     */
    private ITypeBinding bindingOf(String typeName) {
        ITypeBinding[] found = {null};
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                if (found[0] == null && node.getType().toString().equals(typeName)) {
                    ITypeBinding b = node.getType().resolveBinding();
                    if (b != null) found[0] = b;
                }
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                if (found[0] == null && node.getType().toString().equals(typeName)) {
                    ITypeBinding b = node.getType().resolveBinding();
                    if (b != null) found[0] = b;
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                if (found[0] == null && node.getType().toString().equals(typeName)) {
                    ITypeBinding b = node.getType().resolveBinding();
                    if (b != null) found[0] = b;
                }
                return true;
            }
        });
        return found[0];
    }
}
