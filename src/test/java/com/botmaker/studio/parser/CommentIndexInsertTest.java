package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.Comment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drop index counts {@link BodyBlock} children, which include comments; JDT's {@code Block.statements()}
 * does not, because a {@code Comment} is not a {@code Statement}. The two indexings therefore diverge in any
 * body that holds a comment, and every insert has to translate between them.
 *
 * <p>This was found through a rule that no longer exists — an activity stub's {@code run()} used to end in a
 * pinned {@code return Outcome.NEXT;} that nothing could be inserted after, and a drop between the generated
 * {@code // TODO} and that return was compared in the wrong indexing and wrongly refused. The pinned return
 * went with the generator on 2026-08-29; the index translation it exposed is what actually needed testing,
 * and it is still live in {@code CodeEditor.toStatementIndex}.
 */
class CommentIndexInsertTest {

    private static final String PASTED = "int pasted = 99;";

    private static EditorFixture withComment() {
        Path file = EditorFixture.activitiesFile("Mining.java");
        String source = "package com.mybot.activities;\n"
                + "public class Mining {\n"
                + "    public void run() {\n"
                + "        // TODO: how to do Mining\n"
                + "        int last = 1;\n"
                + "    }\n"
                + "}\n";
        return new EditorFixture(source, file);
    }

    /** The child index just after the body's comment. */
    private static int afterComment(BodyBlock body) {
        for (int i = 0; i < body.getStatements().size(); i++) {
            if (body.getStatements().get(i).getAstNode() instanceof Comment) return i + 1;
        }
        throw new AssertionError("fixture run() should contain a comment child");
    }

    @Test
    void insertJustAfterACommentLandsWhereItWasDropped() {
        EditorFixture f = withComment();
        BodyBlock run = f.body("run");
        f.editor.pasteCode(run, afterComment(run), PASTED);

        assertNotNull(f.lastCode, "an insert after a comment must be allowed");
        int pasted = f.lastCode.indexOf(PASTED);
        int last = f.lastCode.indexOf("int last");
        assertTrue(pasted >= 0 && pasted < last,
                "the new statement must land between the comment and the statement below it:\n" + f.lastCode);
    }

    @Test
    void insertAtTheEndOfACommentedBodyLandsLast() {
        EditorFixture f = withComment();
        BodyBlock run = f.body("run");
        f.editor.pasteCode(run, run.getStatements().size(), PASTED);

        assertNotNull(f.lastCode, "an insert at the end must be allowed");
        assertTrue(f.lastCode.indexOf(PASTED) > f.lastCode.indexOf("int last"),
                "the new statement must land after the last one:\n" + f.lastCode);
    }
}
