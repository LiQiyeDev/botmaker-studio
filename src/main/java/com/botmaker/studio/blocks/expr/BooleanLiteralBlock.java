package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.StyleBuilder;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;

/**
 * Block for true/false values with toggle switch style UI.
 * Directly toggles on click.
 */
public class BooleanLiteralBlock extends AbstractExpressionBlock {

    private boolean value;

    public BooleanLiteralBlock(String id, BooleanLiteral astNode) {
        super(id, astNode);
        this.value = astNode.booleanValue();
    }

    public boolean getValue() {
        return value;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        StackPane root = new StackPane();
        root.getStyleClass().add("boolean-literal-block");

        // Visible pill label
        Label displayLabel = new Label(value ? "TRUE" : "FALSE");

        // Apply initial styles
        updateLabelStyle(displayLabel, value);

        StackPane.setAlignment(displayLabel, Pos.CENTER);

        // Handle toggle on click
        root.setOnMouseClicked(e -> {
            boolean newValue = !value;
            this.value = newValue;

            // Immediate UI feedback
            displayLabel.setText(newValue ? "TRUE" : "FALSE");
            updateLabelStyle(displayLabel, newValue);

            // Update AST
            context.getCodeEditor().replaceLiteralValue(
                    (Expression) this.astNode,
                    String.valueOf(newValue)
            );
        });

        root.getChildren().add(displayLabel);
        root.setMinWidth(60);
        root.setMaxHeight(24);

        // Show hand cursor to indicate interactivity
        root.setCursor(javafx.scene.Cursor.HAND);

        return root;
    }

    private void updateLabelStyle(Label label, boolean val) {
        String trueColor = "#2ecc71";  // Emerald Green
        String falseColor = "#e74c3c"; // Alizarin Red
        String color = val ? trueColor : falseColor;

        StyleBuilder.create()
                .textColor("white")
                .fontWeight("bold")
                .fontSize(11)
                .fontFamily("'Segoe UI', sans-serif")
                .padding(3, 10, 3, 10)
                .backgroundColor(color)
                .backgroundRadius(12)
                .cursor("hand")
                .applyTo(label);
    }

    @Override
    public String getDetails() {
        return "Boolean: " + value;
    }
}