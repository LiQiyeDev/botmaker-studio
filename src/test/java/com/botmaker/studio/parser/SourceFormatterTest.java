package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.parser.helpers.SourceParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout pass, held to the two things that make it safe to put on the edit path: it must change nothing
 * about what the code <em>means</em>, and it must never swallow an edit it can't handle.
 */
class SourceFormatterTest {

    /**
     * The real degradation this exists for: a user's activity whose whole lambda, {@code switch} and both
     * guarded labels sit on one line. Same file the insert tests use, for the same reason — it is the artefact,
     * not a reconstruction of it.
     */
    @Test
    void aPackedOneLineFileComesBackMultiLineAndMeansTheSame() {
        String packed = readResource("/parser/packed-switch.java.txt");
        String longest = longestLine(packed);
        assertTrue(longest.length() > 200, "fixture should be packed; longest line is " + longest.length());

        String formatted = SourceFormatter.format(packed);

        assertTrue(longestLine(formatted).length() < longest.length(),
                () -> "nothing was reflowed:\n" + formatted);
        assertSameAst(packed, formatted);
    }

    /**
     * The wiring, not the formatter: an edit published through {@code CodeEditor} comes out laid out. Asserted
     * because the formatter being correct and the write path calling it are two different facts, and the
     * second one is a single line that any refactor can quietly drop.
     */
    @Test
    void codePublishedByAnEditIsFormatted() {
        String packed = readResource("/parser/packed-switch.java.txt");
        EditorFixture fixture = new EditorFixture(packed);

        fixture.editor.addStatement(fixture.body("run"), BlockCatalog.PRINT, 0);

        assertNotNull(fixture.lastCode, () -> "the edit was refused: " + fixture.statusMessages);
        assertTrue(longestLine(fixture.lastCode).length() < longestLine(packed).length(),
                () -> "the write path published unformatted source:\n" + fixture.lastCode);
    }

    /** Formatting twice must be formatting once — otherwise every save produces a diff. */
    @Test
    void formattingIsIdempotent() {
        String once = SourceFormatter.format(readResource("/parser/packed-switch.java.txt"));

        assertEquals(once, SourceFormatter.format(once));
    }

    /**
     * Source that doesn't parse is normal mid-edit, and must come back untouched. JDT does <em>not</em> decline
     * it on its own — it recovers a tree and lays that out, which is a way to lose text from a file that was
     * going to be refused and dumped intact. The check is ours, and this is what holds it in place.
     */
    @Test
    void sourceThatDoesNotParseIsReturnedUntouched() {
        String broken = "package com.mybot;\npublic class Subject { void run() { int a = ; } \n";

        assertSame(broken, SourceFormatter.format(broken),
                "declining must return the very same text, not a reflowed guess at it");
    }

    @Test
    void nullAndBlankAreLetThrough() {
        assertSame(null, SourceFormatter.format(null));
        assertSame("", SourceFormatter.format(""));
    }

    /** Same tree, printed the same way — the only definition of "means the same" available without a compiler. */
    private static void assertSameAst(String before, String after) {
        assertFalse(SourceParser.hasSyntaxErrors(SourceParser.parse(after)),
                () -> "the formatter emitted source that doesn't parse:\n" + after);
        assertEquals(SourceParser.parse(before).toString(), SourceParser.parse(after).toString(),
                "formatting changed the tree, not just the layout");
    }

    private static String longestLine(String source) {
        return source.lines().max((a, b) -> Integer.compare(a.length(), b.length())).orElse("");
    }

    private static String readResource(String path) {
        try (var in = SourceFormatterTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
