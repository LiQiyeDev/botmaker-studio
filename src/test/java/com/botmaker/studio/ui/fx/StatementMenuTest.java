package com.botmaker.studio.ui.fx;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.ui.render.menu.ExpressionMenuFactory;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the statement menu — the {@link ContextMenu} the "+" separator / empty-body placeholder shows to
 * insert a new block ({@link ExpressionMenuFactory#createStatementMenu}). This is the search/rebuild logic the
 * user reported an interaction issue around; the companion {@link SeparatorInsertButtonTest} covers the button's
 * hover/visibility state machine.
 *
 * <p>The menu is asserted at the JavaFX-object level (its {@code MenuItem}s), not by showing a popup window, so the
 * tests are deterministic headless: constructing/mutating the menu still requires the FX toolkit (hence
 * {@link FxHeadlessTest}), and every mutation is driven on the FX thread via {@link #interact(Runnable)}.
 */
class StatementMenuTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene needed — these tests operate on the ContextMenu object directly. ApplicationTest still starts
        // the FX runtime, which is all createStatementMenu() requires.
    }

    @Test
    void defaultMenuShowsCategorySubmenusWithControlRelocatedLast() {
        // Built with a null analyzer (no project resolved), so there are no generated SDK-facade submenus — only
        // the language-block categories, rendered as submenus (no flat promoted bot-actions row anymore).
        ContextMenu menu = build(type -> {});

        List<MenuItem> items = menu.getItems();
        assertTrue(items.get(0) instanceof CustomMenuItem, "first item is the search box");
        assertTrue(leafItems(menu).isEmpty(), "no flat leaf blocks promoted to the top level");

        List<Menu> submenus = items.stream().filter(i -> i instanceof Menu).map(i -> (Menu) i).toList();
        assertFalse(submenus.isEmpty(), "language category submenus are shown");

        // The bot Control statements (enable/disable activity, stop bot) are relocated into a clearly-labelled
        // "Control" submenu, placed last.
        assertEquals(BlockCategory.CONTROL.getLabel(), submenus.getLast().getText(), "Control group is placed last");
        List<String> control = submenus.getLast().getItems().stream().map(MenuItem::getText).toList();
        assertTrue(control.contains(BlockCatalog.STOP_BOT.displayName()), control.toString());
        assertTrue(control.contains(BlockCatalog.DISABLE_ACTIVITY.displayName()), control.toString());
        assertTrue(control.contains(BlockCatalog.ENABLE_ACTIVITY.displayName()), control.toString());
    }

    @Test
    void searchFiltersToMatchingBlocksOnly() {
        ContextMenu menu = build(type -> {});
        String query = BlockCatalog.PRINT.displayName(); // "Print"

        setSearch(menu, query);

        // Active search collapses to a flat, filtered list — no submenus, and every leaf matches the query.
        assertFalse(menu.getItems().stream().anyMatch(i -> i instanceof Menu),
                "search results are flat (no category submenus)");
        List<String> leaves = leafTexts(menu);
        assertTrue(leaves.contains(BlockCatalog.PRINT.displayName()), "the matching block is listed: " + leaves);
        assertTrue(leaves.stream().allMatch(t -> t.toLowerCase().contains(query.toLowerCase())),
                "every result matches the query: " + leaves);
    }

    @Test
    void searchWithNoMatchShowsDisabledPlaceholder() {
        ContextMenu menu = build(type -> {});

        setSearch(menu, "zzzznotablock");

        List<MenuItem> body = menu.getItems().subList(1, menu.getItems().size());
        assertEquals(1, body.size(), "only the placeholder remains");
        assertTrue(body.get(0).isDisable(), "placeholder is disabled");
        assertEquals("No matching blocks", body.get(0).getText());
    }

    @Test
    void selectingAnItemFiresConsumerWithThatBlockType() {
        AtomicReference<BlockType> selected = new AtomicReference<>();
        ContextMenu menu = build(selected::set);
        setSearch(menu, BlockCatalog.PRINT.displayName());

        MenuItem printItem = leafItems(menu).stream()
                .filter(i -> BlockCatalog.PRINT.displayName().equals(i.getText()))
                .findFirst().orElse(null);
        assertNotNull(printItem, "the Print item should be present after filtering");

        interact(printItem::fire);

        assertEquals(BlockCatalog.PRINT, selected.get(),
                "firing the menu item should hand the exact BlockType to the insert callback");
    }

    // --- helpers ---

    private ContextMenu build(java.util.function.Consumer<BlockType> onSelection) {
        AtomicReference<ContextMenu> ref = new AtomicReference<>();
        // Null analyzer: exercises the language-block path (no project/SDK jar resolved in a headless test).
        interact(() -> ref.set(ExpressionMenuFactory.createStatementMenu(null, onSelection)));
        return ref.get();
    }

    private void setSearch(ContextMenu menu, String query) {
        TextField search = (TextField) ((CustomMenuItem) menu.getItems().get(0)).getContent();
        interact(() -> search.setText(query)); // fires the textProperty listener -> rebuildStatementItems
    }

    /** Leaf (non-submenu, non-separator) menu items directly under the menu root. */
    private static List<MenuItem> leafItems(ContextMenu menu) {
        List<MenuItem> out = new ArrayList<>();
        for (MenuItem item : menu.getItems()) {
            if (item instanceof CustomMenuItem || item instanceof SeparatorMenuItem || item instanceof Menu) continue;
            out.add(item);
        }
        return out;
    }

    private static List<String> leafTexts(ContextMenu menu) {
        return leafItems(menu).stream().map(MenuItem::getText).toList();
    }
}
