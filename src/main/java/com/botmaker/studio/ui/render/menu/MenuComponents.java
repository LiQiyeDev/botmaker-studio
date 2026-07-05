package com.botmaker.studio.ui.render.menu;

import javafx.collections.ObservableList;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Small helpers for the recurring "turn a list into a flat menu" pattern: one {@link MenuItem} per element,
 * with a single disabled fallback item when the list is empty. Complements the type-aware menu building in
 * {@link ExpressionMenuFactory} — this is the plumbing, that is the domain logic.
 */
public final class MenuComponents {

    private MenuComponents() {}

    /**
     * Fills {@code target} (a {@link ContextMenu}'s or {@code MenuButton}'s items list) with one
     * {@link MenuItem} per element of {@code items}, labelled by {@code label} and invoking {@code onPick}
     * on action. When {@code items} is empty, adds a single disabled {@code emptyText} item instead.
     */
    public static <T> void populate(ObservableList<MenuItem> target, List<T> items,
                                    Function<T, String> label, Consumer<T> onPick, String emptyText) {
        if (items.isEmpty()) {
            MenuItem empty = new MenuItem(emptyText);
            empty.setDisable(true);
            target.add(empty);
            return;
        }
        for (T item : items) {
            MenuItem menuItem = new MenuItem(label.apply(item));
            menuItem.setOnAction(e -> onPick.accept(item));
            target.add(menuItem);
        }
    }

    /**
     * Builds a flat {@link ContextMenu} from {@code items} (see {@link #populate}) and shows it below
     * {@code anchor}.
     */
    public static <T> void showListMenu(Node anchor, List<T> items, Function<T, String> label,
                                        Consumer<T> onPick, String emptyText) {
        ContextMenu menu = new ContextMenu();
        populate(menu.getItems(), items, label, onPick, emptyText);
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

}
