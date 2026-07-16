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
    OUTPUT("Output"),
    INPUT("Input"),
    VARIABLES("Variables"),
    FLOW("Logic"),
    LOOPS("Loops"),
    CONTROL("Control"),
    // Game launch blocks carry this category, but they're promoted to the top-level bot actions
    // (BlockCatalog.BOT_ACTIONS), so no "Game" submenu is shown (empty categories are skipped).
    GAME("Game"),
    FUNCTIONS("Functions"),
    /** Vision/geometry variable declarations (Point, Rect, Size, MatchResult, …) — their own insert submenu. */
    BOT_VARIABLE("Declare Bot Variable"),
    UTILITY("Utility");

    private final String label;

    BlockCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
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
