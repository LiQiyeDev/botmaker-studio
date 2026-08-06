package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.ExpressionCategory;
import com.botmaker.studio.palette.SdkType;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;

/**
 * The single icon lookup for {@link StatementMenu} and {@link ExpressionMenu}, so the two can't drift on what a
 * category or an SDK facade looks like.
 *
 * <p>Categories already carry their own glyph ({@link BlockCategory#icon()} / {@link ExpressionCategory#icon()})
 * and so, since it became a typed enum, does each SDK facade ({@link SdkType#icon()}); this class re-exports all
 * three so callers have one place to ask, applies {@link #FALLBACK} where a glyph is absent, and adds the one set
 * that has no record of its own: the menus' structural submenus ("Variables", "Call Function", …).
 *
 * <p>The facade glyphs used to live here as a second hand-maintained map keyed by simple name, which could drift
 * from the facade list itself. They now sit on {@link SdkType} beside the class they belong to.
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

    /** The glyph for an SDK type, or {@link #FALLBACK} for one that carries none. */
    static String iconFor(SdkType sdkType) {
        String icon = sdkType == null ? null : sdkType.icon();
        return icon == null ? FALLBACK : icon;
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
        label.setStyle("-fx-font-family: 'Segoe UI Symbol'; -fx-text-fill: #555;");
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
