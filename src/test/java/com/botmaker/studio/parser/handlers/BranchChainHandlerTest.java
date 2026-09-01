package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditorFixture;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading and writing a branch chain, asserted on the <b>emitted source text</b> for the writes — the same
 * rule {@code MatchesSwitchHandlerTest} was written under, and for the same reason: a rewrite can reach its
 * handler and still produce nothing when JDT mishandles a property pairing.
 *
 * <p>Every fixture below is deliberately written in a vocabulary that is <em>not</em> the SDK's, because the
 * one thing worth proving about this handler is that it never knew one. If any assertion here needed
 * {@code Matches} or {@code when} spelled in the handler, the handler would be its predecessor again.
 */
class BranchChainHandlerTest {

    private static final String CHAIN = """
            package test;
            public class Subject {
                void run() {
                    Gadget.each(THINGS, found -> {
                        found.pick(m -> m.isRed(), () -> {
                                 Log.say("red");
                             })
                             .pick(m -> m.isBlue(), () -> {
                                 Log.say("blue");
                             })
                             .fallback(() -> {
                                 Log.say("neither");
                             });
                    });
                }
            }
            """;

    private static final String ONE_BRANCH = """
            package test;
            public class Subject {
                void run() {
                    found.pick(m -> m.isRed(), () -> {
                        Log.say("red");
                    });
                }
            }
            """;

    private static final String NO_CONDITION = """
            package test;
            public class Subject {
                void run() {
                    runner.submit(() -> {
                        Log.say("go");
                    });
                }
            }
            """;

    private static final String ORDINARY_LAMBDA_CALL = """
            package test;
            public class Subject {
                void run() {
                    Gadget.each(THINGS, found -> {
                        Log.say("once");
                    });
                }
            }
            """;

    // ---- finding the nodes ------------------------------------------------------------------------------

    /** The outermost invocation of the first expression statement that is a chain, or the first one at all. */
    private static MethodInvocation firstCall(EditorFixture fixture) {
        List<MethodInvocation> calls = new ArrayList<>();
        fixture.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override
            public boolean visit(ExpressionStatement node) {
                if (node.getExpression() instanceof MethodInvocation mi) calls.add(mi);
                return true;
            }
        });
        assertFalse(calls.isEmpty(), "fixture should contain a call statement");
        return calls.stream().filter(BranchChainHandler::isBranchChain).findFirst().orElse(calls.getFirst());
    }

    private static ExpressionStatement chainStatement(EditorFixture fixture) {
        return (ExpressionStatement) firstCall(fixture).getParent();
    }

    /** Source with every space removed — the assertions are about structure, not JDT's indentation. */
    private static String dense(String code) {
        return code.replaceAll("\\s+", "");
    }

    // ---- reading ---------------------------------------------------------------------------------------

    @Test
    void aChainIsReadInSourceOrderWithItsFallbackLast() {
        List<BranchChainHandler.Link> links = BranchChainHandler.read(firstCall(new EditorFixture(CHAIN)));

        assertAll(
                () -> assertEquals(3, links.size()),
                () -> assertEquals(List.of("pick", "pick", "fallback"),
                        links.stream().map(BranchChainHandler.Link::method).toList()),
                () -> assertEquals(List.of(false, false, true),
                        links.stream().map(BranchChainHandler.Link::isTerminal).toList(),
                        "a link with no condition is the chain's fallback"));
    }

    @Test
    void theSubjectIsWhateverTheChainIsCalledOn() {
        assertEquals("found", BranchChainHandler.subjectOf(firstCall(new EditorFixture(CHAIN))));
    }

    @Test
    void aConditionIsTheLambdaBodyAndNotTheLambda() {
        List<BranchChainHandler.Link> links = BranchChainHandler.read(firstCall(new EditorFixture(CHAIN)));

        assertAll(
                () -> assertEquals("m.isRed()", links.getFirst().conditionExpression().toString(),
                        "the user edits the test, not the lambda that carries it"),
                () -> assertEquals("m", links.getFirst().conditionParam()),
                () -> assertEquals(null, links.getLast().conditionExpression(),
                        "a fallback has nothing to test"));
    }

    /**
     * The clause that keeps every ordinary one-lambda call rendering as it always did. Without it,
     * {@code runner.submit(() -> {…})} would become a one-link chain with nothing to branch on.
     */
    @Test
    void aCallWithNoConditionIsNotAChain() {
        assertFalse(BranchChainHandler.isBranchChain(firstCall(new EditorFixture(NO_CONDITION))));
    }

    /** A leading non-lambda argument means the call is a body-carrying call, not a link. */
    @Test
    void anOrdinaryLambdaCallIsNotAChain() {
        assertFalse(BranchChainHandler.isBranchChain(firstCall(new EditorFixture(ORDINARY_LAMBDA_CALL))));
    }

    @Test
    void aSingleTestingLinkIsAChain() {
        assertTrue(BranchChainHandler.isBranchChain(firstCall(new EditorFixture(ONE_BRANCH))));
    }

    // ---- writing ---------------------------------------------------------------------------------------

    @Test
    void addingABranchCopiesTheChainsOwnMethodNameAndSeedsFalse() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.addBranchLink(chainStatement(fixture), 1);

        String out = dense(fixture.lastCode);
        assertAll(
                () -> assertTrue(out.contains("pick(m->false,()->{})"),
                        "a new branch is seeded false so it cannot silently shadow the ones after it"),
                () -> assertTrue(out.contains("m.isRed()") && out.contains("m.isBlue()"),
                        "the branches that were there keep their tests"),
                () -> assertTrue(out.contains("fallback"), "the fallback survives the insert"));
    }

    @Test
    void theNewBranchLandsAfterTheOneItWasAddedFrom() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.addBranchLink(chainStatement(fixture), 0);

        String out = dense(fixture.lastCode);
        int red = out.indexOf("m.isRed()");
        int seeded = out.indexOf("m->false");
        int blue = out.indexOf("m.isBlue()");
        assertTrue(red < seeded && seeded < blue,
                "inserted between the branch it came from and the next one, not appended at the end");
    }

    /**
     * The parameter of a seeded condition copies one already in the chain, so every branch reads the same.
     * It is not derived from the subject's type, which is exactly what this handler refuses to know.
     */
    @Test
    void aSeededConditionBorrowsTheNameTheChainAlreadyUses() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.addBranchLink(chainStatement(fixture), 0);

        assertFalse(dense(fixture.lastCode).contains("it->false"),
                "'it' is the fallback for a chain with no name to copy, not the first choice");
    }

    @Test
    void removingABranchClosesTheChainOverIt() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.removeBranchLink(chainStatement(fixture), 1);

        String out = dense(fixture.lastCode);
        assertAll(
                () -> assertFalse(out.contains("m.isBlue()"), "the removed branch is gone"),
                () -> assertTrue(out.contains("found.pick(m->m.isRed()"),
                        "the survivor is still called on the subject"),
                () -> assertTrue(out.contains("fallback"), "and still carries the fallback after it"));
    }

    @Test
    void removingTheFallbackLeavesAChainThatStillCompiles() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.removeBranchLink(chainStatement(fixture), 2);

        String out = dense(fixture.lastCode);
        assertAll(
                () -> assertFalse(out.contains("fallback")),
                () -> assertTrue(out.contains("m.isRed()") && out.contains("m.isBlue()")));
    }

    /**
     * The last link cannot go: what would be left is a bare subject expression as a statement, which does not
     * compile. Deleting the whole statement is the block's own delete button, not this.
     */
    @Test
    void theLastLinkIsRefusedRatherThanLeavingABareExpression() {
        EditorFixture fixture = new EditorFixture(ONE_BRANCH);
        fixture.editor.removeBranchLink(chainStatement(fixture), 0);

        assertRefused(fixture, ONE_BRANCH);
    }

    @Test
    void anOutOfRangeIndexWritesNothing() {
        EditorFixture fixture = new EditorFixture(CHAIN);
        fixture.editor.addBranchLink(chainStatement(fixture), 9);

        assertRefused(fixture, CHAIN);
    }

    /**
     * A refused edit changed neither the buffer nor anything the canvas was told about.
     *
     * <p>Both halves are asserted because either alone passes for the wrong reason: {@code lastCode} is only
     * ever set by a {@code CodeUpdatedEvent}, so a refusal leaves it {@code null} — comparing it against
     * itself would "pass" on a handler that never ran at all — while the buffer alone would miss an edit
     * that published a rewrite and then failed to apply it.
     */
    private static void assertRefused(EditorFixture fixture, String original) {
        assertAll(
                () -> assertNull(fixture.lastCode, "a refused edit publishes no code update"),
                () -> assertEquals(dense(original), dense(fixture.state.getCurrentCode()),
                        "the buffer is untouched"));
    }
}
