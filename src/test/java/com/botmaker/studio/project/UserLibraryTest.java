package com.botmaker.studio.project;

import com.botmaker.studio.TestSupport;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers {@code TestSupport}'s multi-file parse harness on both the kinds of source tree Studio reads: its
 * own, and a library's.
 *
 * <p><b>Studio ui MISSING/SU18.</b> The second test used to be {@code @Disabled("Update sdkSourceRootPath to
 * your local BotMaker-sdk checkout before running")} — a path constant pointing at
 * {@code ~/path/to/BotMaker-sdk}, which had never resolved on any machine. A test that needs hand-editing is
 * not disabled, it is deleted or parameterised; this one is parameterised, because the assertion is real and
 * the path is knowable. The SDK is a sibling submodule of the same umbrella repo, so its source root is
 * {@code ../botmaker-sdk/src/main/java} from here, overridable with {@code -Dsdk.source.root=…} and skipped
 * with a message when this is a standalone Studio clone with no sibling checked out.
 */
public class UserLibraryTest {

    /** Where the SDK's sources are, given this repo is a submodule of the umbrella. */
    private static final Path SIBLING_SDK_SOURCES =
            Paths.get("..", "botmaker-sdk", "src", "main", "java").toAbsolutePath().normalize();

    private static Path sdkSourceRoot() {
        String override = System.getProperty("sdk.source.root");
        return override == null || override.isBlank()
                ? SIBLING_SDK_SOURCES
                : Paths.get(override).toAbsolutePath().normalize();
    }

    @Test
    void parsesAllProjectSourceFiles() throws IOException {
        Map<String, CompilationUnit> units = TestSupport.parseProjectSources();
        List<String> files = TestSupport.findJavaFiles(TestSupport.SOURCE_ROOT);
        assertEquals(files.size(), units.size(),
                "Parsed AST count should match the number of source files");
    }

    /**
     * The same harness against a source tree outside this project — which is what {@code ProjectAnalyzer}
     * does for every library a user adds. Parsing a foreign tree is where the source-root argument actually
     * matters: get it wrong and every unit comes back without bindings.
     */
    @Test
    void parsesExternalSdkLibrarySources() throws IOException {
        Path sdkSources = sdkSourceRoot();
        assumeTrue(Files.isDirectory(sdkSources),
                "no botmaker-sdk sources at " + sdkSources + " — this is a standalone Studio clone; "
                        + "check the sibling submodule out, or pass -Dsdk.source.root=<path>");

        List<String> files = TestSupport.findJavaFiles(sdkSources);
        assertFalse(files.isEmpty(), "no .java files under " + sdkSources);

        Map<String, CompilationUnit> units =
                TestSupport.createCompilationUnits(TestSupport.runtimeClassPath(), files, sdkSources);

        assertNotNull(units);
        assertEquals(files.size(), units.size(), "every source file must yield a CompilationUnit");

        assertTrue(units.values().stream()
                        .filter(cu -> cu.getPackage() != null)
                        .anyMatch(cu -> cu.getPackage().getName().getFullyQualifiedName()
                                .startsWith("com.botmaker.sdk")),
                "at least one unit must be in the SDK's own package — otherwise the parse resolved "
                        + "something else entirely");
    }

    /** The record is the whole of a user library: three coordinates, no separate store file. */
    @Test
    void aUserLibraryIsItsMavenCoordinate() {
        UserLibrary lib = new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "1.0.7");

        assertEquals("com.github.LiQiyeDev", lib.groupId());
        assertEquals("botmaker-sdk", lib.artifactId());
        assertEquals("1.0.7", lib.version());
        assertEquals(lib, new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "1.0.7"),
                "value identity — the pom is the source of truth and these are compared by content");
    }
}
