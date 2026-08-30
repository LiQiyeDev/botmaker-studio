package com.botmaker.studio.services;

import com.botmaker.sdk.authoring.TagCatalog;
import com.botmaker.sdk.authoring.TemplateManifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the template gallery's tag rail shows, and which templates survive its search box — the parts of the
 * gallery that are a decision rather than a widget, kept free of JavaFX so they can be tested headlessly.
 *
 * <p>The rail is not simply "the keys of {@link ImageTemplateLibrary#listByTag}": those keys are a flat
 * sequence, and the two kinds of tag need to read as two groups with a heading each — the same split the
 * picklist and the tag manager show, so a tag sits in the same place wherever it is met. Counts belong here
 * too, because a count is what makes an empty declared tag legible as "declared, nothing filed yet" rather
 * than as a bug.
 */
public final class TemplateGalleryModel {

    private TemplateGalleryModel() {}

    /** A rail entry: either a group heading or a selectable tag. */
    public sealed interface Row permits Heading, TagRow {}

    /** A non-selectable group label ("Activities", "Custom"). */
    public record Heading(String text) implements Row {}

    /**
     * A selectable bucket. {@code tag} is the key into {@link ImageTemplateLibrary#listByTag}, including the
     * computed {@link TemplateManifest#ALL} and {@link TemplateManifest#UNTAGGED} — from the rail's point of
     * view those are rows like any other, which is the point of giving them names no real tag may take.
     */
    public record TagRow(String tag, int count, boolean managed) implements Row {}

    /**
     * The rail for {@code byTag} (as {@link ImageTemplateLibrary#listByTag} returns it) over {@code catalog}:
     * All, then the activity tags under a heading, then the custom ones under theirs, then Untagged when
     * anything is untagged. A declared tag with nothing in it is still a row — it is somewhere to file to.
     */
    public static List<Row> rows(Map<String, List<Path>> byTag, TagCatalog catalog) {
        List<Row> rows = new ArrayList<>();
        rows.add(new TagRow(TemplateManifest.ALL, count(byTag, TemplateManifest.ALL), false));
        addGroup(rows, "Activities", catalog.namesOf(TagCatalog.Kind.ACTIVITY), byTag, true);
        addGroup(rows, "Custom", catalog.namesOf(TagCatalog.Kind.CUSTOM), byTag, false);
        if (byTag.containsKey(TemplateManifest.UNTAGGED)) {
            rows.add(new TagRow(TemplateManifest.UNTAGGED, count(byTag, TemplateManifest.UNTAGGED), false));
        }
        return List.copyOf(rows);
    }

    private static void addGroup(List<Row> rows, String heading, List<String> tags,
                                 Map<String, List<Path>> byTag, boolean managed) {
        if (tags.isEmpty()) return;  // no heading over nothing
        rows.add(new Heading(heading));
        for (String tag : tags) rows.add(new TagRow(tag, count(byTag, tag), managed));
    }

    private static int count(Map<String, List<Path>> byTag, String tag) {
        List<Path> files = byTag.get(tag);
        return files == null ? 0 : files.size();
    }

    /**
     * The templates in {@code files} whose name contains {@code query}, case-insensitively; a blank query
     * keeps everything. Matching the <em>name</em> rather than the path on purpose — the path is an
     * implementation detail the user never typed, and every template shares most of it.
     */
    public static List<Path> matching(List<Path> files, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.copyOf(files);
        return files.stream()
                .filter(f -> ImageTemplateLibrary.baseName(f).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }
}
