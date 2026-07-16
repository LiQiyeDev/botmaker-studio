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

}