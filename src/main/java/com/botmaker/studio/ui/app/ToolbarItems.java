package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.ActionContext;
import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.ToolbarItem;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Turns a {@link ToolbarItem} into the button the host draws.
 *
 * <p><b>This class is the whole of what "the plugin contributes data, Studio builds the node" costs.</b> It
 * is small on purpose and it is where every property a shared bar has to own is applied in one place — the
 * style class that gives every button the same height, the tooltip, the icon box, the enablement. A surface
 * that handed back a {@code Node} instead would have none of these, and two plugins would produce two
 * different-looking bars.
 *
 * <p>Studio's own items go through it too, and that is the point rather than a convenience: an item built by
 * a different path is an item that can drift from what a plugin's looks like, and the first thing anybody
 * would notice is that the host's buttons are the ones that line up.
 */
final class ToolbarItems {

    /** Edge length of an item's icon. Matches the launch target's cover thumbnail, which predates this. */
    private static final int ICON_PX = 20;

    private ToolbarItems() {}

    /**
     * The button for {@code item}, already styled, labelled, tooltipped and wired.
     *
     * <p>The click is guarded: an action that throws is a plugin's bug and must not take the editor with it.
     * What the user gets is a button that did nothing, which is bad — and better than a dead window.
     */
    static Button button(ToolbarItem item, ActionContext ctx) {
        Button button = new Button();
        button.getStyleClass().add("toolbar-btn");
        if (item.tooltip() != null && !item.tooltip().isBlank()) {
            button.setTooltip(new Tooltip(item.tooltip()));
        }
        button.setOnAction(e -> {
            try {
                item.onClick().accept(ctx);
            } catch (RuntimeException | Error ex) {
                System.err.println("Warning: toolbar item '" + item.id() + "' failed: " + ex);
            }
        });
        refresh(button, item);
        return button;
    }

    /**
     * Re-reads the item's label and icon onto an already-built button.
     *
     * <p>Called when the host has reason to think the answer moved — a settings change, a project switch.
     * Both suppliers are the plugin's code running on the JavaFX thread, so both are guarded and both fall
     * back to leaving what is already there rather than blanking a button.
     */
    static void refresh(Button button, ToolbarItem item) {
        try {
            String text = item.label().get();
            if (text != null) button.setText(text);
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: toolbar item '" + item.id() + "' could not label itself: " + e);
        }
        if (item.icon() == null) return;
        try {
            String resource = item.icon().get();
            button.setGraphic(resource == null || resource.isBlank() ? null : icon(resource, item));
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: toolbar item '" + item.id() + "' could not load its icon: " + e);
        }
    }

    /**
     * Applies {@code state} to a button built from {@code item}.
     *
     * <p>{@link EnabledWhen} is a closed set rather than a predicate precisely so this is a switch the host
     * evaluates, not somebody else's code called once per item on every state change.
     */
    static void applyState(Button button, ToolbarItem item, boolean projectOpen, boolean botRunning) {
        button.setDisable(!switch (item.enabledWhen()) {
            case ALWAYS -> true;
            case PROJECT_OPEN -> projectOpen;
            case BOT_RUNNING -> botRunning;
            case BOT_STOPPED -> !botRunning;
        });
    }

    /**
     * An icon from a URI or from a plugin's own jar.
     *
     * <p>A missing or unreadable resource answers {@code null} rather than throwing: an icon is decoration
     * over a label that already carries a glyph in most of this application, and a plugin whose art failed to
     * ship should lose its picture, not its button.
     */
    private static ImageView icon(String resource, ToolbarItem item) {
        Image image = load(resource, item);
        if (image == null || image.isError()) return null;
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitHeight(ICON_PX);
        view.setFitWidth(ICON_PX);
        return view;
    }

    private static Image load(String resource, ToolbarItem item) {
        try {
            if (resource.contains(":")) return new Image(resource, ICON_PX, ICON_PX, true, true, true);
            // A bare name is a resource in the jar that asked for it — the plugin's own, never Studio's, and
            // never the context classloader's, which on the FX thread is whatever launched the application.
            // The item's own click handler is a class from that jar, so its loader is the honest answer and
            // the only one the host can arrive at without being told the plugin's coordinate.
            ClassLoader owner = item.onClick().getClass().getClassLoader();
            var stream = owner == null ? null : owner.getResourceAsStream(resource);
            return stream == null ? null : new Image(stream, ICON_PX, ICON_PX, true, true);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
