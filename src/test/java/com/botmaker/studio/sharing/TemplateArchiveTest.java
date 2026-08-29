package com.botmaker.studio.sharing;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.TemplateManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
 * {@link TemplateArchive} — moving templates between projects as a {@code .bmtemplates} file.
 *
 * <p>The two behaviours worth pinning are the ones that would quietly corrupt a project rather than fail
 * loudly: an import must never overwrite a template an existing bot's source already points at, and the
 * resolution sidecar must travel with its PNG (without it the SDK rescales a template against the wrong
 * capture resolution and simply stops matching, with nothing to see in the editor).
 */
class TemplateArchiveTest {

    private static ProjectConfig project(Path root, String name) throws IOException {
        ProjectConfig config = ProjectConfig.forProject(name, root);
        Files.createDirectories(config.imagesRoot());
        return config;
    }

    /** Saves a real (tiny) PNG through the library, so the sidecar is written the way Studio writes it. */
    private static void saveTemplate(ProjectConfig config, String name) throws IOException {
        saveTemplate(config, name, 0xFF000000);
    }

    /** The same, in a given colour — so two projects can hold templates that share a name but not a picture. */
    private static void saveTemplate(ProjectConfig config, String name, int rgb) throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, rgb);
        ImageTemplateLibrary.saveTemplate(config, img, name, 1920, 1080, "Game");
    }

    /**
     * Tags {@code name} the way Studio does: declare the tag on the project, then assign it. There is no
     * other way in — an assignment to a tag the project doesn't declare is dropped on the way to disk.
     */
    private static void tag(ProjectConfig config, String name, String tag) {
        ImageTemplateLibrary.declareTag(config, tag);
        ImageTemplateLibrary.applyTags(config, Map.of(name, List.of(tag)));
    }

    @Test
    void exportThenImportCarriesThePixelsTheSidecarAndTheTags(@TempDir Path root) throws IOException {
        ProjectConfig source = project(root, "Source");
        saveTemplate(source, "gold_ore");
        tag(source, "gold_ore", "Mining");

        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(source, ImageTemplateLibrary.list(source), archive);

        ProjectConfig dest = project(root, "Dest");
        TemplateArchive.ImportResult result = TemplateArchive.importInto(dest, archive);

        assertEquals(List.of("gold_ore"), result.imported());
        Path png = dest.imagesRoot().resolve("gold_ore.png");
        assertTrue(Files.isRegularFile(png));
        assertEquals(4, ImageIO.read(png.toFile()).getWidth());
        assertTrue(Files.isRegularFile(ImageTemplateLibrary.sidecarFor(png)),
                "without the sidecar the SDK rescales against the wrong capture resolution");
        assertEquals(Set.of("Mining"), Set.copyOf(ImageTemplateLibrary.manifest(dest).tagsOf("gold_ore")));
    }

    @Test
    void anImportNeverOverwritesAnExistingTemplate(@TempDir Path root) throws IOException {
        ProjectConfig source = project(root, "Source");
        saveTemplate(source, "accept", 0xFF00FF00);
        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(source, ImageTemplateLibrary.list(source), archive);

        ProjectConfig dest = project(root, "Dest");
        saveTemplate(dest, "accept", 0xFFFF0000);   // same name, a different picture
        long before = Files.size(dest.imagesRoot().resolve("accept.png"));

        TemplateArchive.ImportResult result = TemplateArchive.importInto(dest, archive);

        // The local "accept" is what some bot's source already references — it stays exactly as it was.
        assertEquals(before, Files.size(dest.imagesRoot().resolve("accept.png")));
        assertEquals(List.of("accept_2"), result.imported());
        assertEquals("accept_2", result.renamed().get("accept"));
        assertTrue(Files.isRegularFile(dest.imagesRoot().resolve("accept_2.png")));
    }

    /**
     * The round trip that used to double the library: export everything, import it back into the project it
     * came from. Same name and same bytes is the same template, so there is nothing to add and nothing to
     * rename — the {@code _2} copies this produced were pure noise.
     */
    @Test
    void aTemplateThatIsAlreadyHereIsNotImportedAgain(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root, "Same");
        saveTemplate(config, "accept", 0xFF00FF00);
        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(config, ImageTemplateLibrary.list(config), archive);

        TemplateArchive.ImportResult result = TemplateArchive.importInto(config, archive);

        assertEquals(List.of(), result.imported());
        assertEquals(List.of("accept"), result.unchanged());
        assertTrue(result.renamed().isEmpty());
        assertFalse(Files.exists(config.imagesRoot().resolve("accept_2.png")));
        assertEquals(1, ImageTemplateLibrary.list(config).size());
    }

    /**
     * An import that adds a template must make it nameable.
     *
     * <p>It used to assert that a {@code GOLD_ORE} constant appeared in a generated {@code Templates} class,
     * because a picture with no constant could only be referenced by a raw path literal. There is no such
     * class: {@code Wire.image("gold_ore")} names the file, so the import is complete the moment the file and
     * its library entry are there, which is what this asserts instead.
     */
    @Test
    void anImportMakesTheTemplateNameable(@TempDir Path root) throws IOException {
        ProjectConfig source = project(root, "Source");
        saveTemplate(source, "gold_ore");
        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(source, ImageTemplateLibrary.list(source), archive);

        ProjectConfig dest = project(root, "Dest");
        TemplateArchive.importInto(dest, archive);

        assertTrue(Files.exists(dest.imagesRoot().resolve("gold_ore.png")),
                "the picture itself must arrive; its name is how a bot reaches it");
        assertTrue(ImageTemplateLibrary.list(dest).stream()
                        .anyMatch(t -> ImageTemplateLibrary.baseName(t).equals("gold_ore")),
                "and the library must list it, or no picker offers it");
    }

    @Test
    void importingIntoAProjectWithItsOwnTagsKeepsBoth(@TempDir Path root) throws IOException {
        ProjectConfig source = project(root, "Source");
        saveTemplate(source, "shared");
        tag(source, "shared", "Imported");
        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(source, ImageTemplateLibrary.list(source), archive);

        ProjectConfig dest = project(root, "Dest");
        saveTemplate(dest, "keep_me");
        tag(dest, "keep_me", "Local");

        TemplateArchive.importInto(dest, archive);

        TemplateManifest manifest = ImageTemplateLibrary.manifest(dest);
        assertEquals(Set.of("Local"), Set.copyOf(manifest.tagsOf("keep_me")));
        assertEquals(Set.of("Imported"), Set.copyOf(manifest.tagsOf("shared")));
    }

    @Test
    void exportingASelectionTakesOnlyThatSelection(@TempDir Path root) throws IOException {
        ProjectConfig source = project(root, "Source");
        saveTemplate(source, "a");
        saveTemplate(source, "b");

        Path archive = root.resolve("out" + TemplateArchive.EXTENSION);
        TemplateArchive.export(source, List.of(source.imagesRoot().resolve("a.png")), archive);

        ProjectConfig dest = project(root, "Dest");
        TemplateArchive.importInto(dest, archive);

        assertTrue(ImageTemplateLibrary.exists(dest, "a"));
        assertFalse(ImageTemplateLibrary.exists(dest, "b"));
    }
}
