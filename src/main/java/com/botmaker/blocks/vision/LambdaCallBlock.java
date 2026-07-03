package com.botmaker.blocks.vision;

import com.botmaker.core.AbstractStatementBlock;
import com.botmaker.core.BlockWithChildren;
import com.botmaker.core.BodyBlock;
import com.botmaker.core.CodeBlock;
import com.botmaker.core.ExpressionBlock;
import com.botmaker.services.CodeEditorService;
import com.botmaker.types.ResolvedType;
import com.botmaker.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * A body-carrying block for a facade "lambda call" — {@code Class.method(image, m -> { … })}. It renders like a
 * loop: a plain-English header with a fillable image slot plus an indented, droppable body (the lambda body).
 * Backs the {@code ImageFinder.whileExists/ifExists/untilExists} vision helpers; the header wording is keyed on the
 * method name so a single class serves all three (and any future lambda call). See {@code parser.handlers.LambdaCallHandler}.
 */
public class LambdaCallBlock extends AbstractStatementBlock implements BlockWithChildren {

    private static final ResolvedType IMAGE_TYPE = ResolvedType.named("ImageTemplate");

    private final String method;
    private ExpressionBlock image;
    private BodyBlock body;

    public LambdaCallBlock(String id, ASTNode astNode, String method) {
        super(id, astNode);
        this.method = method;
    }

    public void setImage(ExpressionBlock image) { this.image = image; }
    public void setBody(BodyBlock body) { this.body = body; }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (image != null) children.add(image);
        if (body != null) children.add(body);
        return children;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(5);

        Button changeBtn = createAddButton(e ->
                showExpressionMenuAndReplace(
                        (Button) e.getSource(),
                        context,
                        IMAGE_TYPE,
                        image != null ? (Expression) image.getAstNode() : null
                )
        );

        Node headerContent = BlockLayout.sentence()
                .addKeyword(prefixFor(method))
                .addExpressionSlot(image, context, IMAGE_TYPE)
                .addKeyword(suffixFor(method))
                .addNode(changeBtn)
                .build();

        container.getChildren().add(BlockLayout.header()
                .withCustomNode(headerContent)
                .withDeleteButton(() -> context.getCodeEditor().deleteStatement((Statement) this.astNode))
                .build());

        container.getChildren().add(createIndentedBody(body, context, "loop-body"));

        return container;
    }

    private static String prefixFor(String method) {
        return switch (method) {
            case "whileExists" -> "while image";
            case "ifExists" -> "if image";
            case "untilExists" -> "repeat until image";
            default -> method + " (";
        };
    }

    private static String suffixFor(String method) {
        return switch (method) {
            case "whileExists", "ifExists" -> "is visible";
            case "untilExists" -> "appears";
            default -> ")";
        };
    }
}
