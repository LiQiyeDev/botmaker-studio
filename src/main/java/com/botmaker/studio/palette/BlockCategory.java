package com.botmaker.studio.palette;

/**
 * Palette grouping for {@link BlockType}s. Drives palette/menu sections and per-category theming
 * (see {@code ui.render.theme.ColorPalette#forCategory}).
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
    /**
     * Home category for the promoted find/click/wait bot actions, plus the non-promoted "Find Image → Do
     * Actions" body block — so a small "Vision" submenu holds that one entry.
     */
    VISION("Vision"),
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
}
