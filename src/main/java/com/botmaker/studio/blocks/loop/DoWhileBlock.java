package com.botmaker.studio.blocks.loop;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

public class DoWhileBlock extends AbstractStatementBlock implements BlockWithChildren {

    private ExpressionBlock condition;
    private BodyBlock body;

    public DoWhileBlock(String id, DoStatement astNode, BlockDragAndDropManager dragAndDropManager) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (body != null) children.add(body);
        if (condition != null) children.add(condition);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);

        // 1. Header: "do"
        container.getChildren().add(BlockLayout.header()
                .withKeyword("do")
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build());

        // 2. Body
        container.getChildren().add(createIndentedBody(body, context, "loop-body"));

        // 3. Footer: "while [condition] [+]"
        Button changeBtn = createAddButton(e ->
                showExpressionMenuAndReplace(
                        (Button) e.getSource(),
                        context,
                        ResolvedType.primitive("boolean"),
                        condition != null ? (Expression) condition.getAstNode() : null
                )
        );

        HBox footer = BlockLayout.sentence()
                .addKeyword("while")
                .addExpressionSlot(condition, context, ResolvedType.primitive("boolean"))
                .addNode(changeBtn)
                .build();

        container.getChildren().add(footer);

        return container;
    }
}