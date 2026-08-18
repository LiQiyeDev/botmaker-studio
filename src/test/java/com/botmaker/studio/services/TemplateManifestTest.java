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
    void aTagComesOffTheNamedTemplatesAndOffNothingElse() {
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Mining")
                .withTags("a", List.of("Mining", "Shared"))
                .withTags("b", List.of("Mining"))
                .withTags("c", List.of("Mining"))
                .untagged(List.of("a", "b"), "MINING");   // spelling is not the question a removal answers

        assertEquals(List.of("Shared"), List.copyOf(manifest.tagsOf("a")));
        assertTrue(manifest.tagsOf("b").isEmpty());
        assertEquals(List.of("Mining"), List.copyOf(manifest.tagsOf("c")), "c was not named");
    }

    @Test
    void emptyingATagDoesNotDeleteIt() {
        // Removing the last assignment is not the same gesture as deleting the group — the tag manager owns
        // that. An emptied tag stays on the rail, ready to be filled again.
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Mining")
                .withTags("a", List.of("Mining"))
                .untagged(List.of("a"), "Mining");

        assertTrue(manifest.customTags().contains("Mining"));
        assertTrue(manifest.byTag(List.of("a"), TagCatalog.of(null, manifest.customTags()))
                .get("Mining").isEmpty());
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
    void aDeclaredTagSurvivesWithNothingCarryingIt(@TempDir Path dir) throws IOException {
        // The whole point of declaring: the tag is there to file things under, so it has to outlive the
        // moment before anything is filed. The manifest used to be deleted when no template was tagged.
        TemplateManifest.empty().declaring("Shared").write(dir);

        assertTrue(Files.exists(dir.resolve(TemplateManifest.FILE_NAME)));
        assertEquals(Set.of("Shared"), Set.copyOf(TemplateManifest.read(dir).customTags()));
    }

    @Test
    void aManifestWrittenBeforeTagsWereDeclaredStillLoads(@TempDir Path dir) throws IOException {
        // The old shape was the assignment map on its own, with no wrapper and no declarations.
        Files.writeString(dir.resolve(TemplateManifest.FILE_NAME),
                "{ \"gold_ore\": { \"tags\": [\"Mining\"] } }");

        TemplateManifest read = TemplateManifest.read(dir);
        assertEquals(Set.of("Mining"), Set.copyOf(read.tagsOf("gold_ore")));
        assertTrue(read.customTags().isEmpty());
    }

    @Test
    void deletingACustomTagTakesItsAssignmentsWithIt() {
        // Left behind, they are invisible — and come back to life the day someone declares the name again.
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Shared")
                .withTags("gold_ore", List.of("Shared", "Mining"));

        TemplateManifest without = manifest.undeclaring("shared");
        assertEquals(Set.of("Mining"), Set.copyOf(without.tagsOf("gold_ore")));
        assertTrue(without.customTags().isEmpty());
    }

    @Test
    void renamingACustomTagCarriesEveryTemplateWithIt() {
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Shared")
                .withTags("a", List.of("Shared"))
                .withTags("b", List.of("Shared"));

        TemplateManifest renamed = manifest.renamedTag("Shared", "Common");
        assertEquals(Set.of("Common"), Set.copyOf(renamed.customTags()));
        assertEquals(Set.of("Common"), Set.copyOf(renamed.tagsOf("a")));
        assertEquals(Set.of("Common"), Set.copyOf(renamed.tagsOf("b")));
    }

    @Test
    void theListingIsOverTheDeclaredSetNotOverWhatWasAssigned() {
        TagCatalog catalog = TagCatalog.of(null, List.of("Shared", "Empty"));
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Shared").declaring("Empty")
                .withTags("gold_ore", List.of("Shared"))
                // "Mining" was an activity that has since been renamed: still in the file, no longer declared.
                .withTags("orphan", List.of("Mining"));

        Map<String, List<String>> byTag = manifest.byTag(List.of("gold_ore", "orphan"), catalog);

        assertEquals(List.of(TemplateManifest.ALL, "Empty", "Shared", TemplateManifest.UNTAGGED),
                List.copyOf(byTag.keySet()), "All first, then the declared tags, then the leftovers");
        assertEquals(List.of("gold_ore", "orphan"), byTag.get(TemplateManifest.ALL));
        assertEquals(List.of(), byTag.get("Empty"), "a declared tag is a row even with nothing in it");
        assertEquals(List.of("gold_ore"), byTag.get("Shared"));
        // The orphan surfaces where someone will find it again, rather than under a tag that isn't there.
        assertEquals(List.of("orphan"), byTag.get(TemplateManifest.UNTAGGED));
    }

    @Test
    void anAssignmentFindsItsRowWhateverTheSpelling() {
        TagCatalog catalog = TagCatalog.of(null, List.of("Shared"));
        TemplateManifest manifest = TemplateManifest.empty().declaring("Shared")
                .withTags("a", List.of("SHARED"));

        assertEquals(List.of("a"), manifest.byTag(List.of("a"), catalog).get("Shared"));
    }

    @Test
    void theComputedBucketsCannotBeUsedAsRealTags() {
        // Otherwise a tag called "All" would compete with the row that means "everything".
        assertTrue(TemplateManifest.empty().withTags("a", List.of("All", "Untagged")).tagsOf("a").isEmpty());
        assertTrue(TemplateManifest.empty().declaring("All").customTags().isEmpty());
    }

    @Test
    void anExportCarriesTheDeclarationsItsTagsNeed() {
        TemplateManifest manifest = TemplateManifest.empty()
                .declaring("Shared").declaring("Unrelated")
                .withTags("a", List.of("Shared"));

        TemplateManifest slice = manifest.restrictedTo(List.of("a"));
        assertEquals(Set.of("Shared"), Set.copyOf(slice.customTags()),
                "the tag travels with the template; the ones it doesn't use stay behind");
    }

    @Test
    void tagsAreTrimmedAndBlankOnesAreNotTags() {
        assertEquals("Mining Camp", TemplateManifest.sanitizeTag("  Mining   Camp "));
        assertTrue(TemplateManifest.empty().withTags("a", List.of("  ", "")).tagsOf("a").isEmpty());
    }
}
