package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;

/**
 * What a block's AST node <em>is</em>, when the block and the statement it draws are not the same node.
 *
 * <p>A block holds one {@code ASTNode}, and for a line that is a bare call the two candidates are both
 * defensible: {@code ClickBlock} holds the {@link ExpressionStatement}, while {@code MethodInvocationBlock}
 * holds the {@code MethodInvocation} inside it — the registry maps the statement node to the latter, so the
 * same drop arrived carrying either shape depending on which block class drew the line.
 *
 * <p>That was the "the cue is green and the drop does nothing" bug: every path that fills a slot asks
 * {@code instanceof ExpressionStatement} and half the blocks in the editor answered no, so the drag layer
 * advertised no type at all and the service returned without a word. Normalising here is the fix, and it lives
 * outside both so the drag side and the drop side cannot disagree about it again.
 */
public final class BlockNodes {

    private BlockNodes() {}

    /**
     * The statement {@code node} stands for — itself when it already is one, the statement around it when it
     * is that statement's whole expression — or null when the node is neither (an argument, a condition, a
     * loop, a declaration).
     */
    public static ExpressionStatement expressionStatementOf(ASTNode node) {
        if (node instanceof ExpressionStatement statement) return statement;
        if (node instanceof Expression expression
                && expression.getParent() instanceof ExpressionStatement statement
                && statement.getExpression() == expression) {
            return statement;
        }
        return null;
    }
}
