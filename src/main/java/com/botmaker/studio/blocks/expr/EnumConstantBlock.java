package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class EnumConstantBlock extends AbstractExpressionBlock {

    private final String enumTypeName;
    private final String constantName;

    public EnumConstantBlock(String id, QualifiedName astNode) {
        super(id, astNode);
        this.enumTypeName = astNode.getQualifier().toString();
        this.constantName = astNode.getName().getIdentifier();
    }

    public EnumConstantBlock(String id, SimpleName astNode, String enumTypeName) {
        super(id, astNode);
        this.enumTypeName = enumTypeName;
        this.constantName = astNode.getIdentifier();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        Label typeLabel = new Label(enumTypeName);
        typeLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px;");

        // --- INTERACTIVITY START ---
        ExpressionMenuFactory.installTypeSelector(typeLabel, "Click to change Enum type",
                () -> context.getProjectAnalyzer().findTypeByName(enumTypeName),
                context, this.astNode,
                newType -> {
                    // Logic to find a default constant for the new type
                    String defaultConst = "VALUE";
                    if (newType.isEnum()) {
                        List<String> consts = newType.enumConstants();
                        if (!consts.isEmpty()) {
                            defaultConst = consts.getFirst();
                        }
                    }
                    context.getCodeEditor().replaceWithEnumConstant(
                            (Expression) this.astNode,
                            newType.simpleName(),
                            defaultConst
                    );
                });
        // --- INTERACTIVITY END ---

        Label dot = new Label(".");
        dot.setStyle("-fx-text-fill: rgba(255,255,255,0.7); -fx-font-weight: bold;");

        ComboBox<String> constantSelector = new ComboBox<>();
        constantSelector.setStyle(
                "-fx-background-color: rgba(255,255,255,0.2);" +
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 11px;" +
                        "-fx-background-radius: 4;" +
                        "-fx-padding: 2 6 2 6;"
        );

        List<String> constants = getEnumConstants(enumTypeName, context);
        constantSelector.getItems().addAll(constants);
        constantSelector.setValue(constantName);

        constantSelector.setOnAction(e -> {
            String newConstant = constantSelector.getValue();
            if (newConstant != null && !newConstant.equals(constantName)) {
                updateConstant(newConstant, context);
            }
        });

        HBox container = BlockLayout.sentence()
                .addNode(typeLabel)
                .addNode(dot)
                .addNode(constantSelector)
                .build();

        container.setStyle(
                "-fx-background-color: #d35400;" +
                        "-fx-background-radius: 5;" +
                        "-fx-padding: 4 8 4 8;"
        );

        return container;
    }

    private List<String> getEnumConstants(String enumName, CodeEditorService context) {
        CompilationUnit cu = context.getState().getCompilationUnit().orElse(null);
        if (cu == null) return new ArrayList<>();

        EnumDeclaration enumDecl = ProjectAnalyzer.findEnumDeclaration(cu, enumName);

        if (enumDecl != null) {
            return ProjectAnalyzer.getEnumConstantNames(enumDecl);
        }

        ResolvedType enumType = context.getProjectAnalyzer().findTypeByName(enumName);

        if (!enumType.isUnknown() && enumType.isEnum()) {
            return enumType.enumConstants();
        }

        return new ArrayList<>();
    }

    private void updateConstant(String newConstant, CodeEditorService context) {
        Expression oldExpr = (Expression) this.astNode;
        if (oldExpr instanceof QualifiedName) {
            QualifiedName qn = (QualifiedName) oldExpr;
            context.getCodeEditor().replaceSimpleName(qn.getName(), newConstant);
        } else if (oldExpr instanceof SimpleName) {
            context.getCodeEditor().replaceSimpleName((SimpleName) oldExpr, newConstant);
        }
    }
}