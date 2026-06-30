package com.botmaker.blocks.expr;

import com.botmaker.core.AbstractExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.types.ResolvedType;
import com.botmaker.suggestions.ProjectAnalyzer;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.QualifiedName;

public class FieldAccessBlock extends AbstractExpressionBlock {

    private final String qualifier;  // "this", "super", or object name
    private final String fieldName;

    public FieldAccessBlock(String id, FieldAccess astNode) {
        this(id, astNode, false);
    }

    public FieldAccessBlock(String id, FieldAccess astNode, boolean markAsUnedited) {
        super(id, astNode);
        Expression expr = astNode.getExpression();
        this.qualifier = expr != null ? expr.toString() : "";
        this.fieldName = astNode.getName().getIdentifier();
        this.isUnedited = markAsUnedited;
    }

    public FieldAccessBlock(String id, QualifiedName astNode, boolean markAsUnedited) {
        super(id, astNode);
        this.qualifier = astNode.getQualifier().toString();
        this.fieldName = astNode.getName().getIdentifier();
        this.isUnedited = markAsUnedited;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        HBox container = new HBox(3);
        container.setAlignment(Pos.CENTER_LEFT);
        container.getStyleClass().add("field-access-block");

        Text qualifierText = new Text(qualifier + ".");
        qualifierText.setStyle("-fx-fill: #8E44AD; -fx-font-weight: bold;");

        Text fieldText = new Text(fieldName);
        fieldText.setStyle("-fx-fill: #2C3E50;");

        container.getChildren().addAll(qualifierText, fieldText);
        applyUneditedClass(container);

        container.setCursor(Cursor.HAND);
        Tooltip.install(container, new Tooltip(isUnedited ? "⚠️ Default field name" : "Click to change field"));

        container.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                requestSuggestions(container, context);
            }
        });

        return container;
    }

    private void requestSuggestions(Node uiNode, CodeEditorService context) {
        // 1. Infer Type
        ResolvedType expectedType = ProjectAnalyzer.inferExpectedType(this.astNode);

    }
}