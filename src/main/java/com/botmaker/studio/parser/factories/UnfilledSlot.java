package com.botmaker.studio.parser.factories;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;

/**
 * The one answer to "is this {@code null} a hole the user still has to fill, or a value they meant?".
 *
 * <p><b>Why the question got harder.</b> A dropped block used to leave {@code null} in every position it
 * couldn't seed, so {@code null} meant "unfilled" and the check was {@code instanceof NullLiteral}. That is no
 * longer true in either direction. A slot with a known type is now seeded with a value that compiles — see
 * {@link InitializerFactory#createDefaultInitializer}, which is where "what does a {@code T} start as?" is
 * answered and has been for a while — so an unfilled slot is the exception rather than the rule. And the old
 * check had the converse wrong all along: a {@code null} the user (or the Studio's own generator) wrote on
 * purpose was flagged as a hole, which is why opening {@code FlowDriver.java} — {@code String node = null;} and
 * {@code return null;} in a file nobody may edit — and pressing Run aborted the run with "fill in the
 * highlighted empty value(s)" and no way to comply.
 *
 * <p><b>The rule.</b> A {@code null} is unfilled only where a <em>value cannot go</em> — the three positions
 * that need the <em>name</em> of something already in scope, and so cannot be seeded with a literal at all:
 * <ul>
 *   <li>the left-hand side of an assignment (an lvalue is a name, never a value),</li>
 *   <li>the subject of a {@code switch} — including the {@code Matches} pattern switch, whose subject is the
 *       value a group lambda is handed,</li>
 *   <li>the iterated expression of an enhanced {@code for}.</li>
 * </ul>
 * Everywhere else — an argument, an initializer, a {@code return} — {@code null} is a value, and the user is
 * entitled to write it.
 */
public final class UnfilledSlot {

    private UnfilledSlot() {}

    /**
     * The placeholder for a slot that needs a name from the drop site's scope and found none. Prefer seeding a
     * real value ({@link InitializerFactory#createDefaultInitializer}); reach for this only in the three
     * positions {@link #isUnfilled} lists, where no value would compile.
     */
    public static org.eclipse.jdt.core.dom.Expression of(org.eclipse.jdt.core.dom.AST ast) {
        return ast.newNullLiteral();
    }

    /** True when {@code node} is a {@code null} standing in for a name the user still has to choose. */
    public static boolean isUnfilled(ASTNode node) {
        if (!(node instanceof NullLiteral)) return false;
        return switch (node.getParent()) {
            case Assignment assignment -> assignment.getLeftHandSide() == node;
            case SwitchStatement switchStatement -> switchStatement.getExpression() == node;
            case SwitchExpression switchExpression -> switchExpression.getExpression() == node;
            case EnhancedForStatement enhancedFor -> enhancedFor.getExpression() == node;
            case null, default -> false;
        };
    }
}
