package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.blocks.expr.ListBlock;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.ExpressionSlots;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.components.pickers.PickerContext;
import com.botmaker.studio.ui.render.components.pickers.PickerRegistry;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.vars.ActivityVariablesDialog;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import static com.botmaker.studio.ui.render.components.BlockUIComponents.createTypeLabel;

public class VariableDeclarationBlock extends AbstractStatementBlock {

    private final String variableName;
    private final ResolvedType varType;
    private ExpressionBlock initializer;

    public VariableDeclarationBlock(String id, VariableDeclarationStatement astNode) {
        super(id, astNode);
        VariableDeclarationFragment fragment = (VariableDeclarationFragment) astNode.fragments().getFirst();
        this.variableName = fragment.getName().getIdentifier();
        this.varType = ProjectAnalyzer.resolveType(astNode.getType());
        this.initializer = null;
    }

    public void setInitializer(ExpressionBlock initializer) { this.initializer = initializer; }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // Name and type are shown here and changed elsewhere. Both used to be editable in place, and the
        // inline rename went through `replaceSimpleName` on the declaration alone — every use site kept the
        // old name, so renaming a variable on its own block is how a file stops compiling. The Variables
        // screen rewrites the uses with it (`renameLocalVariable`) and is one button away, below.
        Label typeLabel = createTypeLabel(varType.simpleName());
        Node nameField = TextFieldComponents.createVariableName(variableName, false, newName -> {});

        Node initNode;
        if (initializer != null) {
            if (initializer instanceof ListBlock) {
                initNode = initializer.getUINode(context);
            } else if (initializer.getAstNode() instanceof ArrayInitializer) {
                initNode = createListDisplay(context);
            } else {
                // Route the initializer through the same specialized pickers used for call arguments, keyed on
                // the declared variable type — so `ImageTemplate t = new ImageTemplate(...)`, `Rect r = ...`,
                // `Point p = ...`, `Direction d = ...` get their thumbnail/region/enum editor instead of a raw
                // expression node. Falls back to the generic node when no picker matches.
                Node picker = PickerRegistry.pickerNodeFor(PickerContext.of(context, ValueSlot.of(initializer), varType));
                initNode = picker != null ? picker : initializer.getUINode(context);
                // Dropping a call onto the value of a declaration is the same gesture as dropping it into any
                // other slot. The list/array renderings above stay out of it: they hold several expressions,
                // and a drop names exactly one to replace.
                ExpressionSlots.makeDroppable(initNode, initializer, context, varType);
            }
        } else {
            initNode = createExpressionDropZone(context);
        }

        Button addButton = createAddButton(e -> {
            Expression currentInitializer = initializer != null ?
                    (Expression) initializer.getAstNode() : null;

            ContextMenu menu = ExpressionMenu.create(
                    varType, false, context, this.astNode, x -> true,
                    selection -> {
                        if (currentInitializer != null) {
                            applyExpressionSelection(context, currentInitializer, selection);
                        } else {
                            context.getCodeEditor().setVariableInitializer((VariableDeclarationStatement) this.astNode, selection);
                        }
                    });
            menu.show((Button)e.getSource(), javafx.geometry.Side.BOTTOM, 0, 0);
        });

        var sentence = BlockLayout.sentence()
                .addNode(typeLabel)
                .addNode(nameField)
                .addKeyword("=")
                .addNode(initNode)
                .addNode(addButton)
                .addNode(variablesButton(context))
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(deleteAction(context))
                .build();
    }

    /**
     * The way to the Variables screen from the block that declares one — a visible control, not a right-click
     * menu item, because it is now the <em>only</em> place this variable's name and type can be changed. It
     * opens on this variable's row, so the screen answers the question you were standing in front of.
     */
    private Node variablesButton(CodeEditorService context) {
        if (isReadOnly()) return null;
        Button open = new Button("✎ Variables…");
        open.getStyleClass().add("variables-open-button");
        open.setTooltip(new Tooltip(
                "Rename or retype \"" + variableName + "\" — renaming here carries every use with it."));
        open.setOnAction(e -> ActivityVariablesDialog.show(context, windowOf(context), variableName));
        return open;
    }

    private javafx.stage.Window windowOf(CodeEditorService context) {
        Node node = getUINode(context);
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "{", "}", false);
    }

    /**
     * "Variables in this activity…" — the list view of what this method declares. Offered from a declare
     * block because that is where you are standing when the question "what else is declared here?" comes up.
     */
    @Override
    public java.util.List<javafx.scene.control.MenuItem> blockMenuItems(CodeEditorService context) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem("Variables in this activity…");
        item.setOnAction(e -> ActivityVariablesDialog.show(context, windowOf(context), variableName));
        return java.util.List.of(item);
    }
}
