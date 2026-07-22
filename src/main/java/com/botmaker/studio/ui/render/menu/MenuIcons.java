package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.ExpressionCategory;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;

import java.util.Map;

/**
 * The single icon lookup for {@link StatementMenu} and {@link ExpressionMenu}, so the two can't drift on what a
 * category or an SDK facade looks like.
 *
 * <p>Categories already carry their own glyph ({@link BlockCategory#icon()} / {@link ExpressionCategory#icon()});
 * this class re-exports them so callers have one place to ask, and adds the two sets that had no icon at all: the
 * SDK facades (whose submenus are generated from runtime-discovered method names, so there is no palette record to
 * hang a field on) and the menus' own structural submenus ("Variables", "Call Function", …).
 *
 * <p><b>Why the lookup lives here and not on the palette records.</b> {@code BlockType}/{@code ExpressionType} are
 * data-only catalogs; a facade icon is a rendering decision about a menu, and the facade list itself
 * ({@code SdkApi.FACADE_CLASSES}) is a list of plain strings. Prefixing at render time keeps the catalogs free of
 * presentation.
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

    /**
     * Facade → glyph. Keyed by the simple class name exactly as it appears in {@code SdkApi.FACADE_CLASSES};
     * a facade added there without an entry here simply renders {@link #FALLBACK}.
     */
    private static final Map<String, String> FACADE_ICONS = Map.ofEntries(
            Map.entry("Mouse", "🖱"),
            Map.entry("Keyboard", "⌨"),
            Map.entry("Wait", "⏱"),
            Map.entry("ImageFinder", "🔍"),
            Map.entry("ImageClicker", "👆"),
            Map.entry("ImageWaiter", "⏳"),
            Map.entry("Pixel", "🎨"),
            Map.entry("Text", "🔤"),
            Map.entry("VisionContext", "👁"),
            Map.entry("ClickConfig", "⚙"),
            Map.entry("Debug", "🐞"),
            Map.entry("Game", "🎮"),
            Map.entry("Target", "🚀"),
            Map.entry("Emulators", "📱"),
            Map.entry("Bot", "🤖"),
            Map.entry("Watchdog", "🐕"),
            Map.entry("Activity", "◎"),
            Map.entry("Source", "🎯"),
            Map.entry("Window", "🪟"),
            Map.entry("Bots", "🤖"));

    static String iconFor(BlockCategory category) {
        return category == null ? FALLBACK : category.icon();
    }

    static String iconFor(ExpressionCategory category) {
        return category == null ? FALLBACK : category.icon();
    }

    /** The glyph for an SDK facade class (simple name), or {@link #FALLBACK} for an unmapped one. */
    static String iconFor(String sdkFacade) {
        return FACADE_ICONS.getOrDefault(sdkFacade, FALLBACK);
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
