package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.TemplateConstants;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    /** The default template's base name — what a value that names a template by name is seeded with. */
    public static final String DEFAULT_TEMPLATE_NAME =
            DEFAULT_TEMPLATE_FILE.substring(0, DEFAULT_TEMPLATE_FILE.lastIndexOf('.'));

    /** True when {@code file} is the project's built-in default template (protected from rename/delete). */
    public static boolean isDefaultTemplate(Path file) {
        return file != null && file.getFileName().toString().equalsIgnoreCase(DEFAULT_TEMPLATE_FILE);
    }

    /**
     * Normalizes a user-entered template name to the allowed character set: trims surrounding whitespace,
     * replaces every character outside {@code [A-Za-z0-9_]} with {@code _}, and lowercases the result. The
     * result may still be blank (when the input was blank or all-whitespace) — callers must reject blanks and
     * check {@link #exists} for uniqueness. Shared by every naming path (the single-capture prompt and the
     * batch dialog).
     *
     * <p><b>Lowercase, and no {@code -}, because the name is also a Java constant.</b> Every template is
     * declared in the generated {@code Templates} class as {@code YTUJ = "…/ytuj.png"}, and Studio reads that
     * constant back to know which file a block refers to. Restricting the name to a lowercase identifier makes
     * the two exactly reversible — see {@link com.botmaker.studio.project.TemplateConstants}. Names captured
     * before this rule keep working; they simply get no constant.
     */
    public static String sanitizeName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    /**
     * Rewrites the generated {@code Templates.java} from what is on disk now. Best-effort: a project whose
     * source tree isn't writable still captures templates, it just doesn't get constants for them.
     *
     * <p>Called after every add, rename and delete rather than on a timer or at project open, because the
     * class is only correct relative to the folder — a stale constant is a use site that compiles and finds
     * nothing, which is the exact failure the constants exist to turn into a compile error.
     */
    public static void regenerateTemplatesClass(ProjectConfig config) {
        try {
            Path file = config.templatesSourceFile();
            Files.createDirectories(file.getParent());
            List<String> names = list(config).stream().map(ImageTemplateLibrary::baseName).toList();
            Files.writeString(file, TemplateConstants.generateSource(config.packageName(), names));
        } catch (IOException e) {
            System.err.println("Failed to regenerate " + TemplateConstants.CLASS_NAME + ": " + e.getMessage());
        }
    }

    /**
     * Whether {@code baseName} would collide with a file the library owns rather than a template — today the
     * one name whose {@code <name>.json} sidecar <em>is</em> {@link TemplateManifest#FILE_NAME}. Checked by
     * every naming path alongside {@link #exists}: saving a template called "templates" would otherwise
     * overwrite the tag manifest with a resolution sidecar.
     */
    public static boolean isReservedName(String baseName) {
        return baseName != null && TemplateManifest.FILE_NAME.equalsIgnoreCase(baseName + ".json");
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
        regenerateTemplatesClass(config);
        return pathFor(config, png);
    }

    /**
     * Overwrites an existing template's picture, keeping its name, its tags and every block that references
     * it. The resolution sidecar is rewritten too — a replacement is usually recaptured against a different
     * window size, and a stale {@code captureWidth} would have the SDK rescale the new picture by the old
     * picture's ratio.
     *
     * <p>Separate from {@link #saveTemplate} even though the file write is the same, because the two answer
     * different questions: saving refuses an existing name (that would be a silent overwrite), replacing
     * requires one.
     */
    public static void replaceImage(ProjectConfig config, Path templateFile, BufferedImage img,
                                    int captureWidth, int captureHeight, String targetTitle) throws IOException {
        Files.createDirectories(templateFile.getParent());
        ImageIO.write(img, "png", templateFile.toFile());
        TemplateMetadata meta = new TemplateMetadata(img.getWidth(), img.getHeight(),
                Math.max(0, captureWidth), Math.max(0, captureHeight), targetTitle, Instant.now().toString());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(sidecarFor(templateFile).toFile(), meta);
    }

    // ── Tags ────────────────────────────────────────────────────────────────────────────────────────────
    //
    // The manifest is read from disk on every call rather than cached. Templates are captured by an overlay,
    // a batch dialog and the resource manager, and imported by an archive — a cache here would be a fifth
    // thing to invalidate, for a file of a few hundred bytes read only when a menu opens.

    /** The project's tag manifest, or an empty one when it has none. */
    public static TemplateManifest manifest(ProjectConfig config) {
        return TemplateManifest.read(config.imagesRoot());
    }

    /** Persists {@code manifest} for the project; best-effort — a failure loses tags, never templates. */
    public static void saveManifest(ProjectConfig config, TemplateManifest manifest) {
        try {
            manifest.write(config.imagesRoot());
        } catch (IOException e) {
            System.err.println("Failed to save template tags: " + e.getMessage());
        }
    }

    /**
     * The project's declared tags — one per activity, plus the custom ones from the manifest. Read from disk
     * for the same reason the manifest is (see above): the activities are edited by a dialog, a canvas and a
     * repair pass, and a cache here would be another of them to invalidate.
     */
    public static TagCatalog tagCatalog(ProjectConfig config) {
        return TagCatalog.of(ActivitiesConfig.read(config.resourcesRoot()), manifest(config).customTags());
    }

    /**
     * The saved templates grouped as {@code tag → files} over the declared set: {@link TemplateManifest#ALL}
     * first, then every declared tag (empty ones included — a tag exists because it was declared, not because
     * something carries it), then {@link TemplateManifest#UNTAGGED}. A template carrying two tags appears
     * under both; there is only ever one file.
     */
    public static Map<String, List<Path>> listByTag(ProjectConfig config) {
        List<Path> files = list(config);
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path file : files) byName.put(baseName(file), file);

        Map<String, List<Path>> grouped = new LinkedHashMap<>();
        manifest(config).byTag(byName.keySet(), tagCatalog(config)).forEach((tag, names) ->
                grouped.put(tag, names.stream().map(byName::get).filter(Objects::nonNull).toList()));
        return grouped;
    }

    /**
     * Files each template under the tags chosen for it, dropping any that the project doesn't declare, and
     * saves once. The map is {@code base name → tags}; a name mapped to an empty list is left untagged.
     *
     * <p>One call rather than one per template because the manifest is a single file: saving inside a loop
     * would rewrite it once per capture and, if the last write lost, silently drop the earlier ones.
     */
    public static void applyTags(ProjectConfig config, Map<String, ? extends Collection<String>> tagsByTemplate) {
        if (tagsByTemplate.isEmpty()) return;
        TagCatalog catalog = tagCatalog(config);
        TemplateManifest updated = manifest(config);
        for (Map.Entry<String, ? extends Collection<String>> entry : tagsByTemplate.entrySet()) {
            updated = updated.withTags(entry.getKey(), catalog.declaredOnly(entry.getValue()));
        }
        saveManifest(config, updated);
    }

    /**
     * Adds one declared {@code tag} to every named template and saves once — "add these to this tag", the
     * operation a tag view wants, as opposed to {@link #applyTags} which <em>replaces</em> a template's whole
     * tag set. An undeclared tag is refused rather than declared as a side effect: declaring is
     * {@link #declareTag}'s job, and a typo should not become a group.
     */
    public static void addTag(ProjectConfig config, Collection<String> baseNames, String tag) {
        if (baseNames.isEmpty() || !tagCatalog(config).isDeclared(tag)) return;
        saveManifest(config, manifest(config).tagged(baseNames, tag));
    }

    /** Takes {@code tag} off every named template and saves once. The tag itself survives, empty. */
    public static void removeTag(ProjectConfig config, Collection<String> baseNames, String tag) {
        if (baseNames.isEmpty()) return;
        saveManifest(config, manifest(config).untagged(baseNames, tag));
    }

    /** Declares {@code tag} as a custom tag and saves; returns the catalog it now belongs to. */
    public static TagCatalog declareTag(ProjectConfig config, String tag) {
        saveManifest(config, manifest(config).declaring(tag));
        return tagCatalog(config);
    }

    /**
     * Renames a template: the PNG, its resolution sidecar and its tags, in that order. Single entry point
     * because the three used to be moved by the caller and the tags weren't moved at all — the manifest is
     * keyed by base name, so a rename that skips it silently untags the template.
     */
    public static void renameTemplate(ProjectConfig config, Path file, String newBaseName) throws IOException {
        Path target = config.imagesRoot().resolve(newBaseName + ".png");
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        Path oldSidecar = sidecarFor(file);
        if (Files.exists(oldSidecar)) {
            Files.move(oldSidecar, sidecarFor(target), StandardCopyOption.REPLACE_EXISTING);
        }
        saveManifest(config, manifest(config).renamed(baseName(file), newBaseName));
        regenerateTemplatesClass(config);
    }

    /** Deletes a template: the PNG, its resolution sidecar and its manifest entry. */
    public static void deleteTemplate(ProjectConfig config, Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(sidecarFor(file));
        saveManifest(config, manifest(config).without(baseName(file)));
        regenerateTemplatesClass(config);
    }

    /**
     * The tag to preselect for a capture started while an activity is open — that activity's tag. Capturing
     * from inside an activity is the common case, and that activity is the grouping the user would otherwise
     * pick by hand; a template later used by a second activity simply gains its tag too.
     *
     * <p>It <em>selects</em> a declared tag, it does not create one: the returned name is checked against
     * {@link #tagCatalog}, so an open file that is no longer a declared activity offers nothing rather than
     * conjuring a tag out of a file name. Returns {@code null} when there is no such tag, which every caller
     * treats as "no suggestion".
     */
    public static String openActivityTag(ProjectConfig config, ProjectState state) {
        if (state == null || state.getActiveFile() == null) return null;
        Path file = state.getActiveFile().getPath();
        if (file == null || file.getParent() == null) return null;
        if (!file.getParent().equals(config.activitiesPackageDir())) return null;
        String name = file.getFileName().toString();
        if (!name.endsWith(".java")) return null;
        TagCatalog.Tag tag = tagCatalog(config).find(name.substring(0, name.length() - ".java".length()));
        return tag == null ? null : tag.name();
    }
}
