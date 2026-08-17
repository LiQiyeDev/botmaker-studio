package com.botmaker.studio.services;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tags a project has. The point of this type is that the answer is <em>finite and derived</em> rather
 * than "whatever was typed into a text field", so the tests are about where each tag comes from and what
 * happens when the two sources disagree.
 */
class TagCatalogTest {

    private static ActivitiesConfig activities(String... names) {
        return new ActivitiesConfig(
                List.of(names).stream().map(n -> ActivityDefinition.create(n, "")).toList(), List.of());
    }

    @Test
    void everyActivityHasATagAndTheUserNeverMadeIt() {
        TagCatalog catalog = TagCatalog.of(activities("Mining", "Combat"), List.of());

        assertEquals(List.of("Mining", "Combat"), catalog.names(), "in the order the activities are listed");
        assertTrue(catalog.isManaged("Mining"));
        assertTrue(catalog.isManaged("mining"), "spelling is not what identifies a tag");
    }

    @Test
    void anArchivedActivityKeepsItsTag() {
        // Archiving is reversible and the templates are still that activity's. Dropping the tag would strand
        // them under nothing the moment someone archived an activity to come back to next week.
        ActivitiesConfig config = new ActivitiesConfig(
                List.of(ActivityDefinition.create("Mining", ""),
                        ActivityDefinition.create("Smelting", "").withArchived(true)),
                List.of());

        assertEquals(List.of("Mining", "Smelting"), TagCatalog.of(config, List.of()).names());
    }

    @Test
    void customTagsFollowTheActivityOnesAndAreTheUsersToChange() {
        TagCatalog catalog = TagCatalog.of(activities("Mining"), List.of("Shared", "Popups"));

        assertEquals(List.of("Mining", "Popups", "Shared"), catalog.names(),
                "activities first, then custom tags alphabetically");
        assertFalse(catalog.isManaged("Shared"));
        assertEquals(TagCatalog.Kind.CUSTOM, catalog.find("Shared").kind());
    }

    @Test
    void aCustomTagCannotShadowAnActivity() {
        // Both would be one row in every picker, and only one of them can be kept in step with the activity.
        TagCatalog catalog = TagCatalog.of(activities("Mining"), List.of("mining"));

        assertEquals(List.of("Mining"), catalog.names());
        assertEquals(TagCatalog.Kind.ACTIVITY, catalog.find("mining").kind());
    }

    @Test
    void whatIsNotDeclaredIsNotATag() {
        TagCatalog catalog = TagCatalog.of(activities("Mining"), List.of());

        assertFalse(catalog.isDeclared("Minning"), "the typo that free text used to make a real tag");
        assertNull(catalog.find("Minning"));
        assertFalse(catalog.isManaged("Minning"), "undeclared is not managed either");
    }

    @Test
    void aSelectionIsNarrowedToDeclaredTagsAndRespelledTheirWay() {
        TagCatalog catalog = TagCatalog.of(activities("Mining"), List.of("Shared"));

        // Whatever order a dialog hands back, what gets saved is catalog order, catalog spelling, and only
        // tags that exist — so a stale selection can't put a dead tag back into the manifest.
        assertEquals(List.of("Mining", "Shared"),
                catalog.declaredOnly(List.of("shared", "gone", "  MINING  ")));
        assertEquals(List.of(), catalog.declaredOnly(List.of()));
    }

    @Test
    void aProjectWithNoActivitiesAndNoCustomTagsHasNoTags() {
        assertEquals(List.of(), TagCatalog.of(ActivitiesConfig.empty(), List.of()).names());
        assertEquals(List.of(), TagCatalog.empty().names());
    }
}
