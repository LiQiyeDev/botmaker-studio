package com.botmaker.services;

import com.botmaker.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the project's saved image templates (PNG files under {@code src/main/resources/images}) and maps
 * between a template file and the path string an SDK {@code ImageTemplate(String)} expects.
 *
 * <p>The SDK loads templates from the filesystem relative to the working directory (which is the project
 * root at run time), so the path embedded in code is relative to the project root, e.g.
 * {@code "src/main/resources/images/accept_button.png"} — always forward-slashed for cross-platform use.
 */
public final class ImageTemplateLibrary {

    private ImageTemplateLibrary() {}

    /** All saved template PNGs, sorted by file name. */
    public static List<Path> list(ProjectConfig config) {
        Path dir = config.imagesRoot();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            System.err.println("Failed to list image templates: " + e.getMessage());
            return List.of();
        }
    }

    /** The project-root-relative, forward-slashed path string for {@code templateFile}. */
    public static String pathFor(ProjectConfig config, Path templateFile) {
        return config.projectPath().relativize(templateFile.toAbsolutePath())
                .toString().replace('\\', '/');
    }

    /** The path string for a template named {@code baseName} (without extension). */
    public static String pathForName(ProjectConfig config, String baseName) {
        return pathFor(config, config.imagesRoot().resolve(baseName + ".png"));
    }

    /** Base name (no extension) of a template file, used as its display label. */
    public static String baseName(Path templateFile) {
        String name = templateFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
