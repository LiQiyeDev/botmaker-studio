package com.botmaker.palette;

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
    UTILITY("Utility");

    private final String label;

    BlockCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
