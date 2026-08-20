package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three answers a picker gives that are not a widget: what a decimal field accepts and stores, what a key
 * box stores, and where a template name resolves to. The controls themselves are the picker gallery's job.
 */
public class ValueEditorsTest {

    private static final List<String> KEYS = List.of("ESCAPE", "ENTER", "SPACE", "F1");

    @Test
    void aDecimalAcceptsEveryHalfTypedFormOnTheWayToANumber() {
        for (String prefix : List.of("", "-", "+", "1", "1.", "1,", "-0.5", "12,75")) {
            assertTrue(ValueEditors.isDecimalSoFar(prefix), prefix + " is on the way to a decimal");
        }
    }

    @Test
    void aDecimalRefusesWhatCouldNeverBecomeANumber() {
        for (String junk : List.of("1.2.3", "abc", "1e5", "1..", "--1", "1 2")) {
            assertFalse(ValueEditors.isDecimalSoFar(junk), junk + " is not a decimal, half-typed or otherwise");
        }
    }

    /**
     * The reported failure: a French keyboard's numeric pad types a comma, the spinner's locale-aware
     * converter read {@code 1,5} back as {@code 1}, and the decimal was "refused". The wire form is a dot.
     */
    @Test
    void aCommaIsStoredAsADot() {
        assertEquals("1.5", ValueEditors.decimalWire("1,5"));
        assertEquals("1.5", ValueEditors.decimalWire(" 1.5 "));
        assertEquals("", ValueEditors.decimalWire(null));
    }

    @Test
    void aTypedKeyNameWinsOverTheSelectionAndIsCaseInsensitive() {
        assertEquals("ESCAPE", ValueEditors.keyWire(KEYS, "escape", "ENTER"));
        assertEquals("ENTER", ValueEditors.keyWire(KEYS, "", "ENTER"));
    }

    /** A name matching nothing is not a key: better blank than a stored typo. */
    @Test
    void aHalfTypedKeyNameStoresNothing() {
        assertEquals("", ValueEditors.keyWire(KEYS, "esc", null));
    }

    /**
     * {@code pathForName} is what gets stored and {@code fileForName} is what can be opened. Handing the
     * first to {@code Path.of} resolved it against Studio's working directory, which is why a template's
     * thumbnail was missing everywhere outside the gallery.
     */
    @Test
    void aTemplateResolvesUnderTheProjectAndNotTheWorkingDirectory() {
        ProjectConfig project = ProjectConfig.forProject("demo", Path.of("/tmp/projects"));
        Path file = ImageTemplateLibrary.fileForName(project, "button");

        assertTrue(file.isAbsolute());
        assertTrue(file.startsWith(project.projectPath()));
        assertEquals("button.png", file.getFileName().toString());
        assertFalse(Path.of(ImageTemplateLibrary.pathForName(project, "button")).isAbsolute());
    }
}
