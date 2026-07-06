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
    FUNCTIONS("Functions"),
    VISION("Vision"),
    /** Vision/geometry variable declarations (Point, Rect, Size, MatchResult, …) — their own insert submenu. */
    BOT_VARIABLE("Declare Bot Variable"),
    GAME("Game"),
    UTILITY("Utility");

    private final String label;

    BlockCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
