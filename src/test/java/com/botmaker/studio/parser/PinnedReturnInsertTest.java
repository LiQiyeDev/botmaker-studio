package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import org.eclipse.jdt.core.dom.Comment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An activity's {@code run()} ends with a pinned {@code return Outcome.NEXT;} that must stay last — you may
 * insert <em>before</em> it but not after. The bug this pins down: the insertion guard compared a BodyBlock
 * <b>child</b> index (comments included) against the pinned return's <b>statements()</b> index (comments
 * excluded), so a drop between the generated {@code // TODO} comment and the return read as "after the return"
 * and was wrongly refused.
 */
class PinnedReturnInsertTest {

    private static final String PASTED = "int pasted = 99;";

    /** A stub under the activities package so it is recognised as an activity (pinned trailing return applies). */
    private static EditorFixture activityStub() {
        Path file = EditorFixture.activitiesFile("Mining.java");
        String source = "package com.mybot.activities;\n"
                + "public class Mining {\n"
                + "    public Outcome run() {\n"
                + "        // TODO: how to do Mining\n"
                + "        return Outcome.NEXT;\n"
                + "    }\n"
                + "    public enum Outcome { NEXT }\n"
                + "}\n";
        return new EditorFixture(source, file);
    }

    /** The child index just after the body's comment (i.e. between the comment and the pinned return). */
    private static int afterComment(BodyBlock body) {
        for (int i = 0; i < body.getStatements().size(); i++) {
            if (body.getStatements().get(i).getAstNode() instanceof Comment) return i + 1;
        }
        throw new AssertionError("fixture run() should contain a comment child");
    }

    @Test
    void insertBetweenCommentAndPinnedReturnIsAllowed() {
        EditorFixture f = activityStub();
        BodyBlock run = f.body("run");
        f.editor.pasteCode(run, afterComment(run), PASTED);

        assertNotNull(f.lastCode, "an insert before the pinned return must be allowed");
        int pasted = f.lastCode.indexOf(PASTED);
        int ret = f.lastCode.indexOf("return Outcome.NEXT");
        assertTrue(pasted >= 0 && pasted < ret, "the new statement must land before the pinned return");
    }

    @Test
    void insertAfterPinnedReturnIsRefused() {
        EditorFixture f = activityStub();
        BodyBlock run = f.body("run");
        // One past the return child = after the pinned return; the guard must refuse (no code update).
        f.editor.pasteCode(run, run.getStatements().size(), PASTED);
        assertNull(f.lastCode, "nothing may be inserted after the pinned return");
    }
}
