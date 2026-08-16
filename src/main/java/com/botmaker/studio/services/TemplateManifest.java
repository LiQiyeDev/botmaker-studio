package com.botmaker.studio.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which tags each image template carries — the whole of template "organisation", kept as metadata beside the
 * files rather than as directory structure.
 *
 * <p><b>Why not folders.</b> A template's identity is the path embedded in generated code
 * ({@code new ImageTemplate("src/main/resources/images/accept.png")}), so moving one into a folder rewrites
 * every bot source that references it. Worse, a template shared by two activities would have to live in one
 * folder or be duplicated into both — and duplicates drift. Tags are many-to-one by construction: the file
 * never moves, {@link ImageTemplateLibrary#pathForName} never changes, and a shared template simply carries
 * both tags. The UI renders the tags as a tree, so it still <em>reads</em> like folders.
 *
 * <p>Stored as {@code templates.json} at the images root, shaped {@code {"<name>": {"tags": [...]}}} — an
 * object per template rather than a bare array so a future per-template attribute doesn't need a migration.
 * The file is advisory: a project with no manifest, or a template missing from it, is simply untagged, and
 * a template listed here with no file on disk is ignored. That is what lets tags be added to an existing
 * project, and a hand-edited or half-written manifest degrade to "no tags" instead of to an error.
 *
 * <p>Immutable — every mutator returns a new manifest, and only {@link #write} touches the disk. Names are
 * matched case-insensitively, the same way {@link ImageTemplateLibrary#exists} treats template files, so the
 * manifest cannot disagree with the filesystem about whether two names are the same template.
 */
public record TemplateManifest(Map<String, SortedSet<String>> tagsByTemplate) {

    /** The manifest's file name, at the images root beside the PNGs. */
    public static final String FILE_NAME = "templates.json";

    /**
     * The bucket a template with no tags is shown under. Not stored — it is computed when listing, so
     * "untagged" can never go stale relative to the tags a template actually has.
     */
    public static final String UNTAGGED = "Untagged";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(List<String> tags) {}

    public TemplateManifest {
        Map<String, SortedSet<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (tagsByTemplate != null) {
            tagsByTemplate.forEach((name, tags) -> {
                if (name == null || name.isBlank() || tags == null) return;
                SortedSet<String> clean = newTagSet();
                tags.stream().map(TemplateManifest::sanitizeTag).filter(t -> !t.isBlank()).forEach(clean::add);
                if (!clean.isEmpty()) copy.put(name, clean);
            });
        }
        tagsByTemplate = Map.copyOf(copy);
    }

    public static TemplateManifest empty() {
        return new TemplateManifest(Map.of());
    }

    /** Tags sort case-insensitively so "Mining" and "mining" can't both appear in a tree. */
    private static SortedSet<String> newTagSet() {
        return new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Normalizes a user-entered tag: trims and collapses runs of whitespace. Unlike a template name (which
     * becomes a file name and so is restricted to {@code [A-Za-z0-9_-]}), a tag is only ever a label, so it
     * may contain spaces and punctuation. Blank means "no tag" — callers drop it.
     */
    public static String sanitizeTag(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * Reads the manifest at {@code imagesRoot}, or an empty one when it is absent or unreadable. Never
     * throws: tags are decoration, and a project whose manifest failed to parse must still open.
     */
    public static TemplateManifest read(Path imagesRoot) {
        Path file = imagesRoot.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return empty();
        try {
            Map<String, Entry> raw = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
            Map<String, SortedSet<String>> tags = new LinkedHashMap<>();
            raw.forEach((name, entry) -> {
                if (entry != null && entry.tags() != null) tags.put(name, new TreeSet<>(entry.tags()));
            });
            return new TemplateManifest(tags);
        } catch (IOException | RuntimeException e) {
            System.err.println("Ignoring unreadable " + FILE_NAME + ": " + e.getMessage());
            return empty();
        }
    }

    /** Writes the manifest to {@code imagesRoot}, deleting it when nothing is tagged. */
    public void write(Path imagesRoot) throws IOException {
        Path file = imagesRoot.resolve(FILE_NAME);
        if (tagsByTemplate.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(imagesRoot);
        Map<String, Entry> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tagsByTemplate.forEach((name, tags) -> out.put(name, new Entry(List.copyOf(tags))));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
    }

    /** The tags of {@code templateName} (base name, no extension) — empty when it has none. */
    public SortedSet<String> tagsOf(String templateName) {
        SortedSet<String> tags = tagsByTemplate.get(templateName);
        return tags == null ? newTagSet() : tags;
    }

    /** Every tag in use, sorted case-insensitively. */
    public SortedSet<String> allTags() {
        SortedSet<String> all = newTagSet();
        tagsByTemplate.values().forEach(all::addAll);
        return all;
    }

    /** This manifest with {@code templateName}'s tags replaced by {@code tags} (empty ⇒ untagged). */
    public TemplateManifest withTags(String templateName, Collection<String> tags) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(templateName);
        next.put(templateName, new TreeSet<>(tags));
        return new TemplateManifest(next);
    }

    /** This manifest with {@code tag} added to every name in {@code templateNames} — the bulk-tag operation. */
    public TemplateManifest tagged(Collection<String> templateNames, String tag) {
        String clean = sanitizeTag(tag);
        if (clean.isBlank()) return this;
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        for (String name : templateNames) {
            SortedSet<String> tags = newTagSet();
            tags.addAll(tagsOf(name));
            tags.add(clean);
            next.put(name, tags);
        }
        return new TemplateManifest(next);
    }

    /** This manifest with {@code from}'s tags carried over to {@code to} — kept in step with a file rename. */
    public TemplateManifest renamed(String from, String to) {
        SortedSet<String> tags = tagsByTemplate.get(from);
        if (tags == null) return this;
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(from);
        next.put(to, tags);
        return new TemplateManifest(next);
    }

    /** This manifest without {@code templateName} — kept in step with a delete. */
    public TemplateManifest without(String templateName) {
        if (!tagsByTemplate.containsKey(templateName)) return this;
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(templateName);
        return new TemplateManifest(next);
    }

    /** Only the entries for {@code templateNames} — the slice that travels in a {@code .bmtemplates} export. */
    public TemplateManifest restrictedTo(Collection<String> templateNames) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>();
        for (String name : templateNames) {
            SortedSet<String> tags = tagsByTemplate.get(name);
            if (tags != null) next.put(name, tags);
        }
        return new TemplateManifest(next);
    }

    /**
     * This manifest plus {@code other}'s — the import merge. Tags <em>union</em> per template rather than
     * replace: an imported template that already exists here keeps the tags this project gave it, so a
     * re-import can't quietly undo local organisation.
     */
    public TemplateManifest mergedWith(TemplateManifest other) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        other.tagsByTemplate().forEach((name, tags) -> {
            SortedSet<String> union = newTagSet();
            union.addAll(tagsOf(name));
            union.addAll(tags);
            next.put(name, union);
        });
        return new TemplateManifest(next);
    }

    /**
     * {@code tag → template names}, with every untagged name in {@code allTemplateNames} collected under
     * {@link #UNTAGGED}. Names not on disk are dropped, so a stale manifest entry never shows an empty row.
     */
    public Map<String, List<String>> byTag(Collection<String> allTemplateNames) {
        Map<String, List<String>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> untagged = new ArrayList<>();
        for (String name : allTemplateNames) {
            SortedSet<String> tags = tagsOf(name);
            if (tags.isEmpty()) {
                untagged.add(name);
            } else {
                for (String tag : tags) grouped.computeIfAbsent(tag, t -> new ArrayList<>()).add(name);
            }
        }
        if (!untagged.isEmpty()) grouped.put(UNTAGGED, untagged);
        return grouped;
    }
}
