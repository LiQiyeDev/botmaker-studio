package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import org.eclipse.jdt.core.dom.Comment;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate between a rewrite and the canvas: source that doesn't parse is never published.
 *
 * <p>The bug behind it: a rewrite that <em>throws</em> is caught by {@code AstRewriteHelper.applyRewrite}, but
 * one that succeeds and emits broken Java sailed straight through to {@code refreshUI}, where JDT recovered a
 * mangled tree and the method rendered <b>empty</b>. Adding a block could erase the visible contents of a
 * method, with Ctrl-Z the only way back.
 *
 * <p>Driven through {@code updateComment} because it is a real raw-text splice rather than an AST rewrite —
 * a comment whose text contains {@code *&#47;} closes the comment early and drops the rest of it into the class
 * body. Any write path would do; the guard sits on the single publish point they all share.
 */
class EditGuardTest {

    /** Comment text that ends the comment it is put in, leaving a stray brace in the class body. */
    private static final String ESCAPES_THE_COMMENT = "*/\n}";

    private static String source(String comment) {
        return "package com.mybot;\npublic class Subject {\n    public static void run() {\n"
                + "        " + comment + "\n        int b = 2;\n    }\n}\n";
    }

    @Test
    void anEditThatWouldNotParseIsRefusedAndTheCodeIsUntouched() {
        EditorFixture f = new EditorFixture(source("// marker"));

        f.editor.updateComment(comment(f), ESCAPES_THE_COMMENT);

        assertNull(f.lastCode, "broken source must never reach the canvas");
        assertTrue(f.statusMessages.stream().anyMatch(m -> m.contains("would have broken the code")),
            "the refusal is user-facing — a silent no-op reads as the editor being stuck: " + f.statusMessages);
    }

    /**
     * The refusal has to name the rewrite that caused it. It didn't: {@code refusedBy} excluded the guard's
     * other frames but not its own, so the first {@code CodeEditor} frame it found was always itself and every
     * refusal in the wild logged "(refusedBy)" — useless, and undetectable from inside the feature it was
     * added to serve. Asserting on the log is the only way this stays true.
     */
    @Test
    void theRefusalNamesTheRewriteThatCausedIt() {
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EditorFixture f = new EditorFixture(source("// marker"));
            f.editor.updateComment(comment(f), ESCAPES_THE_COMMENT);
        } finally {
            System.setErr(realErr);
        }

        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("updateComment"),
            () -> "the log must name the rewrite, not the guard: " + log);
        assertFalse(log.contains("(refusedBy"),
            () -> "the guard named itself again: " + log);
    }

    @Test
    void anEditThatParsesIsPublishedAsBefore() {
        EditorFixture f = new EditorFixture(source("// marker"));

        f.editor.updateComment(comment(f), "renamed");

        assertNotNull(f.lastCode, "a valid edit must still publish");
        assertTrue(f.lastCode.contains("// renamed"), f.lastCode);
        assertTrue(f.statusMessages.isEmpty(), "nothing to report: " + f.statusMessages);
    }

    @Test
    void aFileThatIsAlreadyBrokenStaysEditable() {
        // The half that makes the guard liveable. A user mid-way through fixing a syntax error would otherwise
        // have every edit refused — including the one that fixes it — because the *result* doesn't parse either.
        // Only a newly introduced break is refused, so a file that was already broken is left entirely alone.
        EditorFixture f = new EditorFixture(source("// marker"));
        // A stray brace past the end of the class: broken, and it moves no offset the edit below depends on.
        f.state.setCurrentCode(source("// marker") + "}\n");

        f.editor.updateComment(comment(f), "renamed");

        assertNotNull(f.lastCode, "an edit to an already-broken file must land");
        assertTrue(f.lastCode.contains("// renamed"), f.lastCode);
        assertTrue(f.statusMessages.isEmpty(), "nothing to report: " + f.statusMessages);
    }

    /** The body's comment child — the node the "edit comment" affordance hands the editor. */
    private static Comment comment(EditorFixture f) {
        BodyBlock body = f.body("run");
        for (CodeBlock child : body.getStatements()) {
            if (child.getAstNode() instanceof Comment c) return c;
        }
        throw new AssertionError("fixture should contain a comment child");
    }
}
