package com.botmaker.studio.blocks.misc;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.Collections;
import java.util.List;

/**
 * An initializer block in a class body — {@code static { … }} or the instance {@code { … }} form.
 *
 * <p><b>Why this exists.</b> {@code BlockConverter.parseRoot} handled only methods, enums and fields, and JDT
 * models an initializer as neither — it is an {@link Initializer} body declaration. So the whole construct was
 * dropped from the block tree with no visual trace at all: {@code Activities.java}'s static block (the loader
 * that reads every field's value out of {@code activities.json}) simply wasn't there. Worse than invisible —
 * {@code ClassBlock} rewrites the class from its body-declaration list, so an edit elsewhere in the file could
 * write the initializer out of existence, taking every activity's configured value with it.
 *
 * <p>The static block is also the shape the loader has to keep: an inline initializer would make each field a
 * compile-time constant, and a constant cannot be read out of {@code activities.json} at startup. The file is
 * generated and read-only in the editor, so this block is there to be read rather than typed in — but it has
 * to exist for the file to survive being opened at all.
 *
 * <p>Rendered like a method: a header naming the construct, and the real parsed body beneath it, so the
 * statements inside are ordinary blocks rather than an opaque text dump.
 */
public class InitializerBlock extends AbstractStatementBlock implements BlockWithChildren {

    private final boolean isStatic;
    private BodyBlock body;

    public InitializerBlock(String id, Initializer astNode) {
        super(id, astNode);
        this.isStatic = Modifier.isStatic(astNode.getModifiers());
    }

    public void setBody(BodyBlock body) {
        this.body = body;
    }

    /** True for {@code static { … }}, false for the instance initializer. */
    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public List<CodeBlock> getChildren() {
        return body != null ? Collections.singletonList(body) : Collections.emptyList();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = new VBox(0);

        VBox headerBox = new VBox(5);
        headerBox.getStyleClass().add("block-header");

        Label keyword = new Label(isStatic ? "Static setup" : "Setup");
        keyword.getStyleClass().add("header-keyword-label");

        Label hint = new Label(isStatic ? "runs once when the bot starts" : "runs when this object is created");
        hint.getStyleClass().add("initializer-hint-label");

        HBox topRow = BlockLayout.sentence()
                .addNode(keyword)
                .addNode(hint)
                .build();
        headerBox.getChildren().add(topRow);
        container.getChildren().add(headerBox);

        VBox bodyWrapper = new VBox();
        bodyWrapper.getStyleClass().add("block-body-wrapper");
        if (body != null) {
            bodyWrapper.getChildren().add(body.getUINode(context));
        }
        container.getChildren().add(bodyWrapper);

        return container;
    }
}
