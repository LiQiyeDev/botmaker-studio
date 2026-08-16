package com.botmaker.studio.sharing;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.TemplateManifest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Import/export of image templates on their own — a {@code .bmtemplates} zip — so a set of templates can move
 * between projects without carrying a whole bot with it.
 *
 * <p>Distinct from {@link ProjectArchive}, which collects a <em>project</em> to publish. This is the smaller
 * unit: the PNGs, their resolution sidecars (the SDK reads those to rescale a template at a different
 * resolution — an export without them would import templates that mis-scale) and a slice of the tag manifest
 * covering exactly the exported names.
 *
 * <p>Layout inside the zip mirrors the images root, one level down, so the file is legible in any zip tool:
 * <pre>
 *   images/accept.png
 *   images/accept.json
 *   templates.json
 * </pre>
 *
 * <p>Import never overwrites: a name already taken in the destination is imported under a suffixed name and
 * reported. Templates are referenced from generated source by path, so silently replacing one would change
 * what an existing bot matches against — the safe failure is a duplicate the user can delete, not a
 * substitution they never see.
 */
public final class TemplateArchive {

    private TemplateArchive() {}

    /** The extension of a template-only archive, used by both file choosers. */
    public static final String EXTENSION = ".bmtemplates";

    private static final String IMAGES_PREFIX = "images/";

    /** What an import did, for the status line — one line per outcome the user would want to hear about. */
    public record ImportResult(List<String> imported, Map<String, String> renamed, List<String> skipped) {

        public int count() {
            return imported.size();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder("Imported " + imported.size() + " template(s)");
            if (!renamed.isEmpty()) {
                sb.append("; renamed to avoid collisions: ");
                renamed.forEach((from, to) -> sb.append(from).append("→").append(to).append(" "));
            }
            if (!skipped.isEmpty()) sb.append("; skipped ").append(skipped.size()).append(" unreadable entry(ies)");
            return sb.toString().trim();
        }
    }

    /**
     * Writes {@code templates} (PNG paths from the project's images root) plus their sidecars and tags to
     * {@code destination}.
     */
    public static void export(ProjectConfig config, Collection<Path> templates, Path destination)
            throws IOException {
        List<String> names = templates.stream().map(ImageTemplateLibrary::baseName).toList();
        TemplateManifest slice = ImageTemplateLibrary.manifest(config).restrictedTo(names);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Path png : templates) {
                writeEntry(zip, IMAGES_PREFIX + png.getFileName(), Files.readAllBytes(png));
                Path sidecar = ImageTemplateLibrary.sidecarFor(png);
                if (Files.isRegularFile(sidecar)) {
                    writeEntry(zip, IMAGES_PREFIX + sidecar.getFileName(), Files.readAllBytes(sidecar));
                }
            }
            // The manifest is written through the same writer the project uses, via a scratch directory, so
            // an exported manifest and a project one can never be two different shapes.
            Path scratch = Files.createTempDirectory("bmtemplates");
            try {
                slice.write(scratch);
                Path manifest = scratch.resolve(TemplateManifest.FILE_NAME);
                if (Files.isRegularFile(manifest)) {
                    writeEntry(zip, TemplateManifest.FILE_NAME, Files.readAllBytes(manifest));
                }
            } finally {
                deleteRecursively(scratch);
            }
        }
    }

    /**
     * Unpacks {@code archive} into the project's images root, renaming around existing names and merging the
     * imported tags into the project's manifest.
     */
    public static ImportResult importInto(ProjectConfig config, Path archive) throws IOException {
        Map<String, byte[]> entries = readEntries(archive);

        TemplateManifest incoming = TemplateManifest.empty();
        byte[] manifestBytes = entries.remove(TemplateManifest.FILE_NAME);
        if (manifestBytes != null) {
            Path scratch = Files.createTempDirectory("bmtemplates");
            try {
                Files.write(scratch.resolve(TemplateManifest.FILE_NAME), manifestBytes);
                incoming = TemplateManifest.read(scratch);
            } finally {
                deleteRecursively(scratch);
            }
        }

        Path imagesRoot = config.imagesRoot();
        Files.createDirectories(imagesRoot);
        List<String> imported = new ArrayList<>();
        Map<String, String> renamed = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        TemplateManifest merged = ImageTemplateLibrary.manifest(config);

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String fileName = entry.getKey();
            if (!fileName.toLowerCase().endsWith(".png")) continue; // sidecars are pulled in with their PNG
            String source = fileName.substring(0, fileName.length() - ".png".length());
            String target = freeName(config, source);
            if (target == null) {
                skipped.add(source);
                continue;
            }
            Files.write(imagesRoot.resolve(target + ".png"), entry.getValue());
            byte[] sidecar = entries.get(source + ".json");
            if (sidecar != null) Files.write(imagesRoot.resolve(target + ".json"), sidecar);

            merged = merged.withTags(target, incoming.tagsOf(source));
            imported.add(target);
            if (!target.equals(source)) renamed.put(source, target);
        }

        ImageTemplateLibrary.saveManifest(config, merged);
        return new ImportResult(imported, renamed, skipped);
    }

    /**
     * {@code base} if free, else {@code base_2}, {@code base_3}… The bound exists because the alternative to
     * giving up is an unbounded loop on a pathological project; 99 collisions on one name is a user problem,
     * not a case to keep spinning on.
     */
    private static String freeName(ProjectConfig config, String base) {
        String sanitized = ImageTemplateLibrary.sanitizeName(base);
        if (sanitized.isBlank() || ImageTemplateLibrary.isReservedName(sanitized)) return null;
        if (!ImageTemplateLibrary.exists(config, sanitized)) return sanitized;
        for (int i = 2; i <= 99; i++) {
            String candidate = sanitized + "_" + i;
            if (!ImageTemplateLibrary.exists(config, candidate)) return candidate;
        }
        return null;
    }

    /** Every entry of {@code archive}, keyed by its file name with the {@code images/} prefix stripped. */
    private static Map<String, byte[]> readEntries(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith(IMAGES_PREFIX)) name = name.substring(IMAGES_PREFIX.length());
                // Only a bare file name is ever honoured — an entry naming a directory (or "..") is how a
                // zip writes outside the root it is unpacked into, and nothing this archive contains needs one.
                if (name.isEmpty() || name.contains("/")) continue;
                entries.put(name, zis.readAllBytes());
            }
        }
        return entries;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a temp file we could not remove is not worth failing an export over
                }
            });
        } catch (IOException ignored) {
            // ditto
        }
    }
}
