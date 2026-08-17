package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.palette.BotType;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;

import java.util.List;

/**
 * Picks one of the curated {@link BotType}s, optionally wrapped in a list.
 *
 * <p>A {@link MenuButton} rather than a {@code ComboBox} because the list is grouped and a combo box has no
 * grouping: its items are one flat run, and the two ways round that are a cell factory that fakes
 * unselectable header rows or twenty entries with no shape at all. A menu's submenus are what grouping
 * actually is — and they give the {@code List of ▸} axis somewhere to live that costs nothing when unused,
 * which the flat form would have to spend twenty more entries on.
 */
public final class BotTypePicker extends MenuButton {

    private final ObjectProperty<BotType.Choice> choice = new SimpleObjectProperty<>();

    /**
     * @param allowVoid whether {@link BotType#NOTHING} is offered — true for a return type, false for a
     *                  parameter or a variable, which cannot be {@code void}
     */
    public BotTypePicker(boolean allowVoid) {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        for (BotType.Group group : BotType.Group.values()) {
            List<MenuItem> items = BotType.in(group).stream()
                    .filter(t -> allowVoid || t.declarable())
                    .map(t -> singleItem(t))
                    .toList();
            if (!items.isEmpty()) {
                Menu menu = new Menu(group.label());
                menu.getItems().addAll(items);
                getItems().add(menu);
            }
        }
        getItems().add(listMenu());

        choice.addListener((obs, old, now) -> setText(now == null ? "Choose a type…" : now.label()));
        choice.set(BotType.Choice.of(allowVoid ? BotType.NOTHING : BotType.TEXT));
    }

    private MenuItem singleItem(BotType type) {
        MenuItem item = new MenuItem(type.label());
        item.setOnAction(e -> choice.set(BotType.Choice.of(type)));
        return item;
    }

    /** {@code List of ▸ <group> ▸ <type>} — the same tree again, one level down. */
    private Menu listMenu() {
        Menu listOf = new Menu("List of");
        for (BotType.Group group : BotType.Group.values()) {
            Menu groupMenu = new Menu(group.label());
            for (BotType type : BotType.in(group)) {
                if (!type.listable()) continue;
                MenuItem item = new MenuItem(type.label());
                item.setOnAction(e -> choice.set(new BotType.Choice(type, true)));
                groupMenu.getItems().add(item);
            }
            if (!groupMenu.getItems().isEmpty()) listOf.getItems().add(groupMenu);
        }
        return listOf;
    }

    public ObjectProperty<BotType.Choice> choiceProperty() {
        return choice;
    }

    public BotType.Choice choice() {
        return choice.get();
    }

    public void setChoice(BotType.Choice value) {
        choice.set(value);
    }
}
