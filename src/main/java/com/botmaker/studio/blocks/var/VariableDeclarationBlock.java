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
import com.botmaker.studio.ui.app.vars.EditVariableDialog;
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
            initNode = emptyInitializer(context, varType);
        }

        Button addButton = createAddButton(e -> showInitializerMenu((Button) e.getSource(), context, varType));

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
     * The dashed hole where the starting value goes, opening the same menu as the ⊕ beside it.
     *
     * <p>{@code createEmptySlot} is deliberately not reused here: this block's write is
     * {@code setVariableInitializer}, which unwraps a collection type before asking what the value should be,
     * and a {@code List<Point>} initialised as if it were a {@code Point} is the bug that buys.
     */
    private Node emptyInitializer(CodeEditorService context, ResolvedType varType) {
        Node zone = createExpressionDropZone(context);
        if (isReadOnly()) return zone;
        zone.setCursor(javafx.scene.Cursor.HAND);
        zone.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            showInitializerMenu(zone, context, varType);
            e.consume();
        });
        return zone;
    }

    /** The starting-value menu, on the ⊕ or on the empty slot itself. */
    private void showInitializerMenu(Node anchor, CodeEditorService context, ResolvedType varType) {
        Expression current = initializer != null ? (Expression) initializer.getAstNode() : null;
        ContextMenu menu = ExpressionMenu.create(
                varType, false, context, this.astNode, x -> true,
                selection -> {
                    if (current != null) applyExpressionSelection(context, current, selection);
                    else context.getCodeEditor()
                            .setVariableInitializer((VariableDeclarationStatement) this.astNode, selection);
                });
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /**
     * The way to this variable's screen from the block that declares it — a visible control, not a right-click
     * menu item, because it is now the <em>only</em> place this variable's name and type can be changed.
     *
     * <p>The pencil alone, with the words in the tooltip. It sits inside a sentence — {@code Rect area = …} —
     * where a second labelled button competes with the statement for the reader's eye, and the glyph is the
     * part that says what it does. Larger than the label it replaced for the same reason: it has to be a
     * target now rather than a caption.
     */
    private Node variablesButton(CodeEditorService context) {
        if (isReadOnly()) return null;
        Button open = new Button("✎");
        open.getStyleClass().add("variables-open-button");
        open.setTooltip(new Tooltip(
                "Rename or retype \"" + variableName + "\" — renaming here carries every use with it."));
        open.setOnAction(e -> EditVariableDialog.show(context, windowOf(context), variableName));
        return open;
    }

    private javafx.stage.Window windowOf(CodeEditorService context) {
        Node node = getUINode(context);
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    private HBox createListDisplay(CodeEditorService context) {
        return LayoutComponents.createInlineListDisplay(initializer.getUINode(context), "{", "}", false);
    }

    /** The same screen from the right-click menu, for anyone who looks for it there rather than on the block. */
    @Override
    public java.util.List<javafx.scene.control.MenuItem> blockMenuItems(CodeEditorService context) {
        javafx.scene.control.MenuItem item =
                new javafx.scene.control.MenuItem("Edit \"" + variableName + "\"…");
        item.setOnAction(e -> EditVariableDialog.show(context, windowOf(context), variableName));
        return java.util.List.of(item);
    }
}
