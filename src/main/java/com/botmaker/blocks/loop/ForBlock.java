package com.botmaker.blocks.loop;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.BlockWithChildren;
import com.botmaker.core.CodeBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.ui.dnd.BlockDragAndDropManager;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.ui.render.components.TextFieldComponents;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.ArrayList;
import java.util.List;

public class ForBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock variable;
    private ExpressionBlock collection;
    private BodyBlock body;

    public ForBlock(String id, EnhancedForStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
    }

    public void setVariable(ExpressionBlock variable) { this.variable = variable; }
    public void setCollection(ExpressionBlock collection) { this.collection = collection; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (variable != null) children.add(variable);
        if (collection != null) children.add(collection);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // Extract variable name safely
        String varName = "";
        if (variable != null && variable.getAstNode() instanceof SimpleName) {
            varName = ((SimpleName) variable.getAstNode()).getIdentifier();
        }

        // Create editable field for the loop variable
        TextField nameField = TextFieldComponents.createVariableNameField(varName, newName -> {
            if (variable != null && variable.getAstNode() instanceof SimpleName) {
                // Reuse the generic replacement logic in CodeEditor
                context.getCodeEditor().replaceSimpleName((SimpleName) variable.getAstNode(), newName);
            }
        });

        // Build sentence: "for each [nameField] in [collection]"
        var sentence = BlockLayout.sentence()
                .addKeyword("for each")
                .addNode(nameField) // Use the text field directly
                .addKeyword("in")
                .addExpressionSlot(collection, context, ResolvedType.UNKNOWN)
                .build();

        sentence.getStyleClass().add("for-header");

        // Build full structure with header and body
        return BlockLayout.header()
                .withCustomNode(sentence)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((org.eclipse.jdt.core.dom.Statement) this.astNode))
                .andBody()
                .withContent(body, context)
                .withStyleClass("for-block")
                .build();
    }
}