package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio blocks/parser MISSING 1 — a rewrite that fails must not look like one that succeeded.</b>
 * Gates <b>SP5</b> (B11).
 *
 * <p>{@link AstRewriteHelper#applyRewrite} is the single primitive the whole block editor funnels through:
 * every one of {@code CodeEditor}'s ~40 public edit methods ends in it, directly or through a
 * {@code parser/handlers} transform. Its failure exit returns the <em>original</em> code:
 *
 * <pre>{@code
 * } catch (Exception e) {
 *     e.printStackTrace();
 *     return originalCode;      // ← indistinguishable from "the edit was a no-op"
 * }
 * }</pre>
 *
 * <p>{@code CodeEditor.edit} then publishes a {@code CodeUpdatedEvent} carrying source identical to what was
 * already there. The editor re-parses it, the block tree redraws the same, and the user's drag or menu pick
 * simply does not happen — no message, no error marker, no entry in the Errors tab. The only trace is a stack
 * trace on stdout, which in a packaged app-image goes nowhere.
 *
 * <p>The convention for "nothing happened" already exists one layer up and is honoured
 * ({@link #aNullTransformIsAlreadyTreatedAsNothingHappened}): {@code edit} skips the publish when the transform
 * returns {@code null}. SP5 is one line in each of two catch blocks — this test is what makes it verifiable.
 */
class AstRewriteFailureTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int x = 1;
                    int y = 2;
                }
            }
            """;

    /**
     * A rewrite whose recorded edits cannot be applied to the document it is handed — the shape B11 names,
     * and the one {@code CodeEditor}'s own comments call routine: a node whose source positions no longer
     * match the current text, which happens whenever a sibling file leaves the project uncompiled.
     */
    private record Failing(ASTRewrite rewriter, String mismatchedCode) {}

    private static Failing failingRewrite() {
        CompilationUnit cu = com.botmaker.studio.suggestions.ProjectAnalyzer.createCompilationUnit(
                com.botmaker.studio.TestSupport.runtimeClassPath(), SOURCE,
                com.botmaker.studio.TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "fixture must parse");

        TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
        MethodDeclaration run = type.getMethods()[0];

        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.remove((org.eclipse.jdt.core.dom.Statement) run.getBody().statements().get(1), null);
        // The rewrite's offsets address SOURCE; this document is far shorter, so applying them overruns it.
        return new Failing(rewriter, "class Subject {}");
    }

    // ---- The control: a rewrite that works, so a red test below cannot be blamed on the fixture ----

    @Test
    void aRewriteThatAppliesReturnsTheChangedCode() {
        Failing f = failingRewrite();
        String result = AstRewriteHelper.applyRewrite(f.rewriter(), SOURCE);

        assertNotNull(result);
        assertNotEquals(SOURCE, result, "removing a statement must change the source");
        assertTrue(result.contains("int x = 1;"), "the untouched statement survives");
        assertTrue(!result.contains("int y = 2;"), "the removed statement is gone");
    }

    // ---- What SP5 must fix ----

    /**
     * A <b>third</b> site with B11's shape, found by running this test rather than by reading the two the bug
     * names. {@code CodeEditor.addStatement}'s private overload opens with
     * {@code if (newStatement == null) return originalCode;} — so a {@link BlockType} the factory declines to
     * build (a class member dropped into a body) publishes a {@code CodeUpdatedEvent} carrying unchanged
     * source, exactly as a thrown rewrite does.
     *
     * <p>It matters because the {@code null}-means-nothing-happened convention this bug's fix relies on is
     * declared one layer up, in {@code CodeEditor.edit} — and this path does not go through {@code edit}. SP5
     * has to change three lines, not two, or the convention stays half-true.
     */
    @Test
    @Disabled("B11 is unfixed at a third site: verified red on this commit — CodeEditor.addStatement returns "
            + "the original code when the factory builds nothing. Delete this line in Phase 4 with SP5's fix.")
    void aBlockTypeThatBuildsNoStatementPublishesNothing() {
        EditorFixture f = new EditorFixture(SOURCE);
        BlockType classOnly = new BlockType.MethodMember("METHOD", "Method", BlockCategory.FUNCTIONS);

        f.editor.addStatement(f.body("run"), classOnly, 0);

        assertNull(f.lastCode,
                "nothing was built, so nothing should have been published — instead the editor announced an "
                        + "edit carrying the source it already had");
    }

    /**
     * The primitive. A failure currently returns the original text, so no caller can tell it apart from an
     * edit that legitimately changed nothing.
     */
    @Test
    @Disabled("B11 is unfixed: verified red on this commit — applyRewrite returns the original code on "
            + "failure. Delete this line in Phase 4 with SP5's fix.")
    void aRewriteThatCannotBeAppliedReturnsNull() {
        Failing f = failingRewrite();

        String result = AstRewriteHelper.applyRewrite(f.rewriter(), f.mismatchedCode());

        assertNull(result,
                "applyRewrite returned " + (f.mismatchedCode().equals(result) ? "the original code" : "text")
                        + " for a rewrite that threw. Every caller reads that as a successful no-op edit.");
    }

    /**
     * The user-visible half. Driven through the real {@code CodeEditor} write path with the state B11
     * describes — a compilation unit whose positions no longer match the current text — so the assertion is
     * about what the editor publishes, not about the helper in isolation.
     */
    @Test
    @Disabled("B11 as above — a failed edit still publishes a CodeUpdatedEvent carrying unchanged source. "
            + "Delete this line in Phase 4 with SP5's fix.")
    void aFailedEditPublishesNothing() {
        EditorFixture f = new EditorFixture(SOURCE);
        var body = f.body("run");
        var stale = (org.eclipse.jdt.core.dom.Statement) ((org.eclipse.jdt.core.dom.Block) body.getAstNode())
                .statements().get(1);

        // The CU still describes SOURCE; the current text no longer does. This is the "sibling file is
        // uncompiled" state, not a contrived one.
        f.state.setCurrentCode("class Subject {}");

        f.editor.deleteStatement(stale);

        assertNull(f.lastCode,
                "a rewrite that threw was published as a CodeUpdatedEvent. The editor re-parses it, the "
                        + "blocks redraw identically, and the user's delete silently did not happen.");
        assertEquals("class Subject {}", f.state.getCurrentCode(), "sanity: the code itself is untouched");
    }
}
