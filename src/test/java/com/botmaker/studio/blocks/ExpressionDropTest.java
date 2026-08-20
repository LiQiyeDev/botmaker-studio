package com.botmaker.studio.blocks;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.dnd.ExpressionDropInfo;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole drop, end to end: the drag layer publishes ids, {@code CodeEditorService} resolves them to AST
 * nodes and calls the editor. Both halves were covered — {@link com.botmaker.studio.types.TypeExpectation}
 * for the cue, {@code EmptySlotDropTest} for the rewrite — and the path between them was not, which is where
 * a drop that lit the slot green and then did nothing lived.
 */
class ExpressionDropTest {

    private EditorFixture fx;

    private void open(String source) {
        fx = new EditorFixture(source);
        fx.context();   // wires the drop subscription
    }

    private void drop(ASTNode slot, ASTNode source) {
        fx.context().getEventBus().publish(new CoreApplicationEvents.ExpressionDropRequestedEvent(
                ExpressionDropInfo.fromExistingBlock(idOf(slot), idOf(source))));
    }

    private void dropIntoEmptySlot(ASTNode owner, ASTNode source) {
        fx.context().getEventBus().publish(new CoreApplicationEvents.ExpressionDropRequestedEvent(
                ExpressionDropInfo.intoEmptySlot(idOf(owner), null, idOf(source))));
    }

    /** The block id the drag layer would have put on the dragboard for {@code node}. */
    private String idOf(ASTNode node) {
        CodeBlock block = fx.state.getNodeToBlockMap().get(node);
        assertNotNull(block, "no block for " + node);
        return block.getId();
    }

    private List<Statement> statements() {
        CompilationUnit unit = fx.state.getCompilationUnit().orElseThrow();
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        MethodDeclaration method = type.getMethods()[0];
        Block body = method.getBody();
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();
        return statements;
    }

    private static final String WITH_IF = """
            package test;

            public class Subject {
                public void run() {
                    if (true) {
                    }
                    "abc".isEmpty();
                    "abc".trim();
                }
            }
            """;

    @Test
    void aValueDroppedOnAFilledSlotReplacesIt() {
        open(WITH_IF);
        Expression condition = ((IfStatement) statements().getFirst()).getExpression();
        drop(condition, statements().get(1));

        assertNotNull(fx.lastCode, "the drop should have produced a code update " + fx.statusMessages);
        assertTrue(fx.lastCode.contains("if (\"abc\".isEmpty())"), fx.lastCode);
        assertFalse(fx.lastCode.contains("\"abc\".isEmpty();"),
                "the statement the value came from is consumed:\n" + fx.lastCode);
    }

    @Test
    void aVoidLineIsRefusedOutLoudRatherThanSilently() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        if (true) {
                        }
                        System.out.println("hi");
                    }
                }
                """);
        Expression condition = ((IfStatement) statements().getFirst()).getExpression();
        drop(condition, statements().get(1));

        assertNull(fx.lastCode, "a void call cannot fill a condition:\n" + fx.lastCode);
        assertFalse(fx.statusMessages.isEmpty(), "and the refusal has to say so");
    }

    @Test
    void aValueOfTheWrongTypeIsRefusedOutLoud() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        if (true) {
                        }
                        "abc".length();
                    }
                }
                """);
        Expression condition = ((IfStatement) statements().getFirst()).getExpression();
        drop(condition, statements().get(1));

        assertNull(fx.lastCode, "a number is not a condition:\n" + fx.lastCode);
        assertFalse(fx.statusMessages.isEmpty(), "and the refusal has to say so");
    }

    @Test
    void anEmptySlotTakesTheDroppedValueThroughTheSamePath() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        System.out.println();
                        "abc".trim();
                    }
                }
                """);
        dropIntoEmptySlot(statements().getFirst(), statements().get(1));

        assertNotNull(fx.lastCode, "the drop should have produced a code update " + fx.statusMessages);
        assertTrue(fx.lastCode.contains("System.out.println(\"abc\".trim());"), fx.lastCode);
    }

    @Test
    void aStatementDroppedIntoItsOwnSlotIsRefusedOutLoud() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        System.out.println("hi");
                    }
                }
                """);
        ExpressionStatement stmt = (ExpressionStatement) statements().getFirst();
        Expression argument = (Expression) ((org.eclipse.jdt.core.dom.MethodInvocation)
                stmt.getExpression()).arguments().getFirst();
        drop(argument, stmt);

        assertNull(fx.lastCode, "consuming the statement the slot lives in would delete the slot");
        assertFalse(fx.statusMessages.isEmpty(), "and the refusal has to say so");
    }
}
