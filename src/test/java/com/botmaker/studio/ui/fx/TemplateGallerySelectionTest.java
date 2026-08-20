package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.ui.render.components.TemplateGallery;
import javafx.event.Event;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
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
 * two files. So the gestures are the ones every file manager has: click replaces, Ctrl adds, and several at
 * once come from dragging a box over them.
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

        // Select all is still one gesture, which is what makes the plain click affordable.
        interact(gallery::selectAll);
        assertEquals(4, gallery.selectedFiles().size());
    }

    @Test
    void aBoxDraggedOverTheGridTakesWhatItTouches() throws IOException {
        ProjectConfig config = project("alpha", "bravo", "charlie", "delta");

        TemplateGallery gallery = build(config, true);
        List<Node> tiles = tilesOf(gallery);
        Node layer = tiles.getFirst().getParent().getParent();   // grid → the band layer the handlers sit on

        interact(() -> band(layer, boxOver(tiles.subList(0, 3)), false));
        assertEquals(3, gallery.selectedFiles().size(), "a band takes every tile it touches");

        // A second box on its own replaces; with Ctrl it adds to what the first one took.
        interact(() -> band(layer, boxOver(tiles.subList(3, 4)), false));
        assertEquals(1, gallery.selectedFiles().size(), "a plain drag replaces the selection");
        interact(() -> band(layer, boxOver(tiles.subList(0, 1)), true));
        assertEquals(2, gallery.selectedFiles().size(), "Ctrl-drag adds to it");

        // A press on the background that goes nowhere is a click on the background: it clears.
        Bounds empty = layer.getBoundsInLocal();
        interact(() -> {
            press(layer, empty.getMaxX() - 4, empty.getMaxY() - 4, false);
            release(layer, empty.getMaxX() - 4, empty.getMaxY() - 4);
        });
        assertEquals(0, gallery.selectedFiles().size(), "clicking the background clears the selection");
    }

    /** The scene rectangle that just contains {@code nodes} — what the user would drag a box around. */
    private static Bounds boxOver(List<Node> nodes) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Node node : nodes) {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            minX = Math.min(minX, b.getMinX());
            minY = Math.min(minY, b.getMinY());
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }
        return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }

    /** Press on the background at one corner of {@code box}, drag to the other, release. */
    private static void band(Node layer, Bounds box, boolean control) {
        press(layer, box.getMinX() - 2, box.getMinY() - 2, control);
        drag(layer, box.getMaxX() + 2, box.getMaxY() + 2);
        release(layer, box.getMaxX() + 2, box.getMaxY() + 2);
    }

    private static void press(Node layer, double x, double y, boolean control) {
        fire(layer, MouseEvent.MOUSE_PRESSED, x, y, control);
    }

    private static void drag(Node layer, double x, double y) {
        fire(layer, MouseEvent.MOUSE_DRAGGED, x, y, false);
    }

    private static void release(Node layer, double x, double y) {
        fire(layer, MouseEvent.MOUSE_RELEASED, x, y, false);
    }

    private static void fire(Node layer, javafx.event.EventType<MouseEvent> type,
                             double x, double y, boolean control) {
        Event.fireEvent(layer, new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, control, false, false, true, false, false, false, false, false, null));
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
