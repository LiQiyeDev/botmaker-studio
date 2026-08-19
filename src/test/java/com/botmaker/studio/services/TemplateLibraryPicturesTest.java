package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ImageTemplateLibrary} knows about the <em>pictures</em> it stores, rather than the names: which
 * templates are the same picture under two names, which ones no longer have a file at all, and that replacing
 * a picture from an image file outside the project copies it in.
 *
 * <p>That last one is the reason this class exists at all. "Use an image file…" picks a file anywhere on disk;
 * if it were ever stored as a reference rather than a copy, the bot would run fine on the machine it was
 * authored on and find nothing anywhere else — a failure that no compile catches and that only shows up in
 * somebody else's hands.
 */
class TemplateLibraryPicturesTest {

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("Pics", root);
        Files.createDirectories(config.imagesRoot());
        return config;
    }

    /** A tiny solid-colour picture — small enough that a hash over its pixels is instant. */
    private static BufferedImage image(int rgb) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, rgb);
        return img;
    }

    private static void save(ProjectConfig config, String name, int rgb) throws IOException {
        ImageTemplateLibrary.saveTemplate(config, image(rgb), name, 0, 0, null);
    }

    @Test
    void twoNamesForOnePictureFindEachOther(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        save(config, "accept", 0xFF00FF00);
        save(config, "accept_again", 0xFF00FF00);
        save(config, "cancel", 0xFFFF0000);

        Map<String, List<String>> duplicates = ImageTemplateLibrary.duplicatePictures(config);
        assertEquals(List.of("accept_again"), duplicates.get("accept"));
        assertEquals(List.of("accept"), duplicates.get("accept_again"));
        assertFalse(duplicates.containsKey("cancel"), "a picture nothing else has is not a duplicate");
    }

    @Test
    void aRewrittenPngIsStillTheSamePicture(@TempDir Path root) throws IOException {
        // The point of hashing pixels rather than file bytes: the same picture written twice by ImageIO is
        // two different files, and an index built on file bytes would call them two pictures.
        ProjectConfig config = project(root);
        save(config, "one", 0xFF112233);
        save(config, "two", 0xFF112233);
        assertEquals(ImageTemplateLibrary.pictureHash(config.imagesRoot().resolve("one.png")),
                ImageTemplateLibrary.pictureHash(config.imagesRoot().resolve("two.png")));
    }

    @Test
    void aFileDeletedOutsideStudioIsReported(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        save(config, "gone", 0xFF000000);
        save(config, "here", 0xFF010101);
        ImageTemplateLibrary.declareTag(config, "menus");
        ImageTemplateLibrary.addTag(config, List.of("gone", "here"), "menus");

        Files.delete(config.imagesRoot().resolve("gone.png"));

        assertEquals(List.of("gone"), ImageTemplateLibrary.missingTemplates(config));
    }

    @Test
    void anUntaggedMissingTemplateIsNothingToReport(@TempDir Path root) throws IOException {
        // Nothing files it, so there is no manifest entry left behind to offer to drop — and a template the
        // user deleted through Studio must not come back as a complaint the next time the manager opens.
        ProjectConfig config = project(root);
        save(config, "solo", 0xFF000000);
        Files.delete(config.imagesRoot().resolve("solo.png"));
        assertTrue(ImageTemplateLibrary.missingTemplates(config).isEmpty());
    }

    @Test
    void replacingFromAnOutsideFileCopiesItIn(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        ProjectConfig config = project(root);
        save(config, "button", 0xFF000000);

        Path outside = elsewhere.resolve("screenshot.png");
        ImageIO.write(image(0xFF00AAFF), "png", outside.toFile());

        Path template = config.imagesRoot().resolve("button.png");
        ImageTemplateLibrary.replaceImage(config, template, ImageIO.read(outside.toFile()), 0, 0, null);

        assertTrue(Files.isRegularFile(outside), "the file the user picked is left where it was");
        assertEquals(List.of(template), ImageTemplateLibrary.list(config),
                "the picture goes into the project's images folder, under the template's own name");
        assertEquals(ImageTemplateLibrary.pictureHash(outside), ImageTemplateLibrary.pictureHash(template));

        // And the copy survives the outside file going away, which is the whole point of copying it.
        Files.delete(outside);
        assertNotNull(ImageTemplateLibrary.pictureHash(template));
    }
}
