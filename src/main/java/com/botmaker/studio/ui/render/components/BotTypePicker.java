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
     * What the type is being picked <em>for</em>, which is the only thing that differs between the three
     * places this picker is used. A named purpose rather than a flag because there are three answers and the
     * flag could only carry two — and it is the reason a variable's list and a parameter's list can be
     * different without being two pickers.
     */
    public enum Purpose {
        /** A method's return type: everything, {@code void} included. */
        RETURN_TYPE,
        /** A method parameter: everything a variable can be declared of, so no {@code void}. */
        PARAMETER,
        /** A project variable: the types with a value somebody can write down. See {@link BotType#storable()}. */
        VARIABLE
    }

    public BotTypePicker(Purpose purpose) {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        for (BotType.Group group : BotType.Group.values()) {
            List<MenuItem> items = BotType.in(group).stream()
                    .filter(t -> offers(purpose, t))
                    .map(this::singleItem)
                    .toList();
            if (!items.isEmpty()) {
                Menu menu = new Menu(group.label());
                menu.getItems().addAll(items);
                getItems().add(menu);
            }
        }
        getItems().add(listMenu(purpose));

        choice.addListener((obs, old, now) -> setText(now == null ? "Choose a type…" : now.label()));
        choice.set(BotType.Choice.of(purpose == Purpose.RETURN_TYPE ? BotType.NOTHING : BotType.TEXT));
    }

    private static boolean offers(Purpose purpose, BotType type) {
        return switch (purpose) {
            case RETURN_TYPE -> true;
            case PARAMETER -> type.declarable();
            case VARIABLE -> type.storable();
        };
    }

    private MenuItem singleItem(BotType type) {
        MenuItem item = new MenuItem(type.label());
        item.setOnAction(e -> choice.set(BotType.Choice.of(type)));
        return item;
    }

    /** {@code List of ▸ <group> ▸ <type>} — the same tree again, one level down. */
    private Menu listMenu(Purpose purpose) {
        Menu listOf = new Menu("List of");
        for (BotType.Group group : BotType.Group.values()) {
            Menu groupMenu = new Menu(group.label());
            for (BotType type : BotType.in(group)) {
                if (!type.listable() || !offers(purpose, type)) continue;
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
