import com.botmaker.index.TypeSummaryManager;
import com.botmaker.util.ClassPathManager;
import io.github.classgraph.ClassInfo;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ASTUtilsTest {

    // -----------------------------------------------------------------------
    // Project source parsing
    // -----------------------------------------------------------------------

    @Test
    void parsesAllProjectSourceFiles() throws IOException {
        Map<String, CompilationUnit> units = TestSupport.parseProjectSources();
        List<String> files = TestSupport.findJavaFiles(TestSupport.SOURCE_ROOT);

        assertEquals(files.size(), units.size(),
                "Parsed AST count should match the number of source files");

        for (String path : files) {
            CompilationUnit cu = units.get(path);
            assertNotNull(cu, "Missing CompilationUnit for " + path);
            assertNotNull(cu.getAST(), "AST should be initialized for " + path);
        }
    }

    // -----------------------------------------------------------------------
    // Jar-based tests — require a local project path; skip in CI
    // -----------------------------------------------------------------------

    @Disabled("Requires local project path — set projectPath before running")
    @Test
    void typeSummaryIndexStats() throws IOException {
        Path projectPath = Paths.get("C:\\Users\\bgroi\\Documents\\dev\\IntellijProjects\\BotMaker-Studio");
        List<String> jars = ClassPathManager.resolveJarPaths(projectPath).subList(0, 5);
        TypeSummaryManager manager = TypeSummaryManager.load(jars);

        System.out.println("Total types: " + manager.totalTypes());
        System.out.println("Total enums: " + manager.findEnums().size());

        jars.stream().filter(jar -> !ClassPathManager.isSystemJar(jar)).forEach(jar -> {
            List<ClassInfo> types = manager.getTypesForJar(jar);
            Path cache = TypeSummaryManager.getCacheFileForJar(jar);
            System.out.println(Path.of(jar).getFileName()
                    + " → " + types.size() + " types | cache: " + (Files.exists(cache) ? "OK" : "MISS"));
        });
    }

    @Disabled("Requires local project path — set projectPath before running")
    @Test
    void typeSummaryLookup() throws IOException {
        Path projectPath = Paths.get("C:\\Users\\bgroi\\Documents\\dev\\IntellijProjects\\BotMaker-Studio");
        List<String> jars = ClassPathManager.resolveJarPaths(projectPath).subList(0, 5);
        TypeSummaryManager manager = TypeSummaryManager.load(jars);

        jars.stream()
                .filter(jar -> !ClassPathManager.isSystemJar(jar))
                .flatMap(jar -> manager.getTypesForJar(jar).stream())
                .findFirst()
                .ifPresent(first -> {
                    Optional<ClassInfo> bySimple = manager.findBySimpleName(first.getSimpleName());
                    Optional<ClassInfo> byQualified = manager.findByQualifiedName(first.getName());
                    assertTrue(bySimple.isPresent(), "findBySimpleName failed for " + first.getSimpleName());
                    assertTrue(byQualified.isPresent(), "findByQualifiedName failed for " + first.getName());
                });
    }

    @Disabled("Requires local project path — set projectPath before running")
    @Test
    void typeSummaryCacheRoundTrip() throws IOException {
        Path projectPath = Paths.get("C:\\Users\\bgroi\\Documents\\dev\\IntellijProjects\\BotMaker-Studio");
        List<String> jars = ClassPathManager.resolveJarPaths(projectPath).subList(0, 5);
        TypeSummaryManager manager = TypeSummaryManager.load(jars);

        String firstNonSystem = jars.stream()
                .filter(jar -> !ClassPathManager.isSystemJar(jar))
                .findFirst().orElse(null);

        if (firstNonSystem == null) return;

        int before = manager.getTypesForJar(firstNonSystem).size();
        TypeSummaryManager.invalidateJar(firstNonSystem);
        assertFalse(Files.exists(TypeSummaryManager.getCacheFileForJar(firstNonSystem)));

        TypeSummaryManager reloaded = TypeSummaryManager.load(List.of(firstNonSystem));
        assertEquals(before, reloaded.getTypesForJar(firstNonSystem).size(),
                "Type count must survive invalidate + re-index");

        TypeSummaryManager full = TypeSummaryManager.load(jars);
        assertEquals(manager.totalTypes(), full.totalTypes(),
                "Total type count must be stable across full reload");
    }
}
