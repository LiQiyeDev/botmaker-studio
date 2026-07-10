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
    // Game sits directly after Control so "Launch Program/Steam Game" follows "Wait (ms)" in the menu.
    GAME("Game"),
    FUNCTIONS("Functions"),
    /**
     * Retained only so the promoted find/click/wait bot actions have a home category; it has no submenu
     * of its own (the former lambda vision blocks now live under Loops/Logic, so this group is empty).
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
