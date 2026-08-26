package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.ExpressionCategory;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;

/**
 * The single icon lookup for {@link StatementMenu} and {@link ExpressionMenu}, so the two can't drift on what a
 * category or an SDK facade looks like.
 *
 * <p>Categories already carry their own glyph ({@link BlockCategory#icon()} / {@link ExpressionCategory#icon()})
 * and so does each SDK facade ({@link FacadeEntry#icon()}); this class re-exports all
 * three so callers have one place to ask, applies {@link #FALLBACK} where a glyph is absent, and adds the one set
 * that has no record of its own: the menus' structural submenus ("Variables", "Call Function", …).
 *
 * <p>The facade glyphs used to live here as a second hand-maintained map keyed by simple name, which could drift
 * from the facade list itself. They are now the plugin's own answer, served with the facade they belong to.
 */
final class MenuIcons {

    private MenuIcons() {}

    /** Used for any facade/section this class doesn't name — never blank, so labels stay in one column. */
    static final String FALLBACK = "•";

    // Structural submenus the two menus build themselves (not backed by a palette record).
    static final String VARIABLES = "𝑥";
    static final String ACTIVITIES = "◎";
    static final String ACTIVITY_NAME = "🏷";
    static final String ENUM = "▤";
    static final String FUNCTION_CALL = "ƒ";
    static final String LIBRARY = "📚";
    static final String CAPTURE = "🎯";

    static String iconFor(BlockCategory category) {
        return category == null ? FALLBACK : category.icon();
    }

    static String iconFor(ExpressionCategory category) {
        return category == null ? FALLBACK : category.icon();
    }

    /** The glyph for a catalogued facade, or {@link #FALLBACK} for one that carries none. */
    static String iconFor(FacadeEntry facade) {
        String icon = facade == null ? null : facade.icon();
        return icon == null || icon.isBlank() ? FALLBACK : icon;
    }

    /**
     * A menu item's icon.
     *
     * <p>Set as the item's <b>graphic</b>, never folded into its text: both menus filter their search on
     * {@code getText()}, so an icon in the label would be searchable noise ("+" would match every arithmetic entry
     * by its glyph rather than its name). Fixed-width and centred so the labels beside them line up into a column.
     */
    static Node node(String glyph) {
        Label label = new Label(glyph == null || glyph.isBlank() ? FALLBACK : glyph);
        label.getStyleClass().add("menu-icon");
        label.setMinWidth(16);
        label.setAlignment(javafx.geometry.Pos.CENTER);
        return label;
    }

    /** Sets {@code glyph} as {@code item}'s graphic and returns the item, for use in a build expression. */
    static <T extends MenuItem> T decorate(T item, String glyph) {
        item.setGraphic(node(glyph));
        return item;
    }
}
