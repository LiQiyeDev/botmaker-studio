package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two bulk operations the resource manager's tag views need: file several templates under a tag, and take
 * a tag off several templates. They exist alongside {@link ImageTemplateLibrary#applyTags} rather than on top
 * of it because that one <em>replaces</em> a template's whole tag set — the right answer for the "Tags…"
 * picklist and the wrong one for "add these to this group", which must not disturb the tags they already
 * carry.
 */
class TemplateBulkTaggingTest {

    @Test
    void addingATagLeavesTheTagsATemplateAlreadyCarries(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        ImageTemplateLibrary.declareTag(config, "Mining");
        ImageTemplateLibrary.declareTag(config, "Shared");
        ImageTemplateLibrary.applyTags(config, java.util.Map.of("gold_ore", List.of("Shared")));

        ImageTemplateLibrary.addTag(config, List.of("gold_ore", "iron_ore"), "Mining");

        assertEquals(Set.of("Mining", "Shared"),
                Set.copyOf(ImageTemplateLibrary.manifest(config).tagsOf("gold_ore")));
        assertEquals(Set.of("Mining"),
                Set.copyOf(ImageTemplateLibrary.manifest(config).tagsOf("iron_ore")));
    }

    /** A tag exists because it was declared. Adding a name the project doesn't know is a typo, not a group. */
    @Test
    void anUndeclaredTagIsRefusedRatherThanDeclaredAsASideEffect(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        ImageTemplateLibrary.addTag(config, List.of("gold_ore"), "Minning");

        assertTrue(ImageTemplateLibrary.manifest(config).tagsOf("gold_ore").isEmpty());
        assertTrue(ImageTemplateLibrary.tagCatalog(config).find("Minning") == null);
    }

    @Test
    void removingATagTouchesOnlyTheNamedTemplates(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        ImageTemplateLibrary.declareTag(config, "Mining");
        ImageTemplateLibrary.addTag(config, List.of("gold_ore", "iron_ore"), "Mining");

        ImageTemplateLibrary.removeTag(config, List.of("gold_ore"), "Mining");

        assertTrue(ImageTemplateLibrary.manifest(config).tagsOf("gold_ore").isEmpty());
        assertEquals(Set.of("Mining"),
                Set.copyOf(ImageTemplateLibrary.manifest(config).tagsOf("iron_ore")));
        // The group survives its last member leaving — it is still on the rail, ready to be filled again.
        assertTrue(ImageTemplateLibrary.tagCatalog(config).isDeclared("Mining"));
    }

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("tagbot", root);
        Files.createDirectories(config.imagesRoot());
        return config;
    }
}
