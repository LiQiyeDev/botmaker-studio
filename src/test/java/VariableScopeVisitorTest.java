import com.botmaker.util.VariableScopeVisitor;
import org.eclipse.jdt.core.dom.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class VariableScopeVisitorTest {

    private Map<String, CompilationUnit> parsedUnits;

    @BeforeAll
    void setup() throws IOException {
        parsedUnits = TestSupport.parseProjectSources();
        System.out.println("Parsed " + parsedUnits.size() + " files from " + TestSupport.SOURCE_ROOT);
    }

    // -----------------------------------------------------------------------
    // Variables
    // -----------------------------------------------------------------------

    @Test
    void variableDeclarationDoesNotSeeItself() {
        forEachNode(VariableDeclarationFragment.class, (cu, node) -> {
            String name = node.getName().getIdentifier();
            List<String> visible = VariableScopeVisitor.getAvailableVariables(node).stream()
                    .map(IVariableBinding::getName).toList();
            assertFalse(visible.contains(name),
                    "Variable '" + name + "' must not see itself in its own initializer");
        });
    }

    @Test
    void fieldsAreVisibleInsideMethods() {
        forEachNode(MethodDeclaration.class, (cu, method) -> {
            if (method.getBody() == null) return;
            TypeDeclaration type = enclosingType(method);
            if (type == null) return;

            Set<String> fields = Arrays.stream(type.getFields())
                    .flatMap(f -> ((List<?>) f.fragments()).stream())
                    .map(f -> ((VariableDeclarationFragment) f).getName().getIdentifier())
                    .collect(Collectors.toSet());
            if (fields.isEmpty()) return;

            ASTNode firstNode = firstNodeInside(method);
            if (firstNode == null) return;

            Set<String> visible = VariableScopeVisitor.getAvailableVariables(firstNode).stream()
                    .map(IVariableBinding::getName).collect(Collectors.toSet());

            fields.forEach(field -> assertTrue(visible.contains(field),
                    "Field '" + field + "' not visible inside '" + method.getName() + "'"));
        });
    }

    @Test
    void localVariablesDoNotLeakIntoSiblingMethods() {
        forEachNode(TypeDeclaration.class, (cu, type) -> {
            List<MethodDeclaration> methods = Arrays.asList(type.getMethods());
            if (methods.size() < 2) return;

            MethodDeclaration m1 = methods.get(0);
            MethodDeclaration m2 = methods.get(1);
            Set<String> m1Locals = localsOf(m1);
            if (m1Locals.isEmpty()) return;

            ASTNode firstInM2 = firstNodeInside(m2);
            if (firstInM2 == null) return;

            Set<String> visibleInM2 = VariableScopeVisitor.getAvailableVariables(firstInM2).stream()
                    .map(IVariableBinding::getName).collect(Collectors.toSet());

            m1Locals.forEach(local -> assertFalse(visibleInM2.contains(local),
                    "Local '" + local + "' from '" + m1.getName() + "' leaked into '" + m2.getName() + "'"));
        });
    }

    // -----------------------------------------------------------------------
    // Methods
    // -----------------------------------------------------------------------

    @Test
    void classDeclaredMethodsAreVisibleInsideBody() {
        forEachNode(MethodDeclaration.class, (cu, method) -> {
            if (method.getBody() == null) return;
            TypeDeclaration type = enclosingType(method);
            if (type == null) return;

            Set<String> declared = Arrays.stream(type.getMethods())
                    .map(m -> m.getName().getIdentifier())
                    .collect(Collectors.toSet());

            ASTNode firstNode = firstNodeInside(method);
            if (firstNode == null) return;

            Set<String> visible = VariableScopeVisitor.getAvailableMethods(firstNode).stream()
                    .map(IMethodBinding::getName).collect(Collectors.toSet());

            declared.forEach(name -> assertTrue(visible.contains(name),
                    "Method '" + name + "' not visible inside '" + method.getName() + "'"));
        });
    }

    @Test
    void allOverloadsAreVisible() {
        forEachNode(TypeDeclaration.class, (cu, type) -> {
            Map<String, Long> counts = Arrays.stream(type.getMethods())
                    .collect(Collectors.groupingBy(m -> m.getName().getIdentifier(), Collectors.counting()));

            Set<String> overloaded = counts.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (overloaded.isEmpty() || type.getMethods().length == 0) return;

            ASTNode firstNode = firstNodeInside(type.getMethods()[0]);
            if (firstNode == null) return;

            Set<String> visible = VariableScopeVisitor.getAvailableMethods(firstNode).stream()
                    .map(IMethodBinding::getName).collect(Collectors.toSet());

            overloaded.forEach(name -> assertTrue(visible.contains(name),
                    "Overloaded method '" + name + "' not visible in '" + type.getName() + "'"));
        });
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    @Test
    void topLevelTypeIsVisibleWithinItself() {
        forEachNode(TypeDeclaration.class, (cu, type) -> {
            ASTNode firstNode = firstNodeInside(type);
            if (firstNode == null) return;

            Set<String> visible = VariableScopeVisitor.getAvailableTypes(firstNode).stream()
                    .map(ITypeBinding::getName).collect(Collectors.toSet());

            assertTrue(visible.contains(type.getName().getIdentifier()),
                    "Type '" + type.getName() + "' not visible within itself");
        });
    }

    @Test
    void importedTypesAreVisible() {
        parsedUnits.forEach((path, cu) -> {
            Set<String> imported = ((List<?>) cu.imports()).stream()
                    .map(i -> (ImportDeclaration) i)
                    .filter(i -> !i.isStatic() && !i.isOnDemand())
                    .map(i -> {
                        String fqn = i.getName().getFullyQualifiedName();
                        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    })
                    .collect(Collectors.toSet());
            if (imported.isEmpty()) return;

            AbstractTypeDeclaration type = (AbstractTypeDeclaration) cu.types().get(0);
            MethodDeclaration[] methods = switch (type) {
                case TypeDeclaration td -> td.getMethods();
                case RecordDeclaration rd -> rd.getMethods();
                default -> new MethodDeclaration[0];
            };
            if (methods.length == 0) return;

            ASTNode firstNode = firstNodeInside(methods[0]);
            if (firstNode == null) return;

            Set<String> visible = VariableScopeVisitor.getAvailableTypes(firstNode).stream()
                    .map(ITypeBinding::getName).collect(Collectors.toSet());

            long matched = imported.stream().filter(visible::contains).count();
            assertTrue(matched > 0, "No imported types visible — imports: " + imported);
        });
    }

    // -----------------------------------------------------------------------
    // Immutability
    // -----------------------------------------------------------------------

    @Test
    void resultMapsAreImmutable() {
        CompilationUnit cu = parsedUnits.values().iterator().next();
        VariableScopeVisitor.ScopeResult r = VariableScopeVisitor.analyze(cu);
        assertThrows(UnsupportedOperationException.class, () -> r.variables().put(null, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> r.methods().put(null, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> r.types().put(null, List.of()));
    }

    @Test
    void scopeSnapshotsAreImmutable() {
        CompilationUnit cu = parsedUnits.values().iterator().next();
        VariableScopeVisitor.ScopeResult r = VariableScopeVisitor.analyze(cu);
        r.variables().values().stream().findFirst().ifPresent(s ->
                assertThrows(UnsupportedOperationException.class, () -> s.add(null)));
        r.methods().values().stream().findFirst().ifPresent(s ->
                assertThrows(UnsupportedOperationException.class, () -> s.add(null)));
    }

    // -----------------------------------------------------------------------
    // Single-node API consistency with full analysis
    // -----------------------------------------------------------------------

    @Test
    void singleNodeVariablesMatchFullAnalysis() {
        CompilationUnit cu = parsedUnits.values().iterator().next();
        VariableScopeVisitor.ScopeResult full = VariableScopeVisitor.analyze(cu);
        ASTNode node = full.variables().keySet().iterator().next();

        List<String> fromFull   = full.variables().get(node).stream().map(IVariableBinding::getName).sorted().toList();
        List<String> fromSingle = VariableScopeVisitor.getAvailableVariables(node).stream().map(IVariableBinding::getName).sorted().toList();
        assertEquals(fromFull, fromSingle);
    }

    @Test
    void singleNodeMethodsMatchFullAnalysis() {
        CompilationUnit cu = parsedUnits.values().iterator().next();
        VariableScopeVisitor.ScopeResult full = VariableScopeVisitor.analyze(cu);
        ASTNode node = full.methods().keySet().iterator().next();

        List<String> fromFull   = full.methods().get(node).stream().map(IMethodBinding::getName).sorted().toList();
        List<String> fromSingle = VariableScopeVisitor.getAvailableMethods(node).stream().map(IMethodBinding::getName).sorted().toList();
        assertEquals(fromFull, fromSingle);
    }

    @Test
    void singleNodeTypesMatchFullAnalysis() {
        CompilationUnit cu = parsedUnits.values().iterator().next();
        VariableScopeVisitor.ScopeResult full = VariableScopeVisitor.analyze(cu);
        ASTNode node = full.types().keySet().iterator().next();

        List<String> fromFull   = full.types().get(node).stream().map(ITypeBinding::getName).sorted().toList();
        List<String> fromSingle = VariableScopeVisitor.getAvailableTypes(node).stream().map(ITypeBinding::getName).sorted().toList();
        assertEquals(fromFull, fromSingle);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @FunctionalInterface
    interface NodeConsumer<T extends ASTNode> {
        void accept(CompilationUnit cu, T node);
    }

    @SuppressWarnings("unchecked")
    private <T extends ASTNode> void forEachNode(Class<T> type, NodeConsumer<T> consumer) {
        parsedUnits.forEach((path, cu) -> cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (type.isInstance(node)) consumer.accept(cu, (T) node);
            }
        }));
    }

    private ASTNode firstNodeInside(ASTNode parent) {
        VariableScopeVisitor.ScopeResult full = VariableScopeVisitor.analyze(
                (CompilationUnit) parent.getRoot());
        for (ASTNode node : full.variables().keySet()) {
            if (isDescendantOf(node, parent)) return node;
        }
        for (ASTNode node : full.methods().keySet()) {
            if (isDescendantOf(node, parent)) return node;
        }
        return null;
    }

    private static boolean isDescendantOf(ASTNode node, ASTNode ancestor) {
        ASTNode current = node;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static TypeDeclaration enclosingType(ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null) {
            if (current instanceof TypeDeclaration td) return td;
            current = current.getParent();
        }
        return null;
    }

    private static Set<String> localsOf(MethodDeclaration method) {
        Set<String> locals = new HashSet<>();
        method.accept(new ASTVisitor() {
            @Override
            public void endVisit(VariableDeclarationFragment node) {
                locals.add(node.getName().getIdentifier());
            }
        });
        return locals;
    }
}
