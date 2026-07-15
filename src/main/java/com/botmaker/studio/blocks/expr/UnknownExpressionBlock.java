package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.Expression;

/**
 * Fallback for an expression the block converter has no dedicated block for. Renders the expression's source
 * text verbatim, read-only.
 *
 * <p><b>Why this exists.</b> {@code BlockConverter.dispatchExpression} used to end in a bare
 * {@code return Optional.empty()}, and every caller feeds it through
 * {@code parseExpression(...).ifPresent(block::addArgument)} — so an unrecognised argument was
 * <em>silently discarded</em>. The block then rendered with fewer arguments than the source actually had
 * (this is what made {@code Bot.supervise(a, b, c)} display as {@code supervise()}), while the real arguments
 * sat untouched in the file. That is worse than a display bug: {@code MethodInvocationBlock} rewrites calls
 * from block state, so an edit could write the dropped arguments out of existence.
 *
 * <p>Emitting this block instead means an unmodelled expression is always <em>visible</em> and always
 * round-trips. If you see one in the editor, the fix is to add a real branch for that node type to
 * {@code dispatchExpression} — not to make it disappear again.
 */
public class UnknownExpressionBlock extends AbstractExpressionBlock {

    private final String sourceText;

    public UnknownExpressionBlock(String id, Expression astNode) {
        super(id, astNode);
        this.sourceText = String.valueOf(astNode);
    }

    /** The raw source of the unmodelled expression. */
    public String getSourceText() { return sourceText; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label label = new Label(sourceText);
        label.getStyleClass().add("unknown-expression-block");
        Tooltip.install(label, new Tooltip(
                "This expression has no visual block yet, so it is shown as source and can't be edited here.\n"
                        + "It is preserved exactly as written."));
        return label;
    }
}
