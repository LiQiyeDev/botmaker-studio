package com.botmaker.studio.core;

import com.botmaker.studio.ui.render.menu.ExpressionMenu;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.PlaceholderComponents;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.function.Predicate;

public abstract class AbstractStatementBlock extends AbstractCodeBlock implements StatementBlock {
    public AbstractStatementBlock(String id, ASTNode astNode) {
        super(id, astNode);
    }

    // --- Helpers used by subclasses ---
    //
    // The factories below return null when this block is read-only, and the layout builders skip null nodes.
    // That is the whole read-only rendering rule: a locked block does not get a disabled button, it gets no
    // button. Every subclass inherits it without having to remember, which is the point — the previous design
    // relied on ~20 block classes each checking isReadOnly(), and the ones that forgot were the bug.

    /** The delete button, or null when this block may not be deleted. */
    protected Button createDeleteButton(CodeEditorService context) {
        if (isReadOnly()) return null;
        return BlockUIComponents.createDeleteButton(() ->
                context.getCodeEditor().deleteStatement((Statement) this.astNode)
        );
    }

    protected Label createKeywordLabel(String text) {
        return BlockUIComponents.createKeywordLabel(text);
    }

    protected HBox createStandardHeader(CodeEditorService context, Node... content) {
        HBox container = BlockUIComponents.createHeaderRow(
                deleteAction(context),
                content
        );
        container.getStyleClass().add("statement-block-header");
        return container;
    }

    /** What deleting this block does, or null when it may not be deleted (builders then omit the control). */
    protected Runnable deleteAction(CodeEditorService context) {
        if (isReadOnly()) return null;
        return () -> context.getCodeEditor().deleteStatement((Statement) this.astNode);
    }

    /** The "+" button, or null when this block is read-only. */
    protected Button createAddButton(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        if (isReadOnly()) return null;
        return BlockUIComponents.createAddButton(handler);
    }

    protected javafx.scene.layout.VBox createIndentedBody(com.botmaker.studio.core.BodyBlock body, CodeEditorService context, String styleClass) {
        Node bodyNode = (body != null) ? body.getUINode(context) : null;
        return LayoutComponents.createIndentedBody(bodyNode, styleClass);
    }

    protected Node getOrDropZone(com.botmaker.studio.core.ExpressionBlock expr, CodeEditorService context) {
        return PlaceholderComponents.createExpressionOrDropZone(
                expr,
                context,
                () -> createExpressionDropZone(context)
        );
    }

    protected javafx.scene.layout.HBox createSentence(Node... nodes) {
        return LayoutComponents.createSentenceRow(nodes);
    }

    // --- Expression Menu Helpers (Overloaded for convenience) ---

    // 1. Default (Allow all)
    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace) {
        showExpressionMenuAndReplace(button, context, targetType, toReplace, x -> true);
    }

    // 2. With Filter
    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace,
                                                Predicate<ExpressionType> filter) {
        // A read-only block builds no button to open this, so reaching here means some path forgot. Refuse
        // rather than show a menu whose every pick the write layer will reject.
        if (isReadOnly()) return;

        ContextMenu menu = ExpressionMenu.create(
                targetType,
                false,
                context,
                this.astNode, // Use this block's AST node as context
                filter,
                selection -> applyExpressionSelection(context, toReplace, selection)
        );
        menu.show(button, javafx.geometry.Side.BOTTOM, 0, 0);
    }
}