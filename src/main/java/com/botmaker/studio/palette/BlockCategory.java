package com.botmaker.studio.palette;

/**
 * Palette grouping for {@link BlockType}s. Drives palette/menu sections and per-category theming.
 *
 * <p>A category is also how a block <em>looks</em>: {@link #styleClass()} names a CSS class whose colour is
 * defined once as a {@code -bm-cat-*} design token in {@code blocks.css}. A block's appearance should follow
 * from what it does, not from which class happened to draw it — before this, each block type had its own
 * hand-written rule (or an inline {@code setStyle} string) with its own copy of the hex.
 */
public enum BlockCategory {
    OUTPUT("Output", "🖨"),
    INPUT("Input", "⌨"),
    VARIABLES("Variables", "𝑥"),
    FLOW("Logic", "⑂"),
    LOOPS("Loops", "↻"),
    CONTROL("Control", "⏻"),
    // Game/emulator blocks. The SDK-facade launch calls (Game.*, Emulators.*) are now reached through the
    // generated per-facade submenus in the statement menu, so this category submenu carries only the non-facade
    // blocks (e.g. the "Connect Emulator" handle declaration). Still used flat by the overlay's Basic palette
    // (BlockCatalog.BOT_ACTIONS).
    GAME("Game", "🎮"),
    FUNCTIONS("Functions", "ƒ"),
    /** Vision/geometry variable declarations (Point, Rect, Size, MatchResult, …) — their own insert submenu. */
    BOT_VARIABLE("Declare Bot Variable", "◎"),
    UTILITY("Utility", "🔧");

    private final String label;
    private final String icon;

    BlockCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    /**
     * One-glyph icon for this category's menu entries — the statement-menu counterpart of
     * {@link ExpressionType#icon()}. Rendered as a menu item's graphic, never appended to its text, since the
     * menu's search filters on the text.
     */
    public String icon() {
        return icon;
    }

    /**
     * The CSS class for this category — {@code category-output}, {@code category-loops}, … Applied to a block's
     * root node by {@code AbstractCodeBlock.getUINode}; styled from the {@code -bm-cat-*} tokens in
     * {@code blocks.css}.
     */
    public String styleClass() {
        return "category-" + name().toLowerCase().replace('_', '-');
    }
}
