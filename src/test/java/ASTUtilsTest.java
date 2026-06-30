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


}
