package com.botmaker.studio.ui.fx;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the "+" separator insert button's hover → visibility → menu state machine
 * ({@link BlockDragAndDropManager#createSeparator()} + {@link BlockDragAndDropManager#enableSeparatorClick}). This
 * hover/z-order/{@code userData} juggling is the fragile area behind the reported "statement menu" interaction
 * issue, so each transition is asserted explicitly:
 * <ol>
 *   <li>the button is hidden until the separator is hovered;</li>
 *   <li>hovering reveals it and lifts the separator's view order;</li>
 *   <li>clicking it opens the statement menu and stashes it in {@code userData};</li>
 *   <li>the mouse leaving the separator while the menu is open does <b>not</b> hide the button (the bug fix);</li>
 *   <li>once the menu hides, the button is hidden again.</li>
 * </ol>
 *
 * <p>The separator lives in a shown stage because {@code ContextMenu.show(anchor, …)} needs the anchor attached to
 * a window; the hover handlers are invoked with synthesised {@link MouseEvent}s so the transitions are
 * deterministic rather than dependent on robot hit-testing of a 12px-tall strip.
 */
class SeparatorInsertButtonTest extends FxHeadlessTest {

    private Pane separator;
    private Button plusButton;
    private final List<BlockType> inserted = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        BlockDragAndDropManager dnd = new BlockDragAndDropManager(new EventBus(false));
        separator = dnd.createSeparator();
        // Null target body: no placement filtering, so the menu offers every block (this test is about the
        // button's show/hide behaviour, not about which blocks are legal where).
        dnd.enableSeparatorClick(separator, null, null, inserted::add);
        plusButton = plusButtonOf(separator);

        VBox root = new VBox(separator);
        root.setPrefWidth(300);
        stage.setScene(new Scene(root, 300, 80));
        stage.show();
    }

    @Test
    void buttonHiddenUntilHovered() {
        assertNotNull(plusButton, "separator should contain the '+' insert button");
        assertFalse(plusButton.isVisible(), "the '+' button starts hidden");
    }

    @Test
    void hoverRevealsButtonAndLiftsSeparator() {
        interact(() -> separator.getOnMouseEntered().handle(mouseEntered()));

        assertTrue(plusButton.isVisible(), "hovering the separator reveals the '+' button");
        assertTrue(separator.getViewOrder() < 0,
                "the hovered separator is lifted so adjacent blocks don't cover the button");
    }

    @Test
    void clickingButtonOpensStatementMenuWithoutInsertingYet() {
        interact(() -> separator.getOnMouseEntered().handle(mouseEntered()));
        interact(plusButton::fire);

        assertInstanceOf(ContextMenu.class, plusButton.getUserData(),
                "the open statement menu is stashed on the button so mouse-exit won't hide it");
        assertTrue(((ContextMenu) plusButton.getUserData()).isShowing(), "the statement menu is showing");
        assertTrue(inserted.isEmpty(), "merely opening the menu inserts nothing");
    }

    @Test
    void mouseLeavingWhileMenuOpenKeepsButtonVisible() {
        interact(() -> separator.getOnMouseEntered().handle(mouseEntered()));
        interact(plusButton::fire);

        // The mouse wanders off the separator while the menu is still open — the button must stay put.
        interact(() -> separator.getOnMouseExited().handle(mouseExited()));

        assertTrue(plusButton.isVisible(),
                "the '+' button must not vanish while its statement menu is open");
    }

    @Test
    void closingMenuHidesButtonAgain() {
        interact(() -> separator.getOnMouseEntered().handle(mouseEntered()));
        interact(plusButton::fire);
        ContextMenu menu = (ContextMenu) plusButton.getUserData();

        interact(menu::hide); // fires setOnHidden; separator is no longer hovered (headless), so the button hides

        assertFalse(plusButton.isVisible(), "closing the menu with the mouse away hides the '+' button");
        assertTrue(separator.getViewOrder() == 0.0, "the separator's view order is restored");
    }

    // --- helpers ---

    private static Button plusButtonOf(Pane separator) {
        for (Node child : separator.getChildren()) {
            if (child instanceof Button b) return b;
        }
        return null;
    }

    private MouseEvent mouseEntered() {
        return mouse(MouseEvent.MOUSE_ENTERED);
    }

    private MouseEvent mouseExited() {
        return mouse(MouseEvent.MOUSE_EXITED);
    }

    /** A minimal mouse event with no button pressed — the separator's enter handler ignores drags. */
    private MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 5, 5, 5, 5, MouseButton.NONE, 0,
                false, false, false, false,   // shift, control, alt, meta
                false, false, false,          // primary, middle, secondary down
                false, false, false, null);   // synthesized, popupTrigger, stillSincePress, pickResult
    }
}
