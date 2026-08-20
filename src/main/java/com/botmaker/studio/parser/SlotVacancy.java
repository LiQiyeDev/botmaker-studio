package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * The two questions an expression has to answer to <em>leave</em> the slot it sits in: what its old slot
 * becomes, and whether it can stand as a line of its own.
 *
 * <p>Both used to be nobody's: expressions could be dropped into a slot and never dragged back out, so the
 * editor never had to say what an emptied slot looks like. It cannot be "nothing" in general — {@code if ()}
 * does not parse, and {@link CodeEditor} would refuse the whole edit as broken source — and it cannot be a
 * backfill everywhere either, because a declaration with no initialiser and a print with no argument are
 * both ordinary things a user writes, and the editor already draws a {@code ⟨drop here⟩} region for exactly
 * those two shapes. So the rule is per-slot, and this is where it is written down rather than inferred from
 * which block classes happen to call {@code createExpressionDropZone}.
 *
 * <p>{@code CodeEditor.placeInEmptySlot} is the mirror of {@link #mayBeEmpty}: the same two shapes, read as
 * "where does a value go when this slot holds nothing". If one of them learns a third shape, so must the other.
 *
 * <p>Sentences for the refusals live in {@link com.botmaker.studio.types.SlotFit}, with the rest of the
 * drag-and-drop wording.
 */
public final class SlotVacancy {

    private SlotVacancy() {}

    /**
     * Whether the slot {@code value} occupies may simply be left empty once {@code value} is dragged out of it.
     *
     * <p>True for the two shapes the editor renders an empty slot for — a declaration's initialiser
     * ({@code int x;}) and a print's only argument ({@code BotMaker.print();}). False everywhere else, which
     * means an argument of a real SDK call, an operand, a condition: those are backfilled with the default
     * value for the type the slot expects, so dragging the call out of {@code if (Vision.sees(logo))} leaves
     * {@code if (false)} — a slot that can be clicked or dropped onto — and never source that fails to parse.
     */
    public static boolean mayBeEmpty(Expression value) {
        if (value == null) return false;
        if (value.getLocationInParent() == VariableDeclarationFragment.INITIALIZER_PROPERTY) return true;
        return value.getParent() instanceof MethodInvocation call
                && call.arguments().size() == 1
                && BlockConverter.isPrintStatement(call);
    }

    /**
     * Whether {@code value} is one of the expression forms Java lets stand alone as a statement — the gate on
     * dropping an expression onto a body, where it becomes {@code value;}.
     *
     * <p>This is the JLS's list ({@code ExpressionStatement}), not a judgement about usefulness: a call, an
     * assignment, a {@code new}, and the increment/decrement forms. A comparison or a literal is a value and
     * nothing else, and dropping one on a body is refused with
     * {@link com.botmaker.studio.types.SlotFit#NOT_A_STATEMENT} rather than written as source that will not
     * compile.
     */
    public static boolean canStandAlone(Expression value) {
        return switch (value) {
            case null -> false;
            case MethodInvocation ignored -> true;
            case SuperMethodInvocation ignored -> true;
            case ClassInstanceCreation ignored -> true;
            case Assignment ignored -> true;
            case PostfixExpression ignored -> true;
            case PrefixExpression pre -> pre.getOperator() == PrefixExpression.Operator.INCREMENT
                    || pre.getOperator() == PrefixExpression.Operator.DECREMENT;
            default -> false;
        };
    }
}
