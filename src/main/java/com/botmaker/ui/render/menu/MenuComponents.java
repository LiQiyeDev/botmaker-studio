package com.botmaker.ui.render.menu;

import com.botmaker.types.ResolvedType;
import javafx.collections.ObservableList;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** Inline style for the disabled section headers in {@link #populateGroupedTypeMenu}. */
    private static final String SECTION_HEADER_STYLE = "-fx-font-weight: bold; -fx-opacity: 1.0; -fx-text-fill: #666;";

    /**
     * Populates {@code target} with a type picker grouped into a disabled "PRIMITIVES" section followed by a
     * separated "CLASSES" section. Each section is de-duplicated by simple name and sorted; {@code fundamentalNames}
     * decides which section a type lands in, and {@code void} types are skipped. Every type item invokes
     * {@code onPick}. Shared by the method / constructor "add parameter" menus.
     */
    public static void populateGroupedTypeMenu(ObservableList<MenuItem> target, List<ResolvedType> types,
                                               Collection<String> fundamentalNames, Consumer<ResolvedType> onPick) {
        List<ResolvedType> primitives = new ArrayList<>();
        List<ResolvedType> classes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolvedType type : types) {
            if (type.isVoid()) continue;
            if (!seen.add(type.simpleName())) continue;
            (fundamentalNames.contains(type.simpleName()) ? primitives : classes).add(type);
        }
        primitives.sort(Comparator.comparing(ResolvedType::simpleName));
        classes.sort(Comparator.comparing(ResolvedType::simpleName));

        if (!primitives.isEmpty()) {
            target.add(sectionHeader("PRIMITIVES"));
            for (ResolvedType type : primitives) target.add(typeItem(type, onPick));
        }
        if (!classes.isEmpty()) {
            if (!target.isEmpty()) target.add(new SeparatorMenuItem());
            target.add(sectionHeader("CLASSES"));
            for (ResolvedType type : classes) target.add(typeItem(type, onPick));
        }
    }

    private static MenuItem sectionHeader(String text) {
        MenuItem header = new MenuItem(text);
        header.setDisable(true);
        header.setStyle(SECTION_HEADER_STYLE);
        return header;
    }

    private static MenuItem typeItem(ResolvedType type, Consumer<ResolvedType> onPick) {
        MenuItem item = new MenuItem(type.simpleName());
        item.setOnAction(e -> onPick.accept(type));
        return item;
    }
}
