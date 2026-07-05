package com.botmaker.studio.ui.render.theme;

/**
 * Centralized theme system for block styling.
 * Replaces scattered inline styles with consistent, reusable themes.
 */
public class BlockTheme {

    // Theme instances
    public static final BlockTheme DEFAULT = new BlockTheme();
    public static final BlockTheme DARK = createDarkTheme();
    public static final BlockTheme HIGH_CONTRAST = createHighContrastTheme();

    // Current active theme
    private static BlockTheme current = DEFAULT;

    // Color definitions
    private final ColorPalette colors;
    private final Typography typography;
    private final Spacing spacing;
    private final Effects effects;

    private BlockTheme() {
        this.colors = new ColorPalette();
        this.typography = new Typography();
        this.spacing = new Spacing();
        this.effects = new Effects();
    }

    public static BlockTheme current() {
        return current;
    }

    public static void setTheme(BlockTheme theme) {
        current = theme;
    }

    // Accessors
    public ColorPalette colors() { return colors; }
    public Typography typography() { return typography; }
    public Spacing spacing() { return spacing; }
    public Effects effects() { return effects; }

    // Factory methods for themes
    private static BlockTheme createDarkTheme() {
        BlockTheme theme = new BlockTheme();
        // Configure dark colors
        return theme;
    }

    private static BlockTheme createHighContrastTheme() {
        BlockTheme theme = new BlockTheme();
        // Configure high contrast colors
        return theme;
    }
}