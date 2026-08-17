package com.botmaker.studio.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>Stored as {@code templates.json} at the images root:
 * {@snippet lang=json :
 * { "templates": { "gold_ore": { "tags": ["Mining"] } }, "customTags": ["Shared"] }
 * }
 * An object per template rather than a bare array so a future per-template attribute doesn't need a
 * migration. A manifest written before {@code customTags} existed is a bare {@code {"<name>": {…}}} map;
 * {@link #read} recognises both, so an older project loads with its tags intact and is rewritten into the
 * current shape on the next save. (A template <em>called</em> "templates" cannot make the two shapes
 * ambiguous: {@link ImageTemplateLibrary#isReservedName} has always refused that name, because its
 * resolution sidecar would be this file.)
 *
 * <p><b>Assignments here, declarations elsewhere.</b> This file records which tags a template carries and
 * which <em>custom</em> tags the project has declared. It does not know about activity tags — those are
 * derived from {@code activities.json}, so they exist and vanish with the activity itself. {@link TagCatalog}
 * is what puts the two halves together, and it is the authority on which tags exist: an assignment naming a
 * tag the catalog doesn't declare is inert rather than an error, which is what makes renaming an activity
 * lossless. Rename it back and its templates are filed under it again.
 *
 * <p>Immutable — every mutator returns a new manifest, and only {@link #write} touches the disk. Names are
 * matched case-insensitively, the same way {@link ImageTemplateLibrary#exists} treats template files, so the
 * manifest cannot disagree with the filesystem about whether two names are the same template.
 */
public record TemplateManifest(Map<String, SortedSet<String>> tagsByTemplate, SortedSet<String> customTags) {

    /** The manifest's file name, at the images root beside the PNGs. */
    public static final String FILE_NAME = "templates.json";

    /**
     * The bucket a template with no tags is shown under. Not stored — it is computed when listing, so
     * "untagged" can never go stale relative to the tags a template actually has.
     */
    public static final String UNTAGGED = "Untagged";

    /**
     * The bucket holding every template regardless of its tags. Like {@link #UNTAGGED} it is computed, never
     * stored and never assignable: it exists so "show me everything" is a row in the same list as the tags
     * rather than a separate mode the two renderings would each have to implement.
     */
    public static final String ALL = "All";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The two computed buckets, which no real tag may shadow — checked wherever a tag is named. */
    public static boolean isSyntheticTag(String tag) {
        return ALL.equalsIgnoreCase(tag) || UNTAGGED.equalsIgnoreCase(tag);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(List<String> tags) {}

    public TemplateManifest {
        Map<String, SortedSet<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (tagsByTemplate != null) {
            tagsByTemplate.forEach((name, tags) -> {
                if (name == null || name.isBlank() || tags == null) return;
                SortedSet<String> clean = cleanTags(tags);
                if (!clean.isEmpty()) copy.put(name, clean);
            });
        }
        tagsByTemplate = Map.copyOf(copy);
        customTags = Collections.unmodifiableSortedSet(cleanTags(customTags == null ? Set.of() : customTags));
    }

    /** A manifest with assignments but no declared custom tags — the shape most callers build. */
    public TemplateManifest(Map<String, SortedSet<String>> tagsByTemplate) {
        this(tagsByTemplate, Collections.emptySortedSet());
    }

    private static SortedSet<String> cleanTags(Collection<String> raw) {
        SortedSet<String> clean = newTagSet();
        raw.stream().map(TemplateManifest::sanitizeTag)
                .filter(t -> !t.isBlank() && !isSyntheticTag(t))
                .forEach(clean::add);
        return clean;
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
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root == null || !root.isObject()) return empty();
            // The current shape declares its halves; the older one is the assignment map on its own.
            JsonNode assignments = root.has(TEMPLATES_KEY) ? root.get(TEMPLATES_KEY) : root;
            Map<String, SortedSet<String>> tags = new LinkedHashMap<>();
            if (assignments != null && assignments.isObject()) {
                Map<String, Entry> raw = MAPPER.convertValue(assignments, new TypeReference<>() {});
                raw.forEach((name, entry) -> {
                    if (entry != null && entry.tags() != null) tags.put(name, new TreeSet<>(entry.tags()));
                });
            }
            SortedSet<String> custom = newTagSet();
            JsonNode declared = root.get(CUSTOM_TAGS_KEY);
            if (declared != null && declared.isArray()) declared.forEach(n -> custom.add(n.asText("")));
            return new TemplateManifest(tags, custom);
        } catch (IOException | RuntimeException e) {
            System.err.println("Ignoring unreadable " + FILE_NAME + ": " + e.getMessage());
            return empty();
        }
    }

    private static final String TEMPLATES_KEY = "templates";
    private static final String CUSTOM_TAGS_KEY = "customTags";

    /**
     * Writes the manifest to {@code imagesRoot}, deleting it when there is neither an assignment nor a
     * declared tag left — a declared tag with no templates is worth a file of its own, since declaring it is
     * the act the user performed.
     */
    public void write(Path imagesRoot) throws IOException {
        Path file = imagesRoot.resolve(FILE_NAME);
        if (tagsByTemplate.isEmpty() && customTags.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(imagesRoot);
        Map<String, Entry> assignments = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tagsByTemplate.forEach((name, tags) -> assignments.put(name, new Entry(List.copyOf(tags))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(TEMPLATES_KEY, assignments);
        out.put(CUSTOM_TAGS_KEY, List.copyOf(customTags));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
    }

    /** The tags of {@code templateName} (base name, no extension) — empty when it has none. */
    public SortedSet<String> tagsOf(String templateName) {
        SortedSet<String> tags = tagsByTemplate.get(templateName);
        return tags == null ? newTagSet() : tags;
    }

    /** Every tag in use, sorted case-insensitively. Assignments only — see {@link #customTags()}. */
    public SortedSet<String> allTags() {
        SortedSet<String> all = newTagSet();
        tagsByTemplate.values().forEach(all::addAll);
        return all;
    }

    /** This manifest with {@code tag} declared as a custom tag, whether or not anything carries it yet. */
    public TemplateManifest declaring(String tag) {
        String clean = sanitizeTag(tag);
        if (clean.isBlank() || customTags.contains(clean)) return this;
        SortedSet<String> next = newTagSet();
        next.addAll(customTags);
        next.add(clean);
        return new TemplateManifest(tagsByTemplate, next);
    }

    /**
     * This manifest without the custom tag {@code tag} — undeclared, and stripped from every template that
     * carried it. Deleting a tag is the one operation that <em>does</em> touch assignments: leaving them
     * behind would make the tag come back the moment someone re-declared the same name.
     */
    public TemplateManifest undeclaring(String tag) {
        String clean = sanitizeTag(tag);
        SortedSet<String> nextCustom = newTagSet();
        customTags.stream().filter(t -> !t.equalsIgnoreCase(clean)).forEach(nextCustom::add);
        Map<String, SortedSet<String>> next = new LinkedHashMap<>();
        tagsByTemplate.forEach((name, tags) -> {
            SortedSet<String> kept = newTagSet();
            tags.stream().filter(t -> !t.equalsIgnoreCase(clean)).forEach(kept::add);
            next.put(name, kept);
        });
        return new TemplateManifest(next, nextCustom);
    }

    /** This manifest with the custom tag {@code from} renamed to {@code to}, assignments following it. */
    public TemplateManifest renamedTag(String from, String to) {
        String was = sanitizeTag(from);
        String now = sanitizeTag(to);
        if (was.isBlank() || now.isBlank() || was.equalsIgnoreCase(now)) return this;
        SortedSet<String> nextCustom = newTagSet();
        customTags.forEach(t -> nextCustom.add(t.equalsIgnoreCase(was) ? now : t));
        Map<String, SortedSet<String>> next = new LinkedHashMap<>();
        tagsByTemplate.forEach((name, tags) -> {
            SortedSet<String> moved = newTagSet();
            tags.forEach(t -> moved.add(t.equalsIgnoreCase(was) ? now : t));
            next.put(name, moved);
        });
        return new TemplateManifest(next, nextCustom);
    }

    /** This manifest with {@code templateName}'s tags replaced by {@code tags} (empty ⇒ untagged). */
    public TemplateManifest withTags(String templateName, Collection<String> tags) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(templateName);
        next.put(templateName, new TreeSet<>(tags));
        return new TemplateManifest(next, customTags);
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
        return new TemplateManifest(next, customTags);
    }

    /** This manifest with {@code from}'s tags carried over to {@code to} — kept in step with a file rename. */
    public TemplateManifest renamed(String from, String to) {
        SortedSet<String> tags = tagsByTemplate.get(from);
        if (tags == null) return this;
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(from);
        next.put(to, tags);
        return new TemplateManifest(next, customTags);
    }

    /** This manifest without {@code templateName} — kept in step with a delete. */
    public TemplateManifest without(String templateName) {
        if (!tagsByTemplate.containsKey(templateName)) return this;
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        next.remove(templateName);
        return new TemplateManifest(next, customTags);
    }

    /**
     * Only the entries for {@code templateNames} — the slice that travels in a {@code .bmtemplates} export,
     * plus the declarations for whichever custom tags that slice actually uses, so an import lands tags that
     * exist rather than assignments nothing declares.
     */
    public TemplateManifest restrictedTo(Collection<String> templateNames) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>();
        SortedSet<String> used = newTagSet();
        for (String name : templateNames) {
            SortedSet<String> tags = tagsByTemplate.get(name);
            if (tags == null) continue;
            next.put(name, tags);
            tags.stream().filter(customTags::contains).forEach(used::add);
        }
        return new TemplateManifest(next, used);
    }

    /**
     * This manifest plus {@code other}'s — the import merge. Tags <em>union</em> per template rather than
     * replace: an imported template that already exists here keeps the tags this project gave it, so a
     * re-import can't quietly undo local organisation. The incoming declarations join this project's, so a
     * tag that arrives with an imported template is one this project now has.
     */
    public TemplateManifest mergedWith(TemplateManifest other) {
        Map<String, SortedSet<String>> next = new LinkedHashMap<>(tagsByTemplate);
        other.tagsByTemplate().forEach((name, tags) -> {
            SortedSet<String> union = newTagSet();
            union.addAll(tagsOf(name));
            union.addAll(tags);
            next.put(name, union);
        });
        SortedSet<String> declared = newTagSet();
        declared.addAll(customTags);
        declared.addAll(other.customTags());
        return new TemplateManifest(next, declared);
    }

    /**
     * {@code tag → template names} over every tag actually assigned, with every untagged name in
     * {@code allTemplateNames} collected under {@link #UNTAGGED}. Names not on disk are dropped, so a stale
     * manifest entry never shows an empty row.
     *
     * <p>Takes no view on whether a tag is <em>declared</em> — see the {@link TagCatalog} overload for the
     * grouping the UI renders.
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

    /**
     * The grouping the UI renders: {@link #ALL} first, then every tag {@code catalog} declares — in its
     * order, activity tags before custom ones — and finally {@link #UNTAGGED}.
     *
     * <p>Two rules distinguish this from {@link #byTag(Collection)}, and both follow from the catalog being
     * the authority on which tags exist. A declared tag is listed <b>even when empty</b>, because the point
     * of declaring is that the tag is there to file things under. An assignment to an <b>undeclared</b> tag
     * is ignored — it does not create a row, and a template carrying nothing else counts as untagged — so a
     * renamed or deleted activity cannot leave a ghost group behind, and its templates surface where someone
     * will find them again.
     */
    public Map<String, List<String>> byTag(Collection<String> allTemplateNames, TagCatalog catalog) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(allTemplateNames);
        grouped.put(ALL, all);
        // A second view of the same lists, keyed case-insensitively: "mining" on a template has to find the
        // row declared as "Mining", the way every other name comparison here does.
        Map<String, List<String>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String tag : catalog.names()) {
            List<String> row = new ArrayList<>();
            grouped.put(tag, row);
            byName.put(tag, row);
        }

        List<String> untagged = new ArrayList<>();
        for (String name : all) {
            boolean filed = false;
            for (String tag : tagsOf(name)) {
                List<String> row = byName.get(tag);
                if (row == null) continue;  // assigned to a tag nothing declares: inert, not an error
                row.add(name);
                filed = true;
            }
            if (!filed) untagged.add(name);
        }
        if (!untagged.isEmpty()) grouped.put(UNTAGGED, untagged);
        return grouped;
    }
}
