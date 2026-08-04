package com.botmaker.studio.ui.render.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Centralized theme system for block styling.
 * Replaces scattered inline styles with consistent, reusable themes.
 */
public class BlockTheme {

    // Theme instances
    public static final BlockTheme DEFAULT = new BlockTheme();
    public static final BlockTheme DARK = createDarkTheme();
    public static final BlockTheme BLACK = createBlackTheme();
    public static final BlockTheme HIGH_CONTRAST = createHighContrastTheme();

    // Current active theme
    private static BlockTheme current = DEFAULT;

    // Theme change listeners
    private static final List<Consumer<ThemeType>> themeChangeListeners = new ArrayList<>();

    // Theme types
    public enum ThemeType {
        DEFAULT, DARK, BLACK, HIGH_CONTRAST
    }

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
        ThemeType oldTheme = getCurrentThemeType();
        current = theme;
        ThemeType newTheme = getCurrentThemeType();
        if (oldTheme != newTheme) {
            notifyThemeChange(newTheme);
            saveThemePreference();
        }
    }

    /**
     * Sets the theme based on the theme type.
     *
     * @param themeType the type of theme to activate
     */
    public static void setTheme(ThemeType themeType) {
        ThemeType oldTheme = getCurrentThemeType();
        switch (themeType) {
            case DEFAULT -> current = DEFAULT;
            case DARK -> current = DARK;
            case BLACK -> current = BLACK;
            case HIGH_CONTRAST -> current = HIGH_CONTRAST;
        }
        ThemeType newTheme = getCurrentThemeType();
        if (oldTheme != newTheme) {
            notifyThemeChange(newTheme);
            saveThemePreference();
        }
    }

    /**
     * Sets the theme based on the theme type string.
     *
     * @param themeName the name of the theme ("DEFAULT", "DARK", "BLACK", "HIGH_CONTRAST")
     */
    public static void setThemeByName(String themeName) {
        try {
            ThemeType themeType = ThemeType.valueOf(themeName.toUpperCase());
            setTheme(themeType);
        } catch (IllegalArgumentException e) {
            // Unknown theme name, fall back to default
            setTheme(ThemeType.DEFAULT);
        }
    }

    /**
     * Adds a listener for theme changes.
     *
     * @param listener the consumer to be called when the theme changes
     */
    public static void addThemeChangeListener(Consumer<ThemeType> listener) {
        themeChangeListeners.add(listener);
    }

    /**
     * Removes a theme change listener.
     *
     * @param listener the consumer to remove
     */
    public static void removeThemeChangeListener(Consumer<ThemeType> listener) {
        themeChangeListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners about a theme change.
     *
     * @param newTheme the new theme type
     */
    private static void notifyThemeChange(ThemeType newTheme) {
        themeChangeListeners.forEach(listener -> listener.accept(newTheme));
    }

    /**
     * Gets the current theme type.
     *
     * @return the current theme type
     */
    public static ThemeType getCurrentThemeType() {
        if (current == DEFAULT) return ThemeType.DEFAULT;
        if (current == DARK) return ThemeType.DARK;
        if (current == BLACK) return ThemeType.BLACK;
        if (current == HIGH_CONTRAST) return ThemeType.HIGH_CONTRAST;
        return ThemeType.DEFAULT;
    }

    /**
     * Gets all available theme types.
     *
     * @return array of available theme types
     */
    public static ThemeType[] getAvailableThemes() {
        return ThemeType.values();
    }

    // Theme persistence
    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String THEME_PREFS_KEY = "ui.theme";

    /**
     * Loads the saved theme preference and applies it.
     */
    public static void loadThemePreference() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            String savedTheme = prefs.get(THEME_PREFS_KEY, "DEFAULT");
            setThemeByName(savedTheme);
        } catch (Exception e) {
            // If loading fails, use default theme
            setTheme(ThemeType.DEFAULT);
        }
    }

    /**
     * Saves the current theme preference.
     */
    public static void saveThemePreference() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(THEME_PREFS_KEY, getCurrentThemeType().name());
            prefs.flush();
        } catch (Exception e) {
            // If saving fails, ignore
        }
    }

    /**
     * Initializes the theme system by loading saved preference or using default.
     * Should be called during application startup.
     */
    public static void initialize() {
        loadThemePreference();
    }

    // Accessors
    public ColorPalette colors() { return colors; }
    public Typography typography() { return typography; }
    public Spacing spacing() { return spacing; }
    public Effects effects() { return effects; }

    // Factory methods for themes
    private static BlockTheme createDarkTheme() {
        BlockTheme theme = new BlockTheme();
        theme.colors.applyDarkTheme();
        return theme;
    }

    private static BlockTheme createBlackTheme() {
        BlockTheme theme = new BlockTheme();
        theme.colors.applyBlackTheme();
        return theme;
    }

    private static BlockTheme createHighContrastTheme() {
        BlockTheme theme = new BlockTheme();
        // Configure high contrast colors
        return theme;
    }
}
