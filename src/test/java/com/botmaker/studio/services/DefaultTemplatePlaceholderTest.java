package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageTemplateLibrary#isUnmodifiedDefaultTemplate} — the question a whole-library export asks before
 * leaving {@code default_template.png} out.
 *
 * <p>The comparison is by pixel and not by file bytes on purpose: every project generates its own copy of the
 * placeholder, and two PNG encodings of the same picture are different files. Getting that wrong is what put
 * a {@code default_template_2} in the destination of every import.
 */
class DefaultTemplatePlaceholderTest {

    private static Path write(Path dir, String name, BufferedImage img) throws IOException {
        Path file = dir.resolve(name);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    @Test
    void aFreshlyGeneratedPlaceholderIsRecognised(@TempDir Path dir) throws IOException {
        Path file = write(dir, ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE,
                ImageTemplateLibrary.defaultTemplateImage());
        assertTrue(ImageTemplateLibrary.isUnmodifiedDefaultTemplate(file));
    }

    /** Replaced through the resource manager: same name, real content — it is a template like any other. */
    @Test
    void aReplacedDefaultTemplateIsNotThePlaceholder(@TempDir Path dir) throws IOException {
        BufferedImage replaced = ImageTemplateLibrary.defaultTemplateImage();
        replaced.setRGB(0, 0, 0xFF000000);
        Path file = write(dir, ImageTemplateLibrary.DEFAULT_TEMPLATE_FILE, replaced);
        assertFalse(ImageTemplateLibrary.isUnmodifiedDefaultTemplate(file));
    }

    @Test
    void anotherTemplateIsNeverThePlaceholder(@TempDir Path dir) throws IOException {
        Path file = write(dir, "gold_ore.png", ImageTemplateLibrary.defaultTemplateImage());
        assertFalse(ImageTemplateLibrary.isUnmodifiedDefaultTemplate(file));
    }
}
