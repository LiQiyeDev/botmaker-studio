package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.palette.BlockCategory;
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

    private final String inputType;

    public ReadInputBlock(String id, VariableDeclarationStatement astNode, String inputType) {
        super(id, astNode);
        this.inputType = inputType;
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
        Label readLabel = new Label(readPhrase());
        readLabel.getStyleClass().add("keyword-label");

        var sentence = BlockLayout.sentence()
                .addNode(BlockUIComponents.createTypeLabel(getTypeDisplayName()))
                .addNode(nameField)
                .addKeyword("=")
                .addNode(readLabel)
                .build();

        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(deleteAction(context))
                .build();
    }

    private String readPhrase() {
        return switch (inputType) {
            case "readLine" -> "read a line of text";
            case "readInt" -> "read a whole number";
            case "readDouble" -> "read a decimal";
            case "readBoolean" -> "read true/false";
            default -> "read input";
        };
    }

    private String getTypeDisplayName() {
        return switch (inputType) {
            case "readLine" -> "String";
            case "readInt" -> "int";
            case "readDouble" -> "double";
            case "readBoolean" -> "boolean";
            default -> "var";
        };
    }
}
