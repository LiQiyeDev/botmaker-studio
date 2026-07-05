package com.botmaker.studio.palette;

/**
 * Palette grouping for {@link ExpressionType}s — drives the section ordering of the type-aware
 * insert/replace menus built by {@code ui.render.menu.ExpressionMenuFactory}. Mirrors
 * {@link BlockCategory} for statements.
 */
public enum ExpressionCategory {
    LITERAL("Values"),
    REFERENCE("References"),
    MATH("Math"),
    COMPARISON("Comparison"),
    LOGIC("Logic"),
    STRUCTURE("Structure");

    private final String label;

    ExpressionCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
