package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ExpressionStatement;

/**
 * A fixed-label statement that ends the bot — the generated {@code Bot.stop();} call (see the SDK's
 * {@code Bot.stop}). Modelled like {@link BreakBlock}/{@link ActivityToggleBlock}: no scope/method dropdown,
 * just a keyword phrase, so the fixed static call never surfaces a scope selector.
 *
 * <p>Usable anywhere (unlike the activity-scoped toggles); the generated {@code GameLoop} also emits it
 * automatically once every activity is disabled.
 */
public class StopBotBlock extends AbstractStatementBlock {

    public StopBotBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.CONTROL;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withKeyword("stop this bot")
                .withDeleteButton(deleteAction(context))
                .build();
    }
}
