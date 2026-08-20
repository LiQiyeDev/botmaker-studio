package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a click on a template tile means in a multi-select gallery.
 *
 * <p>It used to mean "toggle", on the reasoning that batch work is the resource manager's whole purpose and
 * requiring a modifier hid the feature. What it did in practice was make the <em>most</em> common gesture —
 * clicking one template, then another, to look at them — end with both selected and the next Delete acting on
 * two files. So the gestures are the ones every file manager has: click replaces, Ctrl adds, Shift extends.
 */
class TemplateGallerySelectionTest extends FxHeadlessTest {

    @Test
    void aPlainClickReplacesTheSelectionAndTheModifiersBuildOne() throws IOException {
        ProjectConfig config = project("alpha", "bravo", "charlie", "delta");

        TemplateGallery gallery = build(config, true);
        List<Node> tiles = tilesOf(gallery);
        assertEquals(4, tiles.size(), "one tile per template");

        interact(() -> click(tiles.get(0), false, false));
        assertEquals(1, gallery.selectedFiles().size());

        // The gesture that was broken: looking at a second template is looking, not adding.
        interact(() -> click(tiles.get(2), false, false));
        assertEquals(List.of(fileOf(tiles, gallery, 2)), gallery.selectedFiles(),
                "a plain click replaces what was selected");

        interact(() -> click(tiles.get(3), false, true));
        assertEquals(2, gallery.selectedFiles().size(), "Ctrl-click adds");
        interact(() -> click(tiles.get(3), false, true));
        assertEquals(1, gallery.selectedFiles().size(), "and Ctrl-click on a selected tile removes it");

        // Shift measures from the anchor — the last tile a click landed on without Shift, here tile 1.
        interact(() -> click(tiles.get(1), false, false));
        interact(() -> click(tiles.get(3), true, false));
        assertEquals(3, gallery.selectedFiles().size(), "Shift-click takes the whole range, both ends included");

        // Select all is still one gesture, which is what makes the plain click affordable.
        interact(gallery::selectAll);
        assertEquals(4, gallery.selectedFiles().size());
    }

    @Test
    void aSingleSelectGalleryStillTakesExactlyOne() throws IOException {
        ProjectConfig config = project("alpha", "bravo");

        TemplateGallery gallery = build(config, false);
        List<Node> tiles = tilesOf(gallery);

        interact(() -> click(tiles.get(0), false, false));
        interact(() -> click(tiles.get(1), false, true));   // a modifier must not open multi-select here
        assertEquals(1, gallery.selectedFiles().size());
    }

    /** The file behind tile {@code index}, read the only way the widget exposes it: by selecting it. */
    private Path fileOf(List<Node> tiles, TemplateGallery gallery, int index) {
        List<Path> before = gallery.selectedFiles();
        interact(() -> click(tiles.get(index), false, false));
        Path file = gallery.selectedFiles().getFirst();
        interact(() -> gallery.setSelection(before));
        return file;
    }

    /**
     * A gallery in a scene, laid out. The scene is not decoration: the tiles live inside a {@code ScrollPane},
     * whose content is not reachable at all until its skin exists — which needs a CSS pass.
     */
    private TemplateGallery build(ProjectConfig config, boolean multiSelect) {
        TemplateGallery[] holder = new TemplateGallery[1];
        interact(() -> {
            holder[0] = new TemplateGallery(config, multiSelect);
            new Scene(holder[0], 900, 600);
            holder[0].applyCss();
            holder[0].layout();
        });
        return holder[0];
    }

    private static List<Node> tilesOf(TemplateGallery gallery) {
        FlowPane grid = (FlowPane) gallery.lookup(".template-tile").getParent();
        return List.copyOf(grid.getChildren());
    }

    private static void click(Node tile, boolean shift, boolean control) {
        Event.fireEvent(tile, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                shift, control, false, false, true, false, false, false, false, false, null));
    }

    private static ProjectConfig project(String... names) throws IOException {
        Path root = Files.createTempDirectory("gallery-selection");
        ProjectConfig config = ProjectConfig.forProject("selectbot", root);
        Files.createDirectories(config.imagesRoot());
        for (String name : names) {
            ImageTemplateLibrary.saveTemplate(config, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB),
                    name, 0, 0, null);
        }
        return config;
    }
}
