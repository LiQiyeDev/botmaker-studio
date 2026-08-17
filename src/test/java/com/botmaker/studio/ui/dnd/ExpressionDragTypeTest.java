package com.botmaker.studio.ui.dnd;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a dragged statement advertises about the value it produces. The presence of that answer is what marks
 * a drag as able to fill an expression slot, so "no bindings" must not read as "not an expression" — that is
 * exactly what made dragging an SDK call into an {@code if} condition impossible.
 */
class ExpressionDragTypeTest {

    /** Parses without bindings, the way the editor parses a file for most of a session. */
    private static List<Statement> statementsOf(String body) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(("class T { void run() {" + body + "} }").toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        MethodDeclaration run = type.getMethods()[0];
        Block block = run.getBody();
        @SuppressWarnings("unchecked")
        List<Statement> statements = block.statements();
        return statements;
    }

    @Test
    void anExpressionStatementWithoutBindingsStillAdvertisesItselfAsDroppable() {
        String type = BlockDragAndDropManager.expressionTypeName(statementsOf("ImageClicker.click(t);").getFirst());
        assertNotNull(type, "an unresolved call must still be draggable into a slot");
        assertEquals("java.lang.Object", type, "unresolved reports the unknown type, which every slot accepts");
    }

    @Test
    void aStatementThatIsNotAnExpressionAdvertisesNothing() {
        assertNull(BlockDragAndDropManager.expressionTypeName(statementsOf("if (a) { }").getFirst()),
                "an if is not a value; dropping it into a slot has no meaning");
        assertNull(BlockDragAndDropManager.expressionTypeName(statementsOf("int x = 1;").getFirst()),
                "a declaration is not a value either");
    }
}
