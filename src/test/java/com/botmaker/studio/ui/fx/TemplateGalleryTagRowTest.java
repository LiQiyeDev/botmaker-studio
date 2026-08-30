package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.sdk.authoring.TemplateManifest;
import com.botmaker.studio.services.TemplateGalleryModel;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's answer to "which tag am I looking at" — the gate the resource manager's per-tag actions hang
 * on. "All" and "Untagged" are rows computed from the assignments, not groups anything can be filed under, so
 * a per-tag action must be off there: adding a template to "Untagged" would mean removing every tag it has.
 */
class TemplateGalleryTagRowTest extends FxHeadlessTest {

    @Test
    void theComputedRowsAreNotTagsAPerTagActionCanUse() throws IOException {
        ProjectConfig config = project();
        ImageTemplateLibrary.declareTag(config, "Mining");

        TemplateGallery[] gallery = new TemplateGallery[1];
        AtomicInteger tagChanges = new AtomicInteger();
        interact(() -> {
            gallery[0] = new TemplateGallery(config, true);
            gallery[0].setOnTagChanged(tagChanges::incrementAndGet);
        });

        // The rail opens on "All", which is a listing of everything rather than somewhere to file things.
        assertEquals(TemplateManifest.ALL, gallery[0].selectedTag());
        assertNull(gallery[0].selectedRealTag(), "\"All\" is not a tag a template can be added to");

        interact(gallery[0]::reload);
        assertTrue(tagChanges.get() > 0, "the manager retitles its per-tag buttons from this callback");
    }

    /** A declared tag with nothing in it is still selectable — that is the case "Add templates…" exists for. */
    @Test
    void anEmptyDeclaredTagIsARealTag() throws IOException {
        ProjectConfig config = project();
        ImageTemplateLibrary.declareTag(config, "Mining");

        TemplateGallery[] gallery = new TemplateGallery[1];
        interact(() -> gallery[0] = new TemplateGallery(config, true));
        // Selected through the rail's own model rather than by clicking a cell: an unrendered ListView has
        // no cells, and what is under test is the rail's answer, not its painting.
        interact(() -> selectRow(gallery[0], "Mining"));

        assertEquals("Mining", gallery[0].selectedTag());
        assertNotNull(gallery[0].selectedRealTag());
    }

    @SuppressWarnings("unchecked")
    private static void selectRow(TemplateGallery gallery, String tag) {
        ListView<TemplateGalleryModel.Row> rail = (ListView<TemplateGalleryModel.Row>) gallery.lookup(".list-view");
        rail.getItems().stream()
                .filter(row -> row instanceof TemplateGalleryModel.TagRow t && t.tag().equals(tag))
                .findFirst()
                .ifPresent(row -> rail.getSelectionModel().select(row));
    }

    private static ProjectConfig project() throws IOException {
        Path root = Files.createTempDirectory("gallery-tags");
        ProjectConfig config = ProjectConfig.forProject("tagbot", root);
        Files.createDirectories(config.imagesRoot());
        return config;
    }
}
