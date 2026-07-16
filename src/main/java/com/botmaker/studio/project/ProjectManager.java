package com.botmaker.studio.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.botmaker.studio.config.Constants.ARCHIVE_ROOT;
import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Manages project discovery and listing
 */
public class ProjectManager {



    /**
     * Lists all available projects
     */
    public List<ProjectInfo> listProjects() {
        return listProjectsUnder(PROJECTS_ROOT);
    }

    /** Lists archived (soft-deleted) projects, in the same shape as {@link #listProjects()}. */
    public List<ProjectInfo> listArchivedProjects() {
        return listProjectsUnder(ARCHIVE_ROOT);
    }

    private List<ProjectInfo> listProjectsUnder(Path root) {
        List<ProjectInfo> projects = new ArrayList<>();

        if (!Files.exists(root)) {
            return projects;
        }

        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isDirectory)
                    .filter(this::isValidProject)
                    .forEach(projectPath -> {
                        try {
                            String projectName = projectPath.getFileName().toString();
                            FileTime lastModified = Files.getLastModifiedTime(projectPath);
                            LocalDateTime modifiedDate = LocalDateTime.ofInstant(
                                    lastModified.toInstant(),
                                    ZoneId.systemDefault()
                            );
                            projects.add(new ProjectInfo(projectName, projectPath, modifiedDate));
                        } catch (IOException e) {
                            System.err.println("Error reading project: " + projectPath);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Error listing projects: " + e.getMessage());
        }

        return projects;
    }

    /**
     * Checks if a directory is a valid project
     * (has src/main/java structure and pom.xml)
     */
    private boolean isValidProject(Path projectPath) {
        Path srcPath = projectPath.resolve("src/main/java");
        Path pom = projectPath.resolve("pom.xml");
        return Files.exists(srcPath) && Files.exists(pom);
    }

    /** Soft-deletes a project by moving it into the archive directory. */
    public void archiveProject(String name) throws IOException {
        Path source = PROJECTS_ROOT.resolve(name);
        if (!Files.exists(source)) {
            throw new IOException("Project '" + name + "' does not exist.");
        }
        Files.createDirectories(ARCHIVE_ROOT);
        Files.move(source, ARCHIVE_ROOT.resolve(name));
    }

    /** Restores an archived project back into the live projects directory. */
    public void restoreProject(String name) throws IOException {
        Path source = ARCHIVE_ROOT.resolve(name);
        if (!Files.exists(source)) {
            throw new IOException("Archived project '" + name + "' does not exist.");
        }
        Path dest = PROJECTS_ROOT.resolve(name);
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + name + "' already exists.");
        }
        Files.move(source, dest);
    }

    /**
     * Permanently deletes an archived project (recursively removes its directory under
     * {@link com.botmaker.studio.config.Constants#ARCHIVE_ROOT ARCHIVE_ROOT}). Only archived projects can be
     * hard-deleted; live projects must be archived first, keeping the destructive action one step removed.
     */
    public void deleteProject(String name) throws IOException {
        Path dir = ARCHIVE_ROOT.resolve(name);
        if (!Files.exists(dir)) {
            throw new IOException("Archived project '" + name + "' does not exist.");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Gets the entry-point source file path for a project.
     *
     * <p>Delegates to {@link ProjectConfig} rather than rebuilding the path: this used to derive the package
     * and file name itself, which meant the rule lived in two places and only one of them learned that a
     * project's class name is derived from its name rather than equal to it.
     */
    public Path getSourceFilePath(String projectName) {
        return ProjectConfig.forProject(projectName, PROJECTS_ROOT).mainSourceFile();
    }
}