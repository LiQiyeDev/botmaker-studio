// FILE: rs\bgroi\Documents\dev\IntellijProjects\BotMaker\src\main\java\com\botmaker\blocks\PrintBlock.java
package com.botmaker.blocks.misc;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.palette.ExpressionCatalog;
import com.botmaker.ui.render.layout.BlockLayout;
import com.botmaker.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class PrintBlock extends AbstractStatementBlock {

    private final List<ExpressionBlock> arguments = new ArrayList<>();

    public PrintBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
    }

    public void addArgument(ExpressionBlock argument) { this.arguments.add(argument); }

    @Override
    protected Node createUINode(CodeEditorService context) {
        var sentenceBuilder = BlockLayout.sentence()
                .addLabel("Print:");

        if (arguments.isEmpty()) {
            sentenceBuilder.addNode(createExpressionDropZone(context));
        } else {
            for (ExpressionBlock arg : arguments) {
                sentenceBuilder.addNode(arg.getUINode(context));
            }
        }

        // Add Button with Filter (No List in Print)
        Button addButton = createAddButton(e -> {
            org.eclipse.jdt.core.dom.Expression toReplace = !arguments.isEmpty() ?
                    (org.eclipse.jdt.core.dom.Expression) arguments.getFirst().getAstNode() : null;

            showExpressionMenuAndReplace(
                    (Button)e.getSource(),
                    context,
                    ResolvedType.UNKNOWN,
                    toReplace,
                    // Filter: Don't allow lists in print
                    expr -> expr != ExpressionCatalog.LIST
            );
        });

        sentenceBuilder.addNode(addButton);

        return BlockLayout.header()
                .withCustomNode(sentenceBuilder.build())
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((org.eclipse.jdt.core.dom.Statement) this.astNode))
                .build();
    }

    @Override
    public String getDetails() {
        String argsString = arguments.stream().map(ExpressionBlock::getDetails).collect(Collectors.joining(", "));
        return "Print Statement: " + argsString;
    }
}