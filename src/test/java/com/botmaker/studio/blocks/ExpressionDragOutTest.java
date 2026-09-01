package com.botmaker.studio.blocks;

import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dragging a value <em>out</em> of the slot it sits in — the direction the editor never had.
 *
 * <p>What is pinned here is the half a user never asks for and always notices: what the vacated slot becomes.
 * It cannot be a hole in general ({@code if ()} does not parse, and the guard would refuse the whole edit), and
 * it cannot be a backfill everywhere either, or a print the user emptied on purpose would refill itself. The
 * rule is {@code SlotVacancy}'s, and these are its two answers seen from the outside.
 */
class ExpressionDragOutTest {

    private EditorFixture fx;

    private void open(String source) {
        fx = new EditorFixture(source);
    }

    private List<Statement> statements(String methodName) {
        CompilationUnit unit = fx.state.getCompilationUnit().orElseThrow();
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getName().getIdentifier().equals(methodName)) {
                @SuppressWarnings("unchecked")
                List<Statement> body = method.getBody().statements();
                return body;
            }
        }
        throw new AssertionError("no method " + methodName);
    }

    @Test
    void aConditionIsBackfilledWhenItsCallIsDraggedOntoTheBody() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        if (ready()) {
                            int kept = 1;
                        }
                    }

                    boolean ready() { return true; }
                }
                """);
        Expression condition = ((IfStatement) statements("run").getFirst()).getExpression();

        fx.editor.moveExpressionToStatement(condition, fx.body("run"), 0);

        assertNotNull(fx.lastCode, "the drag-out should have produced a code update");
        assertTrue(fx.lastCode.contains("ready();"),
                "the value becomes a line of its own:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("if (false)"),
                "a condition cannot be empty, so it takes the default for its type:\n" + fx.lastCode);
    }

    @Test
    void aPrintArgumentDraggedOutLeavesTheSlotEmpty() {
        // The one call shape the editor draws an empty slot for. Backfilling it would put a value back the
        // moment the user took one away.
        open("""
                package test;

                public class Subject {
                    public void run() {
                        System.out.println(name());
                    }

                    String name() { return "x"; }
                }
                """);
        MethodInvocation print = (MethodInvocation) ((ExpressionStatement) statements("run").getFirst()).getExpression();

        fx.editor.moveExpressionToStatement((Expression) print.arguments().getFirst(), fx.body("run"), 1);

        assertNotNull(fx.lastCode, "the drag-out should have produced a code update");
        assertTrue(fx.lastCode.contains("System.out.println();"),
                "the print keeps its empty slot:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("name();"), fx.lastCode);
    }

    @Test
    void anEmptiedPrintCanBeFilledFromItsMenuAgain() {
        // The other half of the test above, and the half that was missing: a slot you can empty and not refill
        // is a one-way door. The menu path went through a replace-this-node write, so with no node to replace
        // every pick was discarded in silence — the print could only be refilled by dropping something on it.
        open("""
                package test;

                public class Subject {
                    public void run() {
                        System.out.println();
                    }

                    String name() { return "x"; }
                }
                """);
        ExpressionStatement print = (ExpressionStatement) statements("run").getFirst();

        fx.editor.fillEmptySlotFromSelection(print,
                new ExpressionChoice.Method("", "name", List.of(), false), ResolvedType.UNKNOWN);

        assertNotNull(fx.lastCode, "filling the empty slot should have produced a code update");
        assertTrue(fx.lastCode.contains("System.out.println(name())"),
                "the pick lands in the hole:\n" + fx.lastCode);
    }

    @Test
    void anInitialiserDraggedIntoAnotherSlotLeavesTheDeclarationBare() {
        open("""
                package test;

                public class Subject {
                    public void run() {
                        String name = greeting();
                        System.out.println("hello");
                    }

                    String greeting() { return "hi"; }
                }
                """);
        VariableDeclarationFragment fragment =
                (VariableDeclarationFragment) ((VariableDeclarationStatement) statements("run").getFirst())
                        .fragments().getFirst();
        MethodInvocation print = (MethodInvocation) ((ExpressionStatement) statements("run").get(1)).getExpression();

        fx.editor.moveExpressionBetweenSlots((Expression) print.arguments().getFirst(), fragment.getInitializer());

        assertNotNull(fx.lastCode, "the move should have produced a code update");
        assertTrue(fx.lastCode.contains("System.out.println(greeting());"),
                "the target slot takes the value:\n" + fx.lastCode);
        assertTrue(fx.lastCode.contains("String name;"),
                "a declaration may have no initialiser, so the slot it left is empty:\n" + fx.lastCode);
    }

    @Test
    void aValueThatIsNotAStatementIsRefusedByTheWritePath() {
        // The drag layer refuses these during drag-over; this is the second door, and it is the one that has to
        // hold, since a comparison written as a line would not compile.
        open("""
                package test;

                public class Subject {
                    public void run() {
                        if (1 < 2) {
                            int kept = 1;
                        }
                    }
                }
                """);
        Expression condition = ((IfStatement) statements("run").getFirst()).getExpression();

        fx.editor.moveExpressionToStatement(condition, fx.body("run"), 0);

        assertFalse(fx.lastCode != null && fx.lastCode.contains("1 < 2;"),
                "a comparison is a value and nothing else:\n" + fx.lastCode);
    }
}
