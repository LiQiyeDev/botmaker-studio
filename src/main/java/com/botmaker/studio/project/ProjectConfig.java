package com.botmaker.studio.project;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Immutable configuration for a single project.
 *
 * <p>Three names, derived once here and never re-derived by callers:
 * <ul>
 *   <li>{@code projectName} — <b>what the user typed</b>. Names the project directory and the Maven artifactId,
 *       and is what the project list shows. It is theirs, so it is kept verbatim.</li>
 *   <li>{@code packageName} — {@code projectName} lowercased, because package names are lowercase.</li>
 *   <li>{@code className} — {@code projectName} with its first letter capitalized, because Java classes are
 *       capitalized. This used to be {@code projectName} itself, which is why the New Project dialog had to
 *       refuse a lowercase first letter: the name doubled as a class name, so {@code myBot} would have
 *       produced {@code class myBot}. Deriving the class name instead lets the user call their project
 *       whatever they like.</li>
 * </ul>
 */
public record ProjectConfig(
        String projectName,
        String packageName,
        String className,
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
        String className = toClassName(projectName);
        Path projectPath = projectsRoot.resolve(projectName);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String javaBin = isWindows ? "java.exe" : "java";
        String javacBin = isWindows ? "javac.exe" : "javac";

        return new ProjectConfig(
                projectName,
                packageName,
                className,
                projectPath,
                projectPath.resolve("src").resolve("main").resolve("java"),
                projectPath.resolve("src").resolve("main").resolve("java")
                        .resolve("com").resolve(packageName).resolve(className + ".java"),
                // Maven standard output directory
                projectPath.resolve("target").resolve("classes"),
                "com." + packageName + "." + className,
                javaHome,
                Paths.get(javaHome, "bin", javaBin).toString(),
                Paths.get(javaHome, "bin", javacBin).toString()
        );
    }

    /** {@code projectName} as a Java class name: the same word, capitalized. */
    public static String toClassName(String projectName) {
        if (projectName == null || projectName.isEmpty()) return projectName;
        return Character.toUpperCase(projectName.charAt(0)) + projectName.substring(1);
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

    /** The generated {@code FlowDriver.java} sidecar — the state machine over the drawn Activity Flow. */
    public Path flowDriverSourceFile() {
        return sourceRoot.resolve("com").resolve(packageName).resolve("FlowDriver.java");
    }

    /** {@code src/main/java/com/<pkg>/activities} — where per-activity subclass stubs live. */
    public Path activitiesPackageDir() {
        return sourceRoot.resolve("com").resolve(packageName).resolve("activities");
    }
}