package com.botmaker.studio.project;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Immutable configuration for a single project.
 */
public record ProjectConfig(
        String projectName,
        String packageName,
        Path projectPath,
        Path sourceRoot,
        Path mainSourceFile,
        Path compiledOutputPath,
        String mainClassName,
        String javaHome,
        String javaExecutable,
        String javacExecutable
) {

    public static ProjectConfig forProject(String projectName, Path projectsRoot) {
        String javaHome = System.getProperty("java.home");
        String packageName = projectName.toLowerCase();
        Path projectPath = projectsRoot.resolve(projectName);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String javaBin = isWindows ? "java.exe" : "java";
        String javacBin = isWindows ? "javac.exe" : "javac";

        return new ProjectConfig(
                projectName,
                packageName,
                projectPath,
                projectPath.resolve("src").resolve("main").resolve("java"),
                projectPath.resolve("src").resolve("main").resolve("java")
                        .resolve("com").resolve(packageName).resolve(projectName + ".java"),
                // Maven standard output directory
                projectPath.resolve("target").resolve("classes"),
                "com." + packageName + "." + projectName,
                javaHome,
                Paths.get(javaHome, "bin", javaBin).toString(),
                Paths.get(javaHome, "bin", javacBin).toString()
        );
    }

    /** {@code src/main/resources} — where {@code activities.json} and image templates live. */
    public Path resourcesRoot() {
        return projectPath.resolve("src").resolve("main").resolve("resources");
    }

    /** {@code src/main/resources/images} — the saved image-template directory. */
    public Path imagesRoot() {
        return resourcesRoot().resolve("images");
    }

    /** The generated {@code Activities.java} sidecar (sibling of the main class). */
    public Path activitiesSourceFile() {
        return sourceRoot.resolve("com").resolve(packageName).resolve("Activities.java");
    }

    /** The generated {@code ActivityRegistry.java} sidecar (sibling of the main class). */
    public Path activityRegistrySourceFile() {
        return sourceRoot.resolve("com").resolve(packageName).resolve("ActivityRegistry.java");
    }

    /** {@code src/main/java/com/<pkg>/activities} — where per-activity subclass stubs live. */
    public Path activitiesPackageDir() {
        return sourceRoot.resolve("com").resolve(packageName).resolve("activities");
    }
}