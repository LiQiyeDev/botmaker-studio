package com.botmaker.studio.util;

import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ClassPathManager {

    public static boolean isSystemJar(String jarPath) {
        String javaHome = System.getProperty("java.home");
        return jarPath.startsWith(javaHome);
    }

    /**
     * Returns all resolved jar paths for a Maven project (transitive dependencies included).
     */
    public static List<String> resolveJarPaths(Path projectPath) {
        return MavenService.resolveClasspath(projectPath);
    }

    /**
     * Finds all .java files recursively under a directory.
     */
    public static List<String> findJavaFiles(Path directory) {
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().toString())
                    .toList();
        } catch (IOException e) {
            System.err.println("Failed to walk directory: " + directory + " - " + e.getMessage());
            return List.of();
        }
    }
}
