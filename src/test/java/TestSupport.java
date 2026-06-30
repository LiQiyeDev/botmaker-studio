import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class TestSupport {

    static final Path SOURCE_ROOT = Paths.get("src", "main", "java").toAbsolutePath();

    private TestSupport() {}

    public static List<String> findJavaFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().toString())
                    .collect(Collectors.toList());
        }
    }

    public static List<String> runtimeClassPath() {
        return Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    /** Parses all .java files under {@code SOURCE_ROOT} and asserts basic sanity. */
    public static Map<String, CompilationUnit> parseProjectSources() throws IOException {
        List<String> files = findJavaFiles(SOURCE_ROOT);
        assertFalse(files.isEmpty(), "No .java files found under " + SOURCE_ROOT);

        Map<String, CompilationUnit> units = createCompilationUnits(
                runtimeClassPath(), files, SOURCE_ROOT);

        assertNotNull(units, "Parsed units map should not be null");
        return units;
    }

    /** Parses many .java files into CompilationUnits with full binding resolution. */
    public static Map<String, CompilationUnit> createCompilationUnits(
            List<String> classPaths, List<String> javaFilePaths, Path sourceRootPath) {

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);

        String[] cpArray = classPaths.toArray(new String[0]);
        String[] sourceRootsArray = { sourceRootPath.toAbsolutePath().toString() };
        String[] rootEncodingsArray = { "UTF-8" };
        parser.setEnvironment(cpArray, sourceRootsArray, rootEncodingsArray, true);

        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);

        String[] filesToParseArray = javaFilePaths.toArray(new String[0]);
        String[] fileEncodingsArray = new String[filesToParseArray.length];
        Arrays.fill(fileEncodingsArray, "UTF-8");

        Map<String, CompilationUnit> parsedUnits = new HashMap<>();
        FileASTRequestor requestor = new FileASTRequestor() {
            @Override
            public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                parsedUnits.put(sourceFilePath, ast);
            }
        };
        parser.createASTs(filesToParseArray, fileEncodingsArray, new String[0], requestor, null);
        return parsedUnits;
    }
}
