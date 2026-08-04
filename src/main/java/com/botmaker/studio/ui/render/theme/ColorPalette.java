package com.botmaker.studio.ui.render.theme;

/**
 * Colours for the few places that still build a style string in Java rather than in CSS.
 *
 * <p><b>Prefer CSS.</b> The design tokens in {@code css/blocks.css} ({@code -bm-*}) are the source of truth for
 * block colour; the values here are a mirror of them, kept only for code that has no stylesheet to reach for
 * (the drag-and-drop separator's inline styling, for one). Any new colour belongs in {@code blocks.css} — an
 * inline style also silently beats an author stylesheet in JavaFX, so a colour set here can't be overridden by
 * a rule there, which is exactly how the two drifted apart in the first place.
 *
 * <p>The per-category getters are gone: a block's category colour is now
 * {@code BlockCategory#styleClass()} + the {@code -bm-cat-*} tokens, so {@code forCategory} would be a second
 * copy of a palette that already exists in CSS. (It had no callers regardless — every block hard-coded its own
 * hex instead.) The {@code withOpacity}/{@code lighten}/{@code darken} helpers are gone too: they delegated to
 * private stubs that returned the literal string {@code "..."}, so every caller would have produced invalid
 * CSS. Use {@code derive(-bm-token, ±n%)} or an {@code rgba(...)} token in the stylesheet instead.
 */
public class ColorPalette {

    // UI element colors
    private String backgroundColor = "#FFFFFF";
    private String textColor = "#2C3E50";
    private String keywordColor = "#34495E";
    private String operatorColor = "#7F8C8D";
    private String typeColor = "#8E44AD";
    private String errorColor = "#E74C3C";
    private String warningColor = "#F39C12";
    private String successColor = "#2ECC71";

    // Accent colors
    private String primaryAccent = "#3498DB";
    private String secondaryAccent = "#95A5A6";
    private String hoverAccent = "#2980B9";

    public String background() { return backgroundColor; }
    public String text() { return textColor; }
    public String keyword() { return keywordColor; }
    public String operator() { return operatorColor; }
    public String type() { return typeColor; }
    public String error() { return errorColor; }
    public String warning() { return warningColor; }
    public String success() { return successColor; }
    public String primary() { return primaryAccent; }
    public String hover() { return hoverAccent; }

    // Theme application methods

    /**
     * Applies the black theme color scheme.
     * True black background with appropriate contrast colors for maximum legibility.
     */
    public void applyBlackTheme() {
        this.backgroundColor = "#000000";  // True black
        this.textColor = "#E0E0E0";      // Light gray for text on black
        this.keywordColor = "#BB86FC";    // Purple accent for keywords
        this.operatorColor = "#808080";    // Medium gray for operators
        this.typeColor = "#03DAC6";       // Teal for types
        this.errorColor = "#FF5555";      // Bright red for errors
        this.warningColor = "#FFA726";    // Orange for warnings
        this.successColor = "#4CAF50";    // Green for success
        this.primaryAccent = "#BB86FC";    // Primary accent (purple)
        this.secondaryAccent = "#666666";  // Secondary accent (dark gray)
        this.hoverAccent = "#9953E8";      // Hover accent (darker purple)
    }

    /**
     * Applies the default light theme color scheme.
     */
    public void applyDefaultTheme() {
        this.backgroundColor = "#FFFFFF";
        this.textColor = "#2C3E50";
        this.keywordColor = "#34495E";
        this.operatorColor = "#7F8C8D";
        this.typeColor = "#8E44AD";
        this.errorColor = "#E74C3C";
        this.warningColor = "#F39C12";
        this.successColor = "#2ECC71";
        this.primaryAccent = "#3498DB";
        this.secondaryAccent = "#95A5A6";
        this.hoverAccent = "#2980B9";
    }

    /**
     * Applies the dark theme color scheme.
     */
    public void applyDarkTheme() {
        this.backgroundColor = "#1E1E1E";  // Dark gray background
        this.textColor = "#D4D4D4";      // Light text
        this.keywordColor = "#569CD6";    // Blue for keywords
        this.operatorColor = "#9CDCFE";    // Light blue for operators
        this.typeColor = "#4EC9B0";      // Teal for types
        this.errorColor = "#F44747";      // Red for errors
        this.warningColor = "#CE9178";    // Orange for warnings
        this.successColor = "#6A9955";    // Green for success
        this.primaryAccent = "#569CD6";    // Primary accent
        this.secondaryAccent = "#484848";  // Secondary accent
        this.hoverAccent = "#7CE7F4";      // Hover accent
    }

}
