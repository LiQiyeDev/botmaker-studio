package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.blocks.expr.UnknownExpressionBlock;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the inline variable dropdown claims. The detection is a shape question about the expression already in
 * the slot — no scene graph, no project — so it is pinned here rather than through the registry.
 */
class VariablePickerMatchTest {

    /** The first argument of the single call in {@code body}, wrapped the way a slot wraps it. */
    private static ExpressionBlock firstArgument(String body) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(("class T { void run() {" + body + "} }").toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        MethodDeclaration run = ((TypeDeclaration) unit.types().getFirst()).getMethods()[0];
        @SuppressWarnings("unchecked")
        List<Statement> statements = run.getBody().statements();
        org.eclipse.jdt.core.dom.MethodInvocation call =
                (org.eclipse.jdt.core.dom.MethodInvocation) ((ExpressionStatement) statements.getFirst()).getExpression();
        // Any expression block will do: the picker asks the AST node what it is, not the block.
        return new UnknownExpressionBlock("arg", (Expression) call.arguments().getFirst());
    }

    @Test
    void aSlotHoldingAProjectVariableIsClaimed() {
        assertEquals("RETRIES", VariablePicker.referencedVariable(firstArgument("wait(Activities.RETRIES);")));
    }

    @Test
    void anythingElseIsLeftToTheTypeBasedPickers() {
        // A literal, a local, and a field of some other class: none of them is "which variable is this".
        assertNull(VariablePicker.referencedVariable(firstArgument("wait(3);")));
        assertNull(VariablePicker.referencedVariable(firstArgument("wait(retries);")));
        assertNull(VariablePicker.referencedVariable(firstArgument("wait(Templates.ORE);")));
    }
}
