package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.util.MethodSignature;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

public class InstantiationBlock extends AbstractExpressionBlock {

    private final String className;
    private final List<ExpressionBlock> arguments = new ArrayList<>();

    public InstantiationBlock(String id, ClassInstanceCreation astNode) {
        super(id, astNode);
        if (astNode.getType() != null) {
            this.className = astNode.getType().toString();
        } else {
            this.className = "Object";
        }
    }

    public void addArgument(ExpressionBlock arg) {
        this.arguments.add(arg);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        ResolvedType expectedType = ProjectAnalyzer.inferExpectedType(this.astNode);

        ComboBox<String> classSelector = new ComboBox<>();
        classSelector.getStyleClass().add("class-selector");

        // Use TypeManager
        List<String> classes = context.getProjectAnalyzer().getCompatibleTypes(expectedType);
        classSelector.getItems().addAll(classes);

        if (!classSelector.getItems().contains(className)) {
            classSelector.getItems().add(0, className);
        }
        if (isCompatibleWithArrayList(expectedType) && !classSelector.getItems().contains("ArrayList")) {
            classSelector.getItems().add("ArrayList");
        }

        classSelector.setValue(className);

        classSelector.setOnAction(e -> {
            String selected = classSelector.getValue();
            if (selected != null && !selected.equals(className)) {
                context.getCodeEditor().updateInstantiation(
                        (ClassInstanceCreation) this.astNode,
                        selected,
                        null
                );
            }
        });

        MenuButton constructorBtn = new MenuButton("⚙");
        constructorBtn.getStyleClass().add("constructor-button");
        constructorBtn.setTooltip(new Tooltip("Select Constructor"));

        constructorBtn.setOnShowing(e -> {
            constructorBtn.getItems().clear();
            List<MethodSignature> constructors = context.getProjectAnalyzer().getConstructors(className);

            for (MethodSignature sig : constructors) {
                MenuItem item = new MenuItem(sig.toString());
                item.setOnAction(ev -> {
                    // Pass ResolvedType list directly
                    context.getCodeEditor().updateInstantiation(
                            (ClassInstanceCreation) this.astNode,
                            className,
                            sig.paramTypes()
                    );
                });
                constructorBtn.getItems().add(item);
            }
        });

        var sentenceBuilder = BlockLayout.sentence()
                .alignment(Pos.CENTER_LEFT)
                .addLabel("Create")
                .addNode(classSelector)
                .addNode(constructorBtn)
                .addLabel("(");

        MethodSignature currentSig = determineCurrentSignature(context);

        for (int i = 0; i < arguments.size(); i++) {
            ExpressionBlock arg = arguments.get(i);

            if (i > 0) sentenceBuilder.addLabel(",");

            Label paramLabel = null;
            if (currentSig != null && i < currentSig.paramNames().size()) {
                paramLabel = new Label(currentSig.paramNames().get(i) + ":");
                paramLabel.getStyleClass().add("param-name-label");
            }

            ResolvedType paramType = (currentSig != null && i < currentSig.paramTypes().size())
                    ? currentSig.paramTypes().get(i)
                    : ResolvedType.UNKNOWN;

            sentenceBuilder.addNode(createArgumentPill(context, arg, paramType, paramLabel, false));
        }

        sentenceBuilder.addLabel(")");

        HBox container = sentenceBuilder.build();
        container.getStyleClass().add("instantiation-block");

        return container;
    }

    private boolean isCompatibleWithArrayList(ResolvedType type) {
        if (type == null || type.isUnknown()) return true;
        String name = type.simpleName();
        return name.contains("List") || name.contains("Collection") || name.equals("Object");
    }

    private MethodSignature determineCurrentSignature(CodeEditorService context) {
        List<MethodSignature> constructors = context.getProjectAnalyzer().getConstructors(className);
        return MethodSignature.bestForArity(constructors, arguments.size());
    }
}