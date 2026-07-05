import com.botmaker.studio.suggestions.ProjectAnalyzer;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import com.botmaker.studio.util.VariableScopeVisitor;
import org.eclipse.jdt.core.dom.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Interactive scope + member explorer. Run with:
 *
 *   -DscopeFile=MyClass    target a specific file (simple class name, no .java)
 *   -DscopeLine=42         target a specific line number
 *
 * Without arguments a random node inside a method body with local variables is chosen.
 * The test always passes — its value is in the printed report.
 *
 * Members marked [Bound] come from live JDT bindings (project types).
 * Members marked [Idx]   come from the TypeSummary library index (external jars).
 * In this test no jar index is loaded, so external types show (no public methods found).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NodeScopeExplorerTest {

    private Map<String, CompilationUnit> parsedUnits;

    @BeforeAll
    void setup() throws IOException {
        parsedUnits = TestSupport.parseProjectSources();
    }

    @Test
    void exploreScopeAtNode() {
        // ── 1. Pick target compilation unit ──────────────────────────────────
        String fileFilter = System.getProperty("scopeFile", "").trim();
        Map.Entry<String, CompilationUnit> entry = null;

        if (!fileFilter.isEmpty()) {
            String target = fileFilter.endsWith(".java") ? fileFilter : fileFilter + ".java";
            entry = parsedUnits.entrySet().stream()
                    .filter(e -> Path.of(e.getKey()).getFileName().toString().equals(target))
                    .findFirst().orElse(null);
            if (entry == null) {
                System.out.println("File not found: " + target
                        + ". Available: " + parsedUnits.keySet().stream()
                        .map(p -> Path.of(p).getFileName().toString())
                        .sorted().collect(Collectors.joining(", ")));
                return;
            }
        } else {
            List<Map.Entry<String, CompilationUnit>> all = new ArrayList<>(parsedUnits.entrySet());
            entry = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }

        String filePath = entry.getKey();
        CompilationUnit cu = entry.getValue();

        // ── 2. Full scope analysis ────────────────────────────────────────────
        VariableScopeVisitor.ScopeResult full = VariableScopeVisitor.analyze(cu);

        // ── 3. Pick target node ───────────────────────────────────────────────
        String lineArg = System.getProperty("scopeLine", "").trim();
        ASTNode targetNode;

        if (!lineArg.isEmpty()) {
            int targetLine = Integer.parseInt(lineArg);
            targetNode = findClosestTrackedNode(cu, full, targetLine);
            if (targetNode == null) {
                System.out.println("No tracked node near line " + targetLine
                        + " in " + Path.of(filePath).getFileName()
                        + ". Try a line inside a method body.");
                return;
            }
        } else {
            targetNode = findInterestingRandomNode(full);
            if (targetNode == null) {
                System.out.println("No interesting node found (need a method with local variables).");
                return;
            }
        }

        assertNotNull(targetNode);

        // ── 4. Scope at this node ─────────────────────────────────────────────
        List<IVariableBinding> localVars    = VariableScopeVisitor.getAvailableVariables(targetNode);
        List<IMethodBinding>   localMethods = VariableScopeVisitor.getAvailableMethods(targetNode);
        List<ITypeBinding>     localTypes   = VariableScopeVisitor.getAvailableTypes(targetNode);

        // ── 5. Global pool (union across all nodes in this file) ─────────────
        Set<String> globalVarNames    = full.variables().values().stream().flatMap(Collection::stream).map(IVariableBinding::getName).collect(Collectors.toSet());
        Set<String> globalMethodNames = full.methods().values().stream().flatMap(Collection::stream).map(IMethodBinding::getName).collect(Collectors.toSet());
        Set<String> globalTypeNames   = full.types().values().stream().flatMap(Collection::stream).map(ITypeBinding::getName).collect(Collectors.toSet());

        // ── 6. Diff ───────────────────────────────────────────────────────────
        Set<String> localVarNames    = localVars.stream().map(IVariableBinding::getName).collect(Collectors.toSet());
        Set<String> localMethodNames = localMethods.stream().map(IMethodBinding::getName).collect(Collectors.toSet());
        Set<String> localTypeNames   = localTypes.stream().map(ITypeBinding::getName).collect(Collectors.toSet());

        List<String> hiddenVars    = globalVarNames.stream().filter(n -> !localVarNames.contains(n)).sorted().toList();
        List<String> hiddenMethods = globalMethodNames.stream().filter(n -> !localMethodNames.contains(n)).sorted().toList();
        List<String> hiddenTypes   = globalTypeNames.stream().filter(n -> !localTypeNames.contains(n)).sorted().toList();

        // ── 7. Resolve members via ProjectAnalyzer ────────────────────────────
        // No jar index loaded in this test — external types fall back to (none).
        // Load a TypeSummaryManager and pass it to get library members too.
        ProjectAnalyzer analyzer = new ProjectAnalyzer(null, null);
        VariableScopeVisitor.NodeScope nodeScope =
                new VariableScopeVisitor.NodeScope(localVars, localMethods, localTypes);
        ProjectAnalyzer.ScopeMembers members = analyzer.resolveScope(nodeScope);

        // ── 8. Print report ───────────────────────────────────────────────────
        int line = cu.getLineNumber(targetNode.getStartPosition());
        String ruler = "═".repeat(65);

        System.out.println("\n" + ruler);
        System.out.println("  NODE SCOPE EXPLORER");
        System.out.printf ("  File : %s%n", Path.of(filePath).getFileName());
        System.out.printf ("  Line : %d%n", line);
        System.out.printf ("  Node : %s  →  %s%n",
                targetNode.getClass().getSimpleName(), summarize(targetNode));
        System.out.printf ("  Hint : -DscopeFile=%s -DscopeLine=%d%n",
                Path.of(filePath).getFileName().toString().replace(".java", ""), line);
        System.out.println(ruler);

        printSection("VARIABLES visible here (" + localVars.size() + ")",
                localVars.stream()
                        .map(v -> v.getType().getName() + "  " + v.getName()
                                + (v.isField() ? "  [field]" : ""))
                        .sorted().toList());

        printSection("METHODS visible here (" + localMethods.size() + ")",
                localMethods.stream()
                        .map(m -> m.getReturnType().getName() + "  " + m.getName()
                                + "(" + paramSummary(m) + ")"
                                + (isStaticMethod(m) ? "  [static]" : ""))
                        .sorted().toList());

        printSection("TYPES visible here (" + localTypes.size() + ")",
                localTypes.stream().map(ITypeBinding::getName).sorted().toList());

        System.out.println("\n  ─── NOT visible here (exist elsewhere in this file) ───");
        printSection("Hidden variables (" + hiddenVars.size() + ")", hiddenVars);
        printSection("Hidden methods ("  + hiddenMethods.size() + ")", hiddenMethods);
        printSection("Hidden types ("    + hiddenTypes.size() + ")", hiddenTypes);

        // ── 9. Per-variable member detail ─────────────────────────────────────
        if (!members.methods().isEmpty()) {
            System.out.println("\n  ─── CALLABLE MEMBERS per variable ───");
            members.methods().forEach((var, resolvedMethods) -> {
                String typeName = var.getType() != null ? var.getType().getName() : "?";
                System.out.printf("%n  [ %s  %s ]%n", typeName, var.getName());

                if (resolvedMethods.isEmpty()) {
                    System.out.println("    (no public methods found)");
                } else {
                    resolvedMethods.stream().limit(12).forEach(rm ->
                            System.out.println("    " + tag(rm) + "  " + formatMethod(rm)));
                    if (resolvedMethods.size() > 12) {
                        System.out.println("    ... +" + (resolvedMethods.size() - 12) + " more");
                    }
                }

                List<ProjectAnalyzer.ResolvedField> varFields =
                        members.fields().getOrDefault(var, List.of());
                if (!varFields.isEmpty()) {
                    System.out.println("    ─ fields ─");
                    varFields.stream().limit(5).forEach(rf ->
                            System.out.println("    " + tag(rf) + "  " + formatField(rf)));
                    if (varFields.size() > 5) {
                        System.out.println("    ... +" + (varFields.size() - 5) + " more");
                    }
                }
            });
        }

        System.out.println("\n" + ruler + "\n");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ASTNode findClosestTrackedNode(CompilationUnit cu,
                                                  VariableScopeVisitor.ScopeResult result,
                                                  int targetLine) {
        return Stream.of(result.variables().keySet(), result.methods().keySet(), result.types().keySet())
                .flatMap(Set::stream)
                .min(Comparator.comparingInt(n ->
                        Math.abs(cu.getLineNumber(n.getStartPosition()) - targetLine)))
                .orElse(null);
    }

    private static ASTNode findInterestingRandomNode(VariableScopeVisitor.ScopeResult result) {
        List<ASTNode> candidates = result.variables().entrySet().stream()
                .filter(e -> enclosingMethod(e.getKey()) != null)
                .filter(e -> e.getValue().stream().anyMatch(v -> !v.isField()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            candidates = result.variables().keySet().stream()
                    .filter(n -> enclosingMethod(n) != null)
                    .collect(Collectors.toList());
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        ASTNode cur = node.getParent();
        while (cur != null) {
            if (cur instanceof MethodDeclaration md) return md;
            cur = cur.getParent();
        }
        return null;
    }

    private static String summarize(ASTNode node) {
        String s = node.toString().trim().replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static String paramSummary(IMethodBinding m) {
        return Arrays.stream(m.getParameterTypes())
                .map(ITypeBinding::getName)
                .collect(Collectors.joining(", "));
    }

    private static boolean isStaticMethod(IMethodBinding m) {
        return (m.getModifiers() & 0x8) != 0;
    }

    private static String tag(ProjectAnalyzer.ResolvedMethod rm) {
        return rm instanceof ProjectAnalyzer.ResolvedMethod.Bound ? "[Bound]" : "[Idx]  ";
    }

    private static String tag(ProjectAnalyzer.ResolvedField rf) {
        return rf instanceof ProjectAnalyzer.ResolvedField.Bound ? "[Bound]" : "[Idx]  ";
    }

    private static String formatMethod(ProjectAnalyzer.ResolvedMethod rm) {
        return switch (rm) {
            case ProjectAnalyzer.ResolvedMethod.Bound b -> {
                IMethodBinding mb = b.binding();
                String ret    = mb.getReturnType() != null ? mb.getReturnType().getName() : "?";
                String params = Arrays.stream(mb.getParameterTypes())
                        .map(ITypeBinding::getName).collect(Collectors.joining(", "));
                yield ret + "  " + mb.getName() + "(" + params + ")"
                        + ((mb.getModifiers() & 0x8) != 0 ? "  [static]" : "");
            }
            case ProjectAnalyzer.ResolvedMethod.FromIndex f -> {
                MethodInfo mi = f.info();
                String ret = mi.getTypeSignatureOrTypeDescriptor().getResultType().toString();
                String params = Arrays.stream(mi.getParameterInfo())
                        .map(p -> p.getTypeSignatureOrTypeDescriptor().toString())
                        .collect(Collectors.joining(", "));
                yield ret + "  " + mi.getName() + "(" + params + ")"
                        + (mi.isStatic() ? "  [static]" : "");
            }
        };
    }

    private static String formatField(ProjectAnalyzer.ResolvedField rf) {
        return switch (rf) {
            case ProjectAnalyzer.ResolvedField.Bound b -> {
                IVariableBinding vb = b.binding();
                yield vb.getType().getName() + "  " + vb.getName()
                        + ((vb.getModifiers() & 0x8) != 0 ? "  [static]" : "");
            }
            case ProjectAnalyzer.ResolvedField.FromIndex f -> {
                FieldInfo fi = f.info();
                yield fi.getTypeSignatureOrTypeDescriptor().toString() + "  " + fi.getName()
                        + (fi.isStatic() ? "  [static]" : "");
            }
        };
    }

    private static void printSection(String header, List<String> items) {
        System.out.println("\n  [ " + header + " ]");
        if (items.isEmpty()) {
            System.out.println("    (none)");
        } else {
            items.forEach(item -> System.out.println("    " + item));
        }
    }
}
