package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import com.botmaker.ui.render.menu.MenuComponents;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.List;

public class IdentifierBlock extends AbstractExpressionBlock {
    private final String identifier;

    public IdentifierBlock(String id, SimpleName astNode) {
        this(id, astNode, false);
    }

    public IdentifierBlock(String id, SimpleName astNode, boolean markAsUnedited) {
        super(id, astNode);
        this.identifier = astNode.getIdentifier();
        this.isUnedited = markAsUnedited;
    }

    public String getIdentifier() { return identifier; }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Text text = new Text(identifier);
        HBox container = new HBox(text);
        container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        container.getStyleClass().add("identifier-block");
        applyUneditedClass(container);

        container.setCursor(Cursor.HAND);
        container.setOnMouseClicked(e -> {
            // Refactored logic call
            requestSuggestions(container, context);
            e.consume();
        });

        return container;
    }

    private void requestSuggestions(Node uiNode, CodeEditorService context) {
        // 1. Infer Type
        ResolvedType expectedType = ProjectAnalyzer.inferExpectedType(this.astNode);

        // 2. Use AST Analysis (Synchronous & Reliable)
        List<ProjectAnalyzer.VariableOption> variables =
                context.getProjectAnalyzer().getVisibleVariables(this.astNode, expectedType);

        MenuComponents.showListMenu(uiNode, variables,
                var -> var.name() + (var.isField() ? " (Field)" : ""),
                var -> {
                    context.getCodeEditor().replaceSimpleName((SimpleName) this.astNode, var.name());
                    markAsEdited();
                },
                "(No variables found)");
    }
}