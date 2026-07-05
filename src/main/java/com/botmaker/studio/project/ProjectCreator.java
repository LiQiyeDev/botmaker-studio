package com.botmaker.studio.project;

import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Scaffolds a new user project as a standard Maven project.
 * The {@code pom.xml} is generated programmatically via {@link MavenService#writePom}
 * (Maven Model API), not from a build-file string.
 */
public class ProjectCreator {

    public void createProject(String projectName) throws IOException {
        createProject(projectName, "");
    }

    /**
     * Creates a new project, pinning the BotMaker SDK to {@code sdkVersion}
     * (blank → {@link MavenService#SDK_FALLBACK_VERSION}).
     */
    public void createProject(String projectName, String sdkVersion) throws IOException {
        validateProjectName(projectName);

        ProjectConfig cfg = ProjectConfig.forProject(projectName, PROJECTS_ROOT);
        Path projectPath = cfg.projectPath();

        if (Files.exists(projectPath.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Project '" + projectName + "' already exists");
        }

        System.out.println("------------------------------------------------");
        System.out.println("Creating Project: " + projectName);
        System.out.println("Location: " + projectPath);
        System.out.println("------------------------------------------------");

        try {
            // 1. Standard Maven directory layout
            Files.createDirectories(projectPath.resolve("src/main/java"));
            Files.createDirectories(projectPath.resolve("src/main/resources"));
            Files.createDirectories(projectPath.resolve("src/test/java"));
            Files.createDirectories(projectPath.resolve("src/test/resources"));

            // 2. pom.xml via the Maven Model API
            System.out.println("1. Generating pom.xml...");
            MavenService.writePom(projectPath, cfg, sdkVersion);

            // 3. Package + main class
            System.out.println("2. Creating source files...");
            Path srcPath = projectPath.resolve("src/main/java/com/" + cfg.packageName());
            Files.createDirectories(srcPath);
            createMainJavaFile(srcPath, projectName, cfg.packageName());

            System.out.println("------------------------------------------------");
            System.out.println("SUCCESS: Project created at " + projectPath);
            System.out.println("------------------------------------------------");
        } catch (Exception e) {
            System.err.println("!!! ERROR during project creation !!!");
            e.printStackTrace();
            throw new IOException("Failed to create project: " + e.getMessage(), e);
        }
    }

    private void createMainJavaFile(Path srcPath, String projectName, String packageName) throws IOException {
        String content = String.format("""
            package com.%s;
            import com.botmaker.sdk.api.BotMaker;

            public class %s {
                public static void main(String[] args) {
                    BotMaker.print("Hello from %s!");
                }
            }
            """, packageName, projectName, projectName);

        Files.writeString(srcPath.resolve(projectName + ".java"), content);
    }

    public boolean projectExists(String projectName) {
        Path projectPath = PROJECTS_ROOT.resolve(projectName);
        return Files.exists(projectPath.resolve("pom.xml"));
    }

    private void validateProjectName(String projectName) {
        if (projectName == null || projectName.trim().isEmpty() || !projectName.matches("^[A-Z][a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid project name: " + projectName);
        }
    }
}
