package com.botmaker.studio.palette;

/**
 * Palette grouping for {@link ExpressionType}s — drives the section ordering of the type-aware
 * insert/replace menus built by {@code ui.render.menu.ExpressionMenu}. Mirrors
 * {@link BlockCategory} for statements.
 */
public enum ExpressionCategory {
    LITERAL("Values", "◆"),
    REFERENCE("References", "𝑥"),
    MATH("Math", "×"),
    COMPARISON("Comparison", "≠"),
    LOGIC("Logic", "&&"),
    STRUCTURE("Structure", "☰");

    private final String label;
    private final String icon;

    ExpressionCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    /** One-glyph icon for this section's submenu; see {@link ExpressionType#icon()} for why it's a glyph. */
    public String icon() {
        return icon;
    }
}
