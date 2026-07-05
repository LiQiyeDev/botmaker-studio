import com.botmaker.studio.project.UserLibrary;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class UserLibraryTest {

    @Test
    void parsesAllProjectSourceFiles() throws IOException {
        Map<String, CompilationUnit> units = TestSupport.parseProjectSources();
        List<String> files = TestSupport.findJavaFiles(TestSupport.SOURCE_ROOT);
        assertEquals(files.size(), units.size(),
                "Parsed AST count should match the number of source files");
    }

    @Disabled("Update sdkSourceRootPath to your local BotMaker-sdk checkout before running")
    @Test
    void parsesExternalSdkLibrarySources() throws IOException {
        UserLibrary sdkLib = new UserLibrary(
                "com.github.LiQiyeDev", "BotMaker-sdk", "0.1.1");
        String sdkPrimaryPackage = "com.botmaker.sdk";

        Path sdkSourceRootPath = Paths.get(System.getProperty("user.home"),
                "path", "to", "BotMaker-sdk", "src", "main", "java").toAbsolutePath();

        if (!Files.exists(sdkSourceRootPath)) {
            fail("SDK source root not found: " + sdkSourceRootPath
                    + " — update the path in this test");
        }

        List<String> files = TestSupport.findJavaFiles(sdkSourceRootPath);
        assertFalse(files.isEmpty(), "Should find at least one .java file in the SDK sources");

        Map<String, CompilationUnit> units = TestSupport.createCompilationUnits(
                TestSupport.runtimeClassPath(), files, sdkSourceRootPath);

        assertNotNull(units);
        assertEquals(files.size(), units.size());

        boolean foundPrimaryPackage = units.values().stream()
                .filter(cu -> cu.getPackage() != null)
                .anyMatch(cu -> cu.getPackage().getName().getFullyQualifiedName()
                        .startsWith(sdkPrimaryPackage));

        assertTrue(foundPrimaryPackage,
                "At least one CU should be in package " + sdkPrimaryPackage);
        System.out.println("Parsed " + units.size() + " files from " + sdkLib);
    }
}
