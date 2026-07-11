import com.botmaker.studio.services.ImageTemplateLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ImageTemplateLibrary#sanitizeName} — the shared naming rule used by both the single-capture
 * prompt and the batch "name them all" dialog: trim, then replace anything outside {@code [A-Za-z0-9_-]} with
 * {@code _}. It may still return a blank string (callers reject blanks separately).
 */
public class ImageTemplateLibraryNameTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("btn_ok", ImageTemplateLibrary.sanitizeName("  btn_ok  "));
    }

    @Test
    void keepsAllowedCharacters() {
        assertEquals("Btn-9_ok", ImageTemplateLibrary.sanitizeName("Btn-9_ok"));
    }

    @Test
    void replacesDisallowedCharactersWithUnderscore() {
        assertEquals("a_b_c_", ImageTemplateLibrary.sanitizeName("a b.c!"));
    }

    @Test
    void nullAndBlankBecomeEmpty() {
        assertEquals("", ImageTemplateLibrary.sanitizeName(null));
        assertEquals("", ImageTemplateLibrary.sanitizeName("   "));
    }
}
