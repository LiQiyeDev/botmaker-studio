package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.parser.factories.UnfilledSlot;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NullLiteral;

/**
 * A {@code null} in the source — in one of two quite different roles, which this block used to conflate.
 *
 * <p>Where a value can stand, {@code null} <em>is</em> the value: {@code FlowDriver}'s {@code String node = null;}
 * and its {@code return null;} are the flow's own "nowhere to go", and an author is entitled to write one.
 * Rendering those as a red "fill me in" button both invited an edit the file would refuse and made
 * {@code BlockValidator} abort the run over code nobody may change.
 *
 * <p>Where a <em>name</em> has to stand — an assignment target, a switch subject — no literal compiles, so
 * {@code null} is genuinely a hole, and that is the only case that keeps the red dashed prompt. Which is which
 * is {@link UnfilledSlot}'s answer, not this block's.
 */
public class NullBlock extends AbstractExpressionBlock {

    public NullBlock(String id, NullLiteral astNode) {
        super(id, astNode);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        if (isReadOnly()) {
            Label literal = new Label("null");
            literal.getStyleClass().add("null-block-literal");
            return literal;
        }

        boolean unfilled = UnfilledSlot.isUnfilled(this.astNode);

        // Unfilled: red and dashed, so it is obvious — before any compile — that the slot still needs a name.
        // A real null: styled flat, so it reads as the literal it is; clicking either one opens the same
        // type-aware picker, because replacing a null with something better is always a legitimate edit.
        Button button = new Button(unfilled ? "Choose a variable…" : "null");
        button.getStyleClass().add(unfilled ? "null-block-button" : "null-block-literal");

        button.setOnAction(e -> {
            ResolvedType expected = ProjectAnalyzer.inferExpectedType(this.astNode);
            showExpressionMenuAndReplace(button, context, expected, (Expression) this.astNode);
        });

        return button;
    }
}
