package com.botmaker.studio.ui.render.components;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueShape;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.project.activity.ValueWire;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the type of a <em>project variable</em>: one {@link ValueType} the loaded plugins register, in one of
 * the four {@link ValueShape}s.
 *
 * <p><b>Why this is not {@link BotTypePicker}.</b> The two pickers answer questions that only looked alike.
 * A signature's type is a name javac has to accept, drawn from a list Studio itself curates, and its axis has
 * two positions — {@code T} and {@code List<T>}. A stored variable's type is a word written into
 * {@code activities.json}, drawn from whatever the project's plugins registered, and its axis has four:
 * fixing the set a value may come from is a project-variable idea and nothing else. Splitting them is what
 * lets the second one stop being an enum without dragging the first along.
 *
 * <p><b>The list is built at construction, from the plugins bound right now.</b> There is no static set to
 * enumerate — a project pinned to an SDK that never had a type simply never sees it — so the menu is a
 * function of {@link ValueWire#registered()} at the moment the dialog opens, which is also when the project
 * whose plugins answer it is the one open.
 *
 * <p><b>Grouping comes from the contract, not from here.</b> {@link ValueType#group()} is a free string a
 * plugin sets, so a second plugin files its types under a heading of its own without a constant being granted
 * to it; the <em>first</em> registration of a heading decides where that heading sits, which stops a plugin
 * reordering another's menu by naming it. A type with no group sits at the top level, in its registration
 * position.
 *
 * <p>A {@link MenuButton} rather than a {@code ComboBox}, and a {@code Shape ▸} radio group above the type
 * tree rather than a second copy of the tree — both for the reasons {@link BotTypePicker} records.
 */
public final class ValueTypePicker extends MenuButton {

    private final ObjectProperty<ValueChoice> choice = new SimpleObjectProperty<>();
    private final ToggleGroup shapeGroup = new ToggleGroup();

    public ValueTypePicker() {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        getItems().add(shapeMenu());
        getItems().add(new SeparatorMenuItem());
        getItems().addAll(typeMenus());

        choice.addListener((obs, old, now) -> {
            setText(now == null ? "Choose a type…" : now.label());
            refreshShapeMenu(now);
        });
        choice.set(ValueWire.one(ValueCatalog.TEXT_ID));
    }

    /**
     * The type tree, grouped by {@link ValueType#group()} in first-registration order.
     *
     * <p>An ungrouped type is added flat rather than under a "Other" heading nobody chose: the contract says
     * a blank group means the top level, and inventing a label for it here would be this host deciding
     * something the plugin declined to.
     */
    private List<MenuItem> typeMenus() {
        Map<String, List<ValueType>> grouped = new LinkedHashMap<>();
        for (ValueType type : ValueWire.registered()) {
            grouped.computeIfAbsent(type.group(), key -> new ArrayList<>()).add(type);
        }
        List<MenuItem> items = new ArrayList<>();
        for (Map.Entry<String, List<ValueType>> entry : grouped.entrySet()) {
            List<MenuItem> members = entry.getValue().stream().map(this::typeItem).toList();
            if (entry.getKey().isBlank()) {
                items.addAll(members);
            } else {
                Menu menu = new Menu(entry.getKey());
                menu.getItems().addAll(members);
                items.add(menu);
            }
        }
        return items;
    }

    /** The shape axis as a menu of its own, above the type tree — all four, since this is a stored value. */
    private Menu shapeMenu() {
        Menu menu = new Menu("Shape");
        for (ValueShape shape : ValueShape.values()) {
            RadioMenuItem item = new RadioMenuItem(shape.label());
            item.setToggleGroup(shapeGroup);
            item.setUserData(shape);
            item.setOnAction(e -> {
                ValueChoice now = choice.get();
                if (now != null) choice.set(new ValueChoice(now.type(), shape));
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    /**
     * Whether {@code type} can take {@code shape} — the same rule {@link ValueChoice}'s constructor corrects
     * to, said here so the menu greys what the record would silently rewrite.
     */
    private static boolean keeps(ValueType type, ValueShape shape) {
        return shape != ValueShape.ONE_OF || type.shapeable();
    }

    /** Ticks the shape in force, and greys the ones this type cannot take. */
    private void refreshShapeMenu(ValueChoice now) {
        for (Toggle toggle : shapeGroup.getToggles()) {
            RadioMenuItem item = (RadioMenuItem) toggle;
            ValueShape shape = (ValueShape) item.getUserData();
            boolean legal = now != null && keeps(now.type(), shape);
            item.setDisable(!legal);
            item.setSelected(now != null && now.shape() == shape);
        }
    }

    /** Picking a type keeps the shape in force, when that type can take it. */
    private MenuItem typeItem(ValueType type) {
        MenuItem item = new MenuItem(type.label());
        item.setOnAction(e -> {
            ValueChoice now = choice.get();
            ValueShape wanted = now == null ? ValueShape.ONE : now.shape();
            choice.set(new ValueChoice(type, keeps(type, wanted) ? wanted : ValueShape.ONE));
        });
        return item;
    }

    public ObjectProperty<ValueChoice> choiceProperty() {
        return choice;
    }

    public ValueChoice choice() {
        return choice.get();
    }

    public void setChoice(ValueChoice value) {
        choice.set(value);
    }
}
