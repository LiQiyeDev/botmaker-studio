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
 * <p>Studio carries the toolkit at <b>runtime</b> scope, so its bundled SDK plugin can instantiate (see
 * {@link PluginHostLoadTest}). Runtime scope means javac never sees it and this rule cannot be broken by
 * accident — but it can be broken by someone widening the scope to {@code compile} to make an import work,
 * which is why the assertion is over the source rather than over the classpath.
 *
 * <p><b>The rule is not aesthetic.</b> The toolkit is a <em>plugin's</em> dependency, resolved child-first
 * onto the plugin's own classloader precisely so two plugins may hold two versions. Studio's copy is the
 * fallback for a plugin that brings none. The moment Studio's own code depends on a toolkit type, Studio has
 * a version of it that must be kept in step with every plugin's, and the widget kit is the module that was
 * split out of the contract because it will version fast.
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
