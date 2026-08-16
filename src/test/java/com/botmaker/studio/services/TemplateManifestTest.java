package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateManifest} — the tag model behind "organise templates into categories".
 *
 * <p>Tested at this level because the manifest is the whole feature: the tree in the resource manager and
 * the submenus in the picker are both renderings of {@link TemplateManifest#byTag}, and the property that
 * matters (a template can be in two groups without existing twice) is a property of this model, not of a
 * JavaFX control.
 */
class TemplateManifestTest {

    @Test
    void aTemplateCanCarryTwoTagsAndIsStillOneTemplate() {
        TemplateManifest manifest = TemplateManifest.empty()
                .withTags("gold_ore", List.of("Mining", "Shared"));

        Map<String, List<String>> byTag = manifest.byTag(List.of("gold_ore"));
        assertEquals(List.of("gold_ore"), byTag.get("Mining"));
        assertEquals(List.of("gold_ore"), byTag.get("Shared"));
        // Two branches, one file — the reason tags were chosen over folders.
        assertEquals(Set.of("Mining", "Shared"), Set.copyOf(manifest.tagsOf("gold_ore")));
    }

    @Test
    void everythingUntaggedLandsInOneBucketAndNothingElseDoes() {
        TemplateManifest manifest = TemplateManifest.empty().withTags("gold_ore", List.of("Mining"));

        Map<String, List<String>> byTag = manifest.byTag(List.of("gold_ore", "accept_button"));
        assertEquals(List.of("accept_button"), byTag.get(TemplateManifest.UNTAGGED));
        assertEquals(List.of("gold_ore"), byTag.get("Mining"));
    }

    @Test
    void aManifestEntryWithNoFileOnDiskIsIgnored() {
        // Deleting a template outside Studio must not leave a phantom row under its tag.
        TemplateManifest manifest = TemplateManifest.empty().withTags("deleted_elsewhere", List.of("Mining"));
        assertTrue(manifest.byTag(List.of()).isEmpty());
    }

    @Test
    void tagsAreMatchedCaseInsensitivelySoATreeCannotShowBoth() {
        TemplateManifest manifest = TemplateManifest.empty()
                .withTags("a", List.of("Mining"))
                .tagged(List.of("a"), "mining");
        assertEquals(1, manifest.tagsOf("a").size());
        assertEquals(1, manifest.allTags().size());
    }

    @Test
    void renameAndDeleteKeepTheManifestInStepWithTheFiles() {
        TemplateManifest manifest = TemplateManifest.empty().withTags("old", List.of("Mining"));

        TemplateManifest renamed = manifest.renamed("old", "new");
        assertTrue(renamed.tagsOf("old").isEmpty());
        assertEquals(Set.of("Mining"), Set.copyOf(renamed.tagsOf("new")));

        assertTrue(renamed.without("new").tagsOf("new").isEmpty());
    }

    @Test
    void mergeUnionsTagsRatherThanReplacingThem() {
        // An import must not undo the tags this project already gave a template of the same name.
        TemplateManifest mine = TemplateManifest.empty().withTags("shared", List.of("Local"));
        TemplateManifest theirs = TemplateManifest.empty().withTags("shared", List.of("Imported"));

        assertEquals(Set.of("Local", "Imported"), Set.copyOf(mine.mergedWith(theirs).tagsOf("shared")));
    }

    @Test
    void anExportSliceCarriesOnlyTheNamesBeingExported() {
        TemplateManifest manifest = TemplateManifest.empty()
                .withTags("a", List.of("Mining"))
                .withTags("b", List.of("Combat"));

        TemplateManifest slice = manifest.restrictedTo(List.of("a"));
        assertEquals(Set.of("Mining"), Set.copyOf(slice.tagsOf("a")));
        assertTrue(slice.tagsOf("b").isEmpty());
    }

    @Test
    void aRoundTripThroughDiskPreservesTheTags(@TempDir Path dir) throws IOException {
        TemplateManifest manifest = TemplateManifest.empty()
                .withTags("gold_ore", List.of("Mining", "Shared"))
                .withTags("accept", List.of("Popups"));
        manifest.write(dir);

        TemplateManifest reloaded = TemplateManifest.read(dir);
        assertEquals(Set.of("Mining", "Shared"), Set.copyOf(reloaded.tagsOf("gold_ore")));
        assertEquals(Set.of("Popups"), Set.copyOf(reloaded.tagsOf("accept")));
    }

    @Test
    void anUnreadableOrAbsentManifestReadsAsNoTagsRatherThanFailing(@TempDir Path dir) throws IOException {
        // Tags are decoration; a project whose manifest was hand-edited into nonsense must still open.
        assertTrue(TemplateManifest.read(dir).allTags().isEmpty());
        Files.writeString(dir.resolve(TemplateManifest.FILE_NAME), "{ not json");
        assertTrue(TemplateManifest.read(dir).allTags().isEmpty());
    }

    @Test
    void writingAnEmptyManifestRemovesTheFile(@TempDir Path dir) throws IOException {
        TemplateManifest.empty().withTags("a", List.of("Mining")).write(dir);
        assertTrue(Files.exists(dir.resolve(TemplateManifest.FILE_NAME)));
        TemplateManifest.empty().write(dir);
        assertFalse(Files.exists(dir.resolve(TemplateManifest.FILE_NAME)));
    }

    @Test
    void tagsAreTrimmedAndBlankOnesAreNotTags() {
        assertEquals("Mining Camp", TemplateManifest.sanitizeTag("  Mining   Camp "));
        assertTrue(TemplateManifest.empty().withTags("a", List.of("  ", "")).tagsOf("a").isEmpty());
    }
}
