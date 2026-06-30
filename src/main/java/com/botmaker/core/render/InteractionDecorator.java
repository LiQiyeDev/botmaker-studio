package com.botmaker.core.render;

import com.botmaker.core.AbstractCodeBlock;
import com.botmaker.events.CoreApplicationEvents;
import com.botmaker.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

/**
 * Wires the per-block right-click menu (copy / paste after). Skipped for read-only blocks, which are
 * non-interactive.
 */
public final class InteractionDecorator implements BlockDecorator {

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (block.isReadOnly()) return;

        ContextMenu menu = new ContextMenu();

        MenuItem copy = new MenuItem("Copy (Ctrl+C)");
        copy.setOnAction(ev -> {
            context.getState().setHighlightedBlock(block);
            context.getEventBus().publish(new CoreApplicationEvents.CopyRequestedEvent());
        });

        MenuItem paste = new MenuItem("Paste After (Ctrl+V)");
        paste.setOnAction(ev -> {
            context.getState().setHighlightedBlock(block);
            context.getEventBus().publish(new CoreApplicationEvents.PasteRequestedEvent());
        });

        menu.getItems().addAll(copy, paste);

        node.setOnContextMenuRequested(e -> {
            menu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }
}
