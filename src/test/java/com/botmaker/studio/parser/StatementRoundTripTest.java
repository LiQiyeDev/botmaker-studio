package com.botmaker.studio.parser;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.Statement;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio blocks/parser MISSING 2 — every statement in a method body must reach the block tree.</b>
 * Gates <b>SP6</b> (B12).
 *
 * <p>{@code BlockConverter.dispatchStatement} is a 13-branch {@code instanceof} chain whose fall-through is
 * {@code Optional.empty()}, and every caller drops an empty. Java has more statement kinds than thirteen, so a
 * method containing a {@code throw}, {@code synchronized}, {@code assert}, a labelled statement or a classic
 * three-part {@code for} renders with that statement <em>absent from the editor</em> while it sits untouched
 * in the file.
 *
 * <p>The repo already diagnosed this defect on the expression side and wrote the diagnosis down — see
 * {@code blocks/expr/UnknownExpressionBlock}'s javadoc, which explains that a silently dropped node is worse
 * than a display bug because the blocks are also the <em>write</em> model: an edit can write the invisible
 * code out of existence. SP6 is that same fix on the statement side.
 *
 * <p>The assertion below is therefore not "these render nicely" but the weaker, non-negotiable one: <b>a
 * statement that is in the source has a block</b>. What the block looks like is SP6's choice.
 */
class StatementRoundTripTest {

    /** One statement kind, as it appears at method-body level. */
    private record Kind(String name, String snippet) {}

    private static String sourceFor(Kind kind) {
        return "package com.mybot;\n"
                + "public class Subject {\n"
                + "    private final Object lock = new Object();\n"
                + "    public void run() throws Exception {\n"
                + "        " + kind.snippet() + "\n"
                + "    }\n"
                + "}\n";
    }

    /** The thirteen kinds {@code dispatchStatement} names — the control group. */
    private static List<Kind> modelled() {
        return List.of(
                new Kind("block", "{ int nested = 1; }"),
                new Kind("variable declaration", "int x = 1;"),
                new Kind("if", "if (true) { int a = 1; }"),
                new Kind("while", "while (false) { int b = 1; }"),
                new Kind("enhanced for", "for (String s : new String[0]) { int c = 1; }"),
                new Kind("do-while", "do { int d = 1; } while (false);"),
                new Kind("switch", "switch (1) { case 1: break; default: break; }"),
                new Kind("expression statement", "System.out.println(\"hi\");"),
                new Kind("enum declaration", "enum Local { A, B }"),
                new Kind("wait (the generated try/sleep template)",
                        "try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }"));
    }

    /**
     * The kinds that fall through. Each is ordinary Java a user can paste in from anywhere.
     *
     * <p>The last two are <b>not</b> in B12's original list, which was derived by reading
     * {@code dispatchStatement}'s thirteen branches. They were found by running this test, and they matter
     * more than the five that were: {@code parseTry} returns {@code Optional.empty()} for every try that is
     * not the generated {@code Wait} template, so an ordinary {@code try/catch} — the single most common
     * "exotic" statement in real bot code, and what the SDK's own examples use — disappears from the editor.
     * A local {@code class} does the same through {@code parseTypeDeclaration}, which handles only enums.
     */
    private static List<Kind> unmodelled() {
        return List.of(
                new Kind("throw", "throw new IllegalStateException(\"stop\");"),
                new Kind("synchronized", "synchronized (lock) { int f = 1; }"),
                new Kind("assert", "assert 1 > 0 : \"impossible\";"),
                new Kind("labelled", "outer: while (false) { break outer; }"),
                new Kind("classic for", "for (int i = 0; i < 3; i++) { int g = 1; }"),
                new Kind("plain try/catch", "try { int e = 1; } catch (RuntimeException ex) { }"),
                new Kind("local class declaration", "class Local { }"));
    }

    /**
     * Whether some block in {@code run()}'s body is the block <em>for</em> the statement in the fixture.
     *
     * <p>Matched by containment rather than node identity: a block's {@code astNode} is not always the
     * {@code Statement} it was built from — {@code MethodInvocationBlock} holds the inner
     * {@code MethodInvocation} — and this test is about whether the statement reached the tree at all, not
     * about which node a block chose to anchor on.
     */
    private static void assertEveryStatementHasABlock(Kind kind) {
        EditorFixture f = new EditorFixture(sourceFor(kind));
        BodyBlock body = f.body("run");
        Block astBody = (Block) body.getAstNode();

        List<Statement> statements = new ArrayList<>();
        for (Object o : astBody.statements()) statements.add((Statement) o);
        assertEquals(1, statements.size(), "fixture for '" + kind.name() + "' should hold exactly one statement");

        Statement subject = statements.get(0);
        CodeBlock found = null;
        for (CodeBlock child : body.getStatements()) {
            for (var n = child.getAstNode(); n != null; n = n.getParent()) {
                if (n == subject) found = child;
            }
        }

        assertNotNull(found,
                "a '" + kind.name() + "' statement produced no block. It is in the file and invisible in the "
                        + "editor — and because the block tree is also the write model, the next edit to this "
                        + "method can write it out of existence. Source was:\n  " + kind.snippet());
    }

    // ---- What already holds ----

    @Test
    void everyModelledStatementKindProducesABlock() {
        assertAll(modelled().stream().map(k -> (org.junit.jupiter.api.function.Executable)
                () -> assertEveryStatementHasABlock(k)).toList());
    }

    /**
     * The converter never silently loses a <em>method</em>: the drop is specific to statement dispatch. Pinned
     * so a green run of the disabled test below can't be mistaken for the converter having become lenient
     * everywhere.
     */
    @Test
    void aMethodBodyWithNoStatementsStillProducesABody() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() { }
                }
                """);
        assertTrue(f.body("run").getStatements().isEmpty(), "an empty body has no children, and that is fine");
    }

    // ---- What SP6 must fix ----

    /**
     * All five in one test because SP6 fixes them with one change — a fall-through
     * {@code UnknownStatementBlock} mirroring the expression side — so they go green together.
     */
    @Test
    @Disabled("B12 is unfixed: verified red on this commit — throw/synchronized/assert/labelled/classic-for "
            + "fall through dispatchStatement, and plain try/catch and local classes are dropped by "
            + "parseTry/parseTypeDeclaration. Delete this line in Phase 4 with SP6.")
    void everyUnmodelledStatementKindStillProducesABlock() {
        assertAll(unmodelled().stream().map(k -> (org.junit.jupiter.api.function.Executable)
                () -> assertEveryStatementHasABlock(k)).toList());
    }
}
