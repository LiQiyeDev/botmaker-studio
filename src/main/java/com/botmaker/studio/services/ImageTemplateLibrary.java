package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The resolution metadata written alongside every captured template as a {@code <name>.json} sidecar.
     * {@code width}/{@code height} are the template's own pixel size; {@code captureWidth}/{@code
     * captureHeight} are the resolution (physical pixels) of the capture source (window/screen) the template
     * was authored against — the SDK reads these to rescale the template when the target runs at a different
     * resolution (see {@code ImageTemplate.captureResolution()}). {@code captureWidth}/{@code captureHeight}
     * of {@code 0} mean "unknown" (the SDK then falls back to the project-wide default resolution).
     */
    public record TemplateMetadata(int width, int height, int captureWidth, int captureHeight,
                                   String target, String createdAt) {}

    /** File name of the built-in default template shipped in every new project (see {@code ProjectCreator}). */
    public static final String DEFAULT_TEMPLATE_FILE = "default_template.png";

    /**
     * Project-root-relative path a fresh {@code new ImageTemplate(...)} references so a newly-dropped vision
     * block compiles immediately against a real (if placeholder) template rather than a missing file.
     */
    public static final String DEFAULT_TEMPLATE_PATH = "src/main/resources/images/" + DEFAULT_TEMPLATE_FILE;

    /** True when {@code file} is the project's built-in default template (protected from rename/delete). */
    public static boolean isDefaultTemplate(Path file) {
        return file != null && file.getFileName().toString().equalsIgnoreCase(DEFAULT_TEMPLATE_FILE);
    }

    /**
     * Normalizes a user-entered template name to the allowed character set: trims surrounding whitespace and
     * replaces every character outside {@code [A-Za-z0-9_-]} with {@code _}. The result may still be blank
     * (when the input was blank or all-whitespace) — callers must reject blanks and check {@link #exists}
     * for uniqueness. Shared by every naming path (the single-capture prompt and the batch dialog).
     */
    public static String sanitizeName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

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

    /** The metadata sidecar ({@code <name>.json}) that lives next to a template PNG. */
    public static Path sidecarFor(Path templateFile) {
        return templateFile.resolveSibling(baseName(templateFile) + ".json");
    }

    /**
     * Whether a template PNG named {@code baseName} already exists (case-insensitive) — used to block
     * duplicate names so a new capture never silently overwrites an existing template.
     */
    public static boolean exists(ProjectConfig config, String baseName) {
        if (baseName == null || baseName.isBlank()) return false;
        String wanted = (baseName + ".png").toLowerCase(Locale.ROOT);
        Path dir = config.imagesRoot();
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Saves {@code img} as {@code <imagesRoot>/<baseName>.png} plus a {@code <baseName>.json} resolution
     * sidecar, and returns the template's project-root-relative path (the string for
     * {@code new ImageTemplate("…")}). {@code captureWidth}/{@code captureHeight} are the capture source's
     * physical resolution (pass {@code 0} when unknown); {@code targetTitle} may be {@code null}.
     * {@code baseName} must already be sanitized. Does not check for an existing file — callers gate that
     * via {@link #exists}.
     */
    public static String saveTemplate(ProjectConfig config, BufferedImage img, String baseName,
                                      int captureWidth, int captureHeight, String targetTitle) throws IOException {
        Path png = config.imagesRoot().resolve(baseName + ".png");
        Files.createDirectories(png.getParent());
        ImageIO.write(img, "png", png.toFile());
        TemplateMetadata meta = new TemplateMetadata(img.getWidth(), img.getHeight(),
                Math.max(0, captureWidth), Math.max(0, captureHeight), targetTitle, Instant.now().toString());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sidecarFor(png).toFile(), meta);
        return pathFor(config, png);
    }
}
