package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.InputKind;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

public class ReadInputBlock extends AbstractStatementBlock {

    /** Null when the source calls a {@code BotMaker.readX()} this Studio does not know — a newer SDK's. */
    private final InputKind kind;

    public ReadInputBlock(String id, VariableDeclarationStatement astNode, InputKind kind) {
        super(id, astNode);
        this.kind = kind;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.INPUT;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VariableDeclarationFragment fragment =
                (VariableDeclarationFragment) ((VariableDeclarationStatement) this.astNode).fragments().getFirst();
        String currentName = fragment.getName().getIdentifier();

        // Editable declared name — a read block *declares* a new variable, so it gets a free-text field
        // (not the existing-variable dropdown an IdentifierBlock would show).
        Node nameField = TextFieldComponents.createVariableName(currentName, !isReadOnly(), newName -> {
            if (!newName.equals(currentName) && !newName.isEmpty()) {
                context.getCodeEditor().replaceSimpleName(fragment.getName(), newName);
            }
        });

        // Human-friendly phrasing instead of the raw BotMaker.readX() call text.
        Label readLabel = new Label(kind == null ? "read input" : kind.phrase());
        readLabel.getStyleClass().add("keyword-label");

        var sentence = BlockLayout.sentence()
                .addNode(BlockUIComponents.createTypeLabel(kind == null ? "var" : kind.typeName()))
                .addNode(nameField)
                .addKeyword("=")
                .addNode(readLabel)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
