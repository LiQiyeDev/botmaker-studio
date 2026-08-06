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

    /**
     * The SDK tier. {@code Point} exists in both {@code com.botmaker.sdk.api} and {@code java.awt}, and the
     * paste path has no analyzer index to disambiguate with — so this is the case the old hand-written JDK
     * map could only handle by omitting {@code Point} from itself and explaining why in a comment.
     */
    @Test
    void pastingAnSdkTypeResolvesToTheSdkNotToAwt() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "Point where = Mouse.position();");

        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("import com.botmaker.sdk.api.Point;"),
                () -> "Point must resolve to the SDK in:\n" + f.lastCode);
        assertFalse(f.lastCode.contains("java.awt.Point"),
                () -> "Point must not resolve to java.awt in:\n" + f.lastCode);
    }

    /** A facade in a sub-package — an FQN no amount of string manipulation on the simple name could produce. */
    @Test
    void pastingASubPackagedSdkFacadeResolvesToItsRealPackage() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "ImageFinder.find(new ImageTemplate(\"gold.png\"));");

        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("import com.botmaker.sdk.api.vision.ImageFinder;"),
                () -> "expected the vision sub-package in:\n" + f.lastCode);
        assertTrue(f.lastCode.contains("import com.botmaker.sdk.api.vision.ImageTemplate;"),
                () -> "expected the vision sub-package in:\n" + f.lastCode);
    }

    /**
     * The JDK probe. None of these four were in the 14-entry map the probe replaced, so before it they
     * pasted as unresolvable names.
     */
    @Test
    void pastingJdkTypesTheOldMapNeverListedNowResolves() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, """
                Optional<Instant> when = Optional.of(Instant.now());
                Pattern p = Pattern.compile("x");
                BigDecimal d = BigDecimal.ONE;""");

        assertNotNull(f.lastCode);
        for (String expected : new String[]{
                "import java.util.Optional;", "import java.time.Instant;",
                "import java.util.regex.Pattern;", "import java.math.BigDecimal;"}) {
            assertTrue(f.lastCode.contains(expected), () -> "missing " + expected + " in:\n" + f.lastCode);
        }
    }

    /** Order is the disambiguation: {@code java.util} is probed before {@code java.awt}, which also has a {@code List}. */
    @Test
    void listResolvesToJavaUtilNotJavaAwt() {
        EditorFixture f = new EditorFixture(SUBJECT);
        f.editor.pasteCode(f.body("run"), 1, "List<String> names = new ArrayList<>();");

        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("import java.util.List;"), () -> "expected java.util.List in:\n" + f.lastCode);
        assertFalse(f.lastCode.contains("java.awt.List"), () -> "java.awt.List in:\n" + f.lastCode);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
