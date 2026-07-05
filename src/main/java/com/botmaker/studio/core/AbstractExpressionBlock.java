package com.botmaker.studio.core;

import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.SelectorComponents;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;

import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class AbstractExpressionBlock extends AbstractCodeBlock implements ExpressionBlock {

    /** Style class flagging an identifier/field still holding its generated default name (styled in blocks.css). */
    protected static final String UNEDITED_STYLE_CLASS = "unedited-identifier";

    /** True while this block still carries an auto-generated default name the user hasn't confirmed. */
    protected boolean isUnedited = false;

    public AbstractExpressionBlock(String id, ASTNode astNode) {
        super(id, astNode);
    }

    public boolean isUnedited() { return isUnedited; }

    /** Adds the unedited marker style class to {@code container} when this block is still unedited. */
    protected void applyUneditedClass(Node container) {
        if (isUnedited) {
            container.getStyleClass().add(UNEDITED_STYLE_CLASS);
        }
    }

    /** Clears the unedited flag and removes the marker style class from the rendered node. */
    public void markAsEdited() {
        this.isUnedited = false;
        if (uiNode != null) {
            uiNode.getStyleClass().remove(UNEDITED_STYLE_CLASS);
        }
    }

    protected Label createKeywordLabel(String text) { return BlockUIComponents.createKeywordLabel(text); }
    protected Label createOperatorLabel(String text) { return BlockUIComponents.createOperatorLabel(text); }
    protected ComboBox<String> createOperatorSelector(String[] names, String[] symbols, String currentSymbol, Consumer<String> onSymbolChange) {
        return SelectorComponents.createOperatorSelector(names, symbols, currentSymbol, onSymbolChange);
    }

    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace) {
        showExpressionMenuAndReplace(button, context, targetType, toReplace, x -> true);
    }

    /**
     * Builds one call-argument "pill" for {@code arg}: its rendered node, a "+" change button wired to the
     * type-aware replace menu for {@code paramType}, and an optional {@code leadingLabel} (a parameter name or
     * type hint, may be null). Shared by {@code InstantiationBlock} / {@code MethodInvocationBlock} — only the
     * leading label and {@code onDark} background styling differ between them.
     */
    protected Node createArgumentPill(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType,
                                      Node leadingLabel, boolean onDark) {
        Button changeBtn = BlockUIComponents.createChangeButton(e ->
                showExpressionMenuAndReplace((Button) e.getSource(), context, paramType, (Expression) arg.getAstNode()));
        return BlockUIComponents.createArgumentPill(leadingLabel, arg.getUINode(context), changeBtn, onDark);
    }

    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace,
                                                Predicate<ExpressionType> filter) {

        // Use 'this.astNode' as context for scope resolution
        ContextMenu menu = ExpressionMenuFactory.createExpressionTypeMenu(
                targetType,
                false,
                context,
                this.astNode, // PASS THE CONTEXT NODE
                filter,
                selection -> applyExpressionSelection(context, toReplace, selection)
        );
        menu.show(button, javafx.geometry.Side.BOTTOM, 0, 0);
    }
}