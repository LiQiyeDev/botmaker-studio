package com.botmaker.studio.services;

import com.botmaker.sdk.authoring.TagCatalog;
import com.botmaker.sdk.authoring.TemplateLibrary;
import com.botmaker.sdk.authoring.TemplateManifest;
import com.botmaker.studio.authoring.TemplateNames;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The project's pictures, as Studio asks for them — a {@link ProjectConfig} away from
 * {@link TemplateLibrary}, which is where they actually live since 2026-08-30.
 *
 * <p><b>Why the folder moved and this did not.</b> A <em>named picture</em> is {@code ImageTemplate}'s own
 * concept, so the plugin that offers the type owns the folder; two readers of one folder is exactly the
 * drift the capture-target work spent a phase deleting. What stays here is the translation every Studio
 * caller was already doing implicitly: a {@code ProjectConfig} is Studio's, a resources directory is what
 * the contract hands a plugin, and the second is one field of the first.
 *
 * <p>It is a façade with no state and no rules of its own — <b>put nothing here that decides anything</b>.
 * A rule added here is a rule the plugin's own pickers do not have, which is the shape of the bug this move
 * exists to prevent. The one method with a body is {@link #openActivityTag}, and it is here precisely
 * because its question is about the editor rather than about the folder.
 */
public final class ImageTemplateLibrary {

    private ImageTemplateLibrary() {}

    /** File name of the built-in default template shipped in every new project. */
    public static final String DEFAULT_TEMPLATE_FILE = TemplateNames.DEFAULT_TEMPLATE_FILE;

    /** Project-root-relative path a fresh {@code new ImageTemplate(...)} references. */
    public static final String DEFAULT_TEMPLATE_PATH = TemplateLibrary.pathForName(
            TemplateNames.DEFAULT_TEMPLATE_NAME);

    /** The default template's base name — what a value that names a template by name is seeded with. */
    public static final String DEFAULT_TEMPLATE_NAME = TemplateNames.DEFAULT_TEMPLATE_NAME;

    // ── questions about a file, which need no project ───────────────────────────────────────────────────

    public static boolean isDefaultTemplate(Path file) {
        return TemplateLibrary.isDefaultTemplate(file);
    }

    public static BufferedImage defaultTemplateImage() {
        return TemplateLibrary.defaultTemplateImage();
    }

    public static void writePlaceholderAt(Path target) throws IOException {
        TemplateLibrary.writePlaceholderAt(target);
    }

    public static boolean isUnmodifiedDefaultTemplate(Path file) {
        return TemplateLibrary.isUnmodifiedDefaultTemplate(file);
    }

    public static String sanitizeName(String raw) {
        return TemplateLibrary.sanitizeName(raw);
    }

    public static boolean isReservedName(String baseName) {
        return TemplateLibrary.isReservedName(baseName);
    }

    public static String baseName(Path templateFile) {
        return TemplateLibrary.baseName(templateFile);
    }

    public static Path sidecarFor(Path templateFile) {
        return TemplateLibrary.sidecarFor(templateFile);
    }

    public static String pictureHash(BufferedImage img) {
        return TemplateLibrary.pictureHash(img);
    }

    public static String pictureHash(Path file) {
        return TemplateLibrary.pictureHash(file);
    }

    public static boolean sameContent(Path file, byte[] incoming) {
        return TemplateLibrary.sameContent(file, incoming);
    }

    public static void replaceImage(ProjectConfig config, Path templateFile, BufferedImage img,
                                    int captureWidth, int captureHeight, String targetTitle) throws IOException {
        TemplateLibrary.replaceImage(templateFile, img, captureWidth, captureHeight, targetTitle);
    }

    // ── questions about a project's folder ──────────────────────────────────────────────────────────────

    public static List<Path> list(ProjectConfig config) {
        return TemplateLibrary.list(config.resourcesRoot());
    }

    public static String pathFor(ProjectConfig config, Path templateFile) {
        return TemplateLibrary.pathFor(templateFile);
    }

    public static String pathForName(ProjectConfig config, String baseName) {
        return TemplateLibrary.pathForName(baseName);
    }

    public static Path fileForName(ProjectConfig config, String baseName) {
        return TemplateLibrary.fileForName(config.resourcesRoot(), baseName);
    }

    public static boolean exists(ProjectConfig config, String baseName) {
        return TemplateLibrary.exists(config.resourcesRoot(), baseName);
    }

    public static String saveTemplate(ProjectConfig config, BufferedImage img, String baseName,
                                      int captureWidth, int captureHeight, String targetTitle) throws IOException {
        return TemplateLibrary.saveTemplate(config.resourcesRoot(), img, baseName,
                captureWidth, captureHeight, targetTitle);
    }

    public static TemplateManifest manifest(ProjectConfig config) {
        return TemplateLibrary.manifest(config.resourcesRoot());
    }

    public static void saveManifest(ProjectConfig config, TemplateManifest manifest) {
        TemplateLibrary.saveManifest(config.resourcesRoot(), manifest);
    }

    public static TagCatalog tagCatalog(ProjectConfig config) {
        return TemplateLibrary.tagCatalog(config.resourcesRoot());
    }

    public static Map<String, List<Path>> listByTag(ProjectConfig config) {
        return TemplateLibrary.listByTag(config.resourcesRoot());
    }

    public static void applyTags(ProjectConfig config, Map<String, ? extends Collection<String>> tagsByTemplate) {
        TemplateLibrary.applyTags(config.resourcesRoot(), tagsByTemplate);
    }

    public static void addTag(ProjectConfig config, Collection<String> baseNames, String tag) {
        TemplateLibrary.addTag(config.resourcesRoot(), baseNames, tag);
    }

    public static void removeTag(ProjectConfig config, Collection<String> baseNames, String tag) {
        TemplateLibrary.removeTag(config.resourcesRoot(), baseNames, tag);
    }

    public static TagCatalog declareTag(ProjectConfig config, String tag) {
        return TemplateLibrary.declareTag(config.resourcesRoot(), tag);
    }

    public static void renameTemplate(ProjectConfig config, Path file, String newBaseName) throws IOException {
        TemplateLibrary.renameTemplate(config.resourcesRoot(), file, newBaseName);
    }

    public static void deleteTemplate(ProjectConfig config, Path file) throws IOException {
        TemplateLibrary.deleteTemplate(config.resourcesRoot(), file);
    }

    public static Map<String, List<String>> duplicatePictures(ProjectConfig config) {
        return TemplateLibrary.duplicatePictures(config.resourcesRoot());
    }

    public static List<String> missingTemplates(ProjectConfig config) {
        return TemplateLibrary.missingTemplates(config.resourcesRoot());
    }

    // ── the one question the folder cannot answer ───────────────────────────────────────────────────────

    /**
     * The tag to preselect for a capture started while an activity is open — that activity's tag. Capturing
     * from inside an activity is the common case, and that activity is the grouping the user would otherwise
     * pick by hand; a template later used by a second activity simply gains its tag too.
     *
     * <p>This did not move with the rest because <em>which file is open in the editor</em> is host state and
     * nothing but the host can answer it. The half that could travel is
     * {@link TemplateLibrary#declaredTag}, which is what turns a file name into a tag the project actually
     * declares — so an open file that is no longer a declared activity offers nothing rather than conjuring
     * a tag out of a file name. Returns {@code null} when there is no such tag, which every caller treats as
     * "no suggestion".
     */
    public static String openActivityTag(ProjectConfig config, ProjectState state) {
        if (state == null || state.getActiveFile() == null) return null;
        Path file = state.getActiveFile().getPath();
        if (file == null || file.getParent() == null) return null;
        if (!file.getParent().equals(config.activitiesPackageDir())) return null;
        String name = file.getFileName().toString();
        if (!name.endsWith(".java")) return null;
        return TemplateLibrary.declaredTag(config.resourcesRoot(),
                name.substring(0, name.length() - ".java".length()));
    }
}
