package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.palette.BotType;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;

import java.util.List;

/**
 * Picks one of the curated {@link BotType}s, in one of the {@link BotType.Shape}s.
 *
 * <p>A {@link MenuButton} rather than a {@code ComboBox} because the list is grouped and a combo box has no
 * grouping: its items are one flat run, and the two ways round that are a cell factory that fakes
 * unselectable header rows or twenty entries with no shape at all. A menu's submenus are what grouping
 * actually is — and they give the shape axis somewhere to live that costs nothing when unused.
 *
 * <p><b>Two questions, two controls.</b> The shape used to be a second copy of the entire type tree under
 * {@code List of ▸}, so the third shape would have been a third copy of it. Here the tree is built once and a
 * {@code Shape ▸} radio group sits above it: picking a type keeps the shape, picking a shape keeps the type,
 * and a shape the chosen type cannot take is greyed rather than absent.
 */
public final class BotTypePicker extends MenuButton {

    private final ObjectProperty<BotType.Choice> choice = new SimpleObjectProperty<>();
    private final ToggleGroup shapeGroup = new ToggleGroup();

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
        /**
         * A local variable in the file being edited. The same set as {@link #PARAMETER} — a variable is a
         * variable — but with no shape axis: retyping a declaration goes through
         * {@code CodeEditor.replaceVariableType}, which writes one {@code ResolvedType} as one type node, and
         * {@code List<Point>} is not a name that survives that trip. Offering a shape the write path cannot
         * spell would be a control that silently does the wrong thing.
         */
        LOCAL_VARIABLE,
        /** A project variable: the types with a value somebody can write down. See {@link BotType#storable()}. */
        VARIABLE
    }

    public BotTypePicker(Purpose purpose) {
        getStyleClass().add("bot-type-picker");
        setMaxWidth(Double.MAX_VALUE);

        // A purpose with one shape has no axis to offer: a "Shape ▸" menu holding a single ticked radio is a
        // control that cannot be operated, and it pushes the type groups down a row for nothing.
        if (shapes(purpose).size() > 1) {
            getItems().add(shapeMenu(purpose));
            getItems().add(new SeparatorMenuItem());
        }
        for (BotType.Group group : BotType.Group.values()) {
            List<MenuItem> items = BotType.in(group).stream()
                    .filter(t -> offers(purpose, t))
                    .map(this::typeItem)
                    .toList();
            if (!items.isEmpty()) {
                Menu menu = new Menu(group.label());
                menu.getItems().addAll(items);
                getItems().add(menu);
            }
        }

        choice.addListener((obs, old, now) -> {
            setText(now == null ? "Choose a type…" : now.label());
            refreshShapeMenu(now);
        });
        choice.set(BotType.Choice.of(purpose == Purpose.RETURN_TYPE ? BotType.NOTHING : BotType.TEXT));
    }

    private static boolean offers(Purpose purpose, BotType type) {
        return switch (purpose) {
            case RETURN_TYPE -> true;
            case PARAMETER, LOCAL_VARIABLE -> type.declarable();
            case VARIABLE -> type.storable();
        };
    }

    /**
     * Which shapes this purpose can express. The two set-shaped ones — {@link BotType.Shape#ONE_OF} and
     * {@link BotType.Shape#ANY_OF} — are project-variable ideas and nothing else: fixing the set a value may
     * come from is a question about something somebody configures, and a method parameter has nobody to ask.
     * So a signature's axis has only {@code T} and {@code List<T>}, the latter spelled
     * {@link BotType.Shape#OPEN_LIST} because there is no set behind it to name.
     */
    private static List<BotType.Shape> shapes(Purpose purpose) {
        return switch (purpose) {
            case VARIABLE -> List.of(BotType.Shape.values());
            case RETURN_TYPE, PARAMETER -> List.of(BotType.Shape.ONE, BotType.Shape.OPEN_LIST);
            case LOCAL_VARIABLE -> List.of(BotType.Shape.ONE);
        };
    }

    /**
     * The shape axis as a menu of its own, above the type tree.
     *
     * <p>It used to be a second copy of the whole tree under {@code List of ▸}, which is why a third shape
     * would have been a third copy. Shape and type are independent questions, so they are two controls: the
     * tree is built once and either half can be changed without restating the other.
     */
    private Menu shapeMenu(Purpose purpose) {
        Menu menu = new Menu("Shape");
        for (BotType.Shape shape : shapes(purpose)) {
            RadioMenuItem item = new RadioMenuItem(shape.label());
            item.setToggleGroup(shapeGroup);
            item.setUserData(shape);
            item.setOnAction(e -> {
                BotType.Choice now = choice.get();
                if (now != null) choice.set(new BotType.Choice(now.type(), shape));
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    /** Whether {@code type} can take {@code shape} — the same rule {@code BotType.Choice} enforces. */
    private static boolean keeps(BotType type, BotType.Shape shape) {
        return switch (shape) {
            case ONE -> true;
            case ONE_OF -> type.shapeable();
            case ANY_OF, OPEN_LIST -> type.listable();
        };
    }

    /** Ticks the shape in force, and greys the ones this type cannot take. */
    private void refreshShapeMenu(BotType.Choice now) {
        for (Toggle toggle : shapeGroup.getToggles()) {
            RadioMenuItem item = (RadioMenuItem) toggle;
            BotType.Shape shape = (BotType.Shape) item.getUserData();
            boolean legal = now != null && keeps(now.type(), shape);
            item.setDisable(!legal);
            item.setSelected(now != null && now.shape() == shape);
        }
    }

    /** Picking a type keeps the shape in force, when that type can take it. */
    private MenuItem typeItem(BotType type) {
        MenuItem item = new MenuItem(type.label());
        item.setOnAction(e -> {
            BotType.Choice now = choice.get();
            BotType.Shape wanted = now == null ? BotType.Shape.ONE : now.shape();
            choice.set(new BotType.Choice(type, keeps(type, wanted) ? wanted : BotType.Shape.ONE));
        });
        return item;
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
