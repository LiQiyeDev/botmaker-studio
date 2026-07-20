package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.Comment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inserting a statement <em>after</em> a comment must actually put it after the comment.
 *
 * <p>The bug this pins down: {@code BodyBlock} children include comment blocks, but a JDT {@link Comment} is
 * not a {@code Statement} and so has no slot in {@code Block.statements()}. The child index was translated to
 * a statements() index by skipping comments — correct arithmetic, wrong anchor, because JDT treats a leading
 * comment as part of the <em>extended</em> range of the statement that follows it. Inserting at that
 * statements() index therefore landed <b>before</b> the comment, so "Paste After" on a comment block pasted
 * before it.
 */
class CommentAnchoredInsertTest {

    private static final String PASTED = "int pasted = 99;";

    private static String source(String body) {
        return "package com.mybot;\npublic class Subject {\n    public static void run() {\n"
                + body + "    }\n}\n";
    }

    @Test
    void pasteAfterAMidBodyCommentLandsAfterIt() {
        EditorFixture f = new EditorFixture(source("        int a = 1;\n        // marker\n        int b = 2;\n"));
        pasteAfterComment(f);
        assertOrder(f.lastCode, "int a = 1;", "// marker", PASTED, "int b = 2;");
    }

    @Test
    void pasteAfterALeadingCommentLandsAfterIt() {
        EditorFixture f = new EditorFixture(source("        // marker\n        int b = 2;\n"));
        pasteAfterComment(f);
        assertOrder(f.lastCode, "// marker", PASTED, "int b = 2;");
    }

    @Test
    void pasteAfterTheOnlyCommentInAnOtherwiseEmptyBodyLandsAfterIt() {
        EditorFixture f = new EditorFixture(source("        // marker\n"));
        pasteAfterComment(f);
        assertOrder(f.lastCode, "// marker", PASTED);
    }

    @Test
    void pasteAfterAStatementIsUnaffected() {
        EditorFixture f = new EditorFixture(source("        int a = 1;\n        // marker\n        int b = 2;\n"));
        f.editor.pasteCode(f.body("run"), 1, PASTED); // after child 0, "int a = 1;"
        assertOrder(f.lastCode, "int a = 1;", PASTED, "// marker", "int b = 2;");
    }

    /** Pastes after the body's comment child — the "Paste After" path from CodeEditorService. */
    private static void pasteAfterComment(EditorFixture f) {
        BodyBlock body = f.body("run");
        int at = -1;
        for (int i = 0; i < body.getStatements().size(); i++) {
            if (body.getStatements().get(i).getAstNode() instanceof Comment) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "fixture should contain a comment child");
        f.editor.pasteCode(body, at + 1, PASTED);
    }

    /** Asserts the snippets appear in the given order in {@code code}. */
    private static void assertOrder(String code, String... snippets) {
        assertNotNull(code, "the edit should have produced new code");
        int previous = -1;
        for (String snippet : snippets) {
            int at = code.indexOf(snippet);
            assertTrue(at >= 0, () -> "missing '" + snippet + "' in:\n" + code);
            assertTrue(at > previous, "'" + snippet + "' is out of order in:\n" + code);
            previous = at;
        }
    }
}
