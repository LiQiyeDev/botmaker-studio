package com.botmaker.studio.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pasting a block brings its imports with it.
 *
 * <p>The clipboard carries bare source — an in-app copy is just {@code node.toString()} — so a pasted
 * {@code new ArrayList<>()} used to land in a file that had never imported {@code ArrayList} and simply
 * stopped compiling. The paste path now resolves the snippet's type names through {@link ImportManager}.
 */
class PasteImportsTest {

    private static final String SUBJECT = """
            package com.mybot;
            public class Subject {
                public static void run() {
                    int a = 1;
                }
            }
            """;

    @Test
    void pastingATypeAddsItsImport() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "ArrayList<String> names = new ArrayList<>();");

        assertNotNull(f.lastCode, "the paste should have produced new code");
        assertTrue(f.lastCode.contains("import java.util.ArrayList;"),
                () -> "expected an ArrayList import in:\n" + f.lastCode);
    }

    @Test
    void pastingTheSameTypeTwiceImportsItOnce() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "ArrayList<String> a = new ArrayList<>();");
        String afterFirst = f.lastCode;

        EditorFixture second = new EditorFixture(afterFirst);
        second.editor.pasteCode(second.body("run"), 1, "ArrayList<String> b = new ArrayList<>();");

        assertNotNull(second.lastCode);
        assertEquals(1, countOf(second.lastCode, "import java.util.ArrayList;"),
                () -> "import should not be duplicated:\n" + second.lastCode);
    }

    @Test
    void pastingUnresolvableTextStillPastes() {
        // Clipboard text can be anything. An unknown type must not block the paste or invent an import.
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "Whatever thing = new Whatever();");

        assertNotNull(f.lastCode, "the paste itself must still land");
        assertTrue(f.lastCode.contains("Whatever thing"), () -> "pasted code missing from:\n" + f.lastCode);
        assertFalse(f.lastCode.contains("import Whatever"), () -> "invented an import in:\n" + f.lastCode);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
