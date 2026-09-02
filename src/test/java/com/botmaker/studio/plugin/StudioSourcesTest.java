package com.botmaker.studio.plugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Studio source names a {@code com.botmaker.plugin.toolkit} type.
 *
 * <p><b>The rule is not aesthetic.</b> The toolkit is a <em>plugin's</em> dependency, resolved child-first
 * onto the plugin's own classloader precisely so two plugins may hold two versions. The moment Studio's own
 * code depends on a toolkit type, Studio has a version of it that must be kept in step with every plugin's,
 * and the widget kit is the module that was split out of the contract because it will version fast.
 *
 * <p><b>Why this is still a test after the dependency went.</b> From 2026-08-28 to 2026-09-02 Studio
 * declared the toolkit at {@code runtime} scope, so javac could not see it and this test existed to catch
 * somebody widening that scope to {@code compile} to make an import work. On 2026-09-02 the dependency was
 * removed outright with the bundled SDK plugin that was its only reason — a plugin brings its own toolkit at
 * {@code compile} scope, which is what {@code botmaker-plugin-archetype} generates and what
 * {@code botmaker-cli}'s {@code pom-scopes} check enforces by refusing a {@code provided} one. So the
 * failure mode this guards has changed shape rather than gone: the way to break the rule now is to add the
 * dependency back, and an import that compiles is exactly the evidence somebody did. Scanning the source
 * catches it in this module; a classpath assertion would not, because the offending build would have the
 * class on its classpath by construction.
 */
class StudioSourcesTest {

    private static final String FORBIDDEN = "com.botmaker.plugin.toolkit";

    @Test
    void studio_never_imports_the_toolkit() throws IOException {
        Path sources = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(FORBIDDEN)) {
                    offenders.add(sources.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Studio source names " + FORBIDDEN + " in " + offenders + ". The toolkit is on Studio's"
                        + " runtime classpath for its bundled plugin's sake only; Studio's own code must not"
                        + " use it. Write the widget in Studio, or put the code in the plugin that needs it.");
    }
}
