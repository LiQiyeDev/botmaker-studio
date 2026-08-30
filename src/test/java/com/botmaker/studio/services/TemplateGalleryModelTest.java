package com.botmaker.studio.services;

import com.botmaker.sdk.authoring.TagCatalog;
import com.botmaker.sdk.authoring.TemplateManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's rail and its search box — the two parts of "one gallery, two places" that are a decision
 * rather than a widget. Tested here because the same rail is rendered in the resource manager and in every
 * template picker: if the rows disagree between them, that is this class disagreeing with itself.
 */
class TemplateGalleryModelTest {

    private static Path template(String name) {
        return Path.of("src/main/resources/images", name + ".png");
    }

    private static Map<String, List<Path>> byTag(Object... pairs) {
        Map<String, List<Path>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") List<String> names = (List<String>) pairs[i + 1];
            map.put((String) pairs[i], names.stream().map(TemplateGalleryModelTest::template).toList());
        }
        return map;
    }

    private static TagCatalog catalog(List<String> activities, List<String> custom) {
        return new TagCatalog(java.util.stream.Stream.concat(
                activities.stream().map(n -> new TagCatalog.Tag(n, TagCatalog.Kind.ACTIVITY)),
                custom.stream().map(n -> new TagCatalog.Tag(n, TagCatalog.Kind.CUSTOM))).toList());
    }

    @Test
    void theRailIsAllThenTheTwoGroupsThenTheLeftovers() {
        List<TemplateGalleryModel.Row> rows = TemplateGalleryModel.rows(
                byTag(TemplateManifest.ALL, List.of("a", "b", "c"),
                        "Mining", List.of("a"),
                        "Shared", List.of("b"),
                        TemplateManifest.UNTAGGED, List.of("c")),
                catalog(List.of("Mining"), List.of("Shared")));

        assertEquals(List.of(
                        new TemplateGalleryModel.TagRow(TemplateManifest.ALL, 3, false),
                        new TemplateGalleryModel.Heading("Activities"),
                        new TemplateGalleryModel.TagRow("Mining", 1, true),
                        new TemplateGalleryModel.Heading("Custom"),
                        new TemplateGalleryModel.TagRow("Shared", 1, false),
                        new TemplateGalleryModel.TagRow(TemplateManifest.UNTAGGED, 1, false)),
                rows);
    }

    @Test
    void aDeclaredTagWithNothingInItIsStillARowWithAZero() {
        // Somewhere to file to. A rail that hid it would make a tag look like it hadn't been created.
        List<TemplateGalleryModel.Row> rows = TemplateGalleryModel.rows(
                byTag(TemplateManifest.ALL, List.of("a"), "Empty", List.of()),
                catalog(List.of(), List.of("Empty")));

        assertTrue(rows.contains(new TemplateGalleryModel.TagRow("Empty", 0, false)));
    }

    @Test
    void aGroupWithNoTagsGetsNoHeading() {
        List<TemplateGalleryModel.Row> rows = TemplateGalleryModel.rows(
                byTag(TemplateManifest.ALL, List.of("a")), catalog(List.of(), List.of()));

        assertEquals(List.of(new TemplateGalleryModel.TagRow(TemplateManifest.ALL, 1, false)), rows);
    }

    @Test
    void untaggedAppearsOnlyWhenSomethingIsUntagged() {
        // listByTag omits the bucket entirely when it is empty, and the rail must not invent it.
        List<TemplateGalleryModel.Row> rows = TemplateGalleryModel.rows(
                byTag(TemplateManifest.ALL, List.of("a"), "Mining", List.of("a")),
                catalog(List.of("Mining"), List.of()));

        assertTrue(rows.stream().noneMatch(r -> r instanceof TemplateGalleryModel.TagRow t
                && t.tag().equals(TemplateManifest.UNTAGGED)));
    }

    @Test
    void searchMatchesPartOfTheNameInAnyCaseAndABlankQueryKeepsEverything() {
        List<Path> files = List.of(template("gold_ore"), template("accept_button"), template("ORE_vein"));

        assertEquals(List.of(template("gold_ore"), template("ORE_vein")),
                TemplateGalleryModel.matching(files, "ore"));
        assertEquals(files, TemplateGalleryModel.matching(files, "   "));
        assertEquals(List.of(), TemplateGalleryModel.matching(files, "nothing"));
    }
}
