package com.botmaker.studio.parser.factories;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which {@code null}s are holes and which are values.
 *
 * <p>The rule used to be "every {@code NullLiteral}", and it was wrong in both directions at once. It called a
 * deliberate {@code return null;} an error — so opening the generated {@code FlowDriver.java}, which the user
 * cannot edit, and pressing Run aborted the run with an instruction impossible to follow — while the positions
 * that really are holes were the ones being seeded with {@code null} in the first place, producing
 * {@code switch (null)} and {@code for (var item : null)}: not "incomplete", just uncompilable.
 *
 * <p>Built node by node rather than parsed on purpose: {@code null = 1;} is not valid Java, so the very shape
 * this class has to recognise is one no parser will hand back.
 */
class UnfilledSlotTest {

    private static AST ast() {
        return AST.newAST(AST.getJLSLatest(), true);
    }

    @Test
    void anAssignmentTargetIsAHole() {
        AST ast = ast();
        Assignment assignment = ast.newAssignment();
        NullLiteral target = ast.newNullLiteral();
        assignment.setLeftHandSide(target);
        assignment.setRightHandSide(ast.newNumberLiteral("1"));

        assertTrue(UnfilledSlot.isUnfilled(target));
    }

    @Test
    void butTheValueBeingAssignedIsNot() {
        // `x = null` is ordinary Java. Only the left of the `=` needs a name.
        AST ast = ast();
        Assignment assignment = ast.newAssignment();
        assignment.setLeftHandSide(ast.newSimpleName("x"));
        NullLiteral value = ast.newNullLiteral();
        assignment.setRightHandSide(value);

        assertFalse(UnfilledSlot.isUnfilled(value));
    }

    @Test
    void aSwitchSubjectAndAnIteratedExpressionAreHoles() {
        AST ast = ast();
        SwitchStatement switchStatement = ast.newSwitchStatement();
        NullLiteral subject = ast.newNullLiteral();
        switchStatement.setExpression(subject);

        EnhancedForStatement enhancedFor = ast.newEnhancedForStatement();
        SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
        parameter.setType(ast.newSimpleType(ast.newSimpleName("String")));
        parameter.setName(ast.newSimpleName("item"));
        enhancedFor.setParameter(parameter);
        NullLiteral iterated = ast.newNullLiteral();
        enhancedFor.setExpression(iterated);
        enhancedFor.setBody(ast.newBlock());

        assertTrue(UnfilledSlot.isUnfilled(subject));
        assertTrue(UnfilledSlot.isUnfilled(iterated));
    }

    @Test
    void theNullsGeneratedCodeWritesOnPurposeAreValues() {
        // FlowDriver's two, exactly: `String node = null;` and `return null;`. Neither is the user's to fill,
        // and calling them errors is what aborted every run started from that file.
        AST ast = ast();
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName("node"));
        NullLiteral initializer = ast.newNullLiteral();
        fragment.setInitializer(initializer);

        ReturnStatement returnStatement = ast.newReturnStatement();
        NullLiteral returned = ast.newNullLiteral();
        returnStatement.setExpression(returned);

        assertFalse(UnfilledSlot.isUnfilled(initializer));
        assertFalse(UnfilledSlot.isUnfilled(returned));
    }

    @Test
    void soIsAnArgumentDeliberatelyPassedAsNull() {
        AST ast = ast();
        MethodInvocation call = ast.newMethodInvocation();
        call.setName(ast.newSimpleName("accept"));
        NullLiteral argument = ast.newNullLiteral();
        call.arguments().add(argument);

        assertFalse(UnfilledSlot.isUnfilled(argument));
    }

    @Test
    void andSoIsANullWithNoParentAtAll() {
        assertFalse(UnfilledSlot.isUnfilled(ast().newNullLiteral()));
        assertFalse(UnfilledSlot.isUnfilled(null));
    }
}
