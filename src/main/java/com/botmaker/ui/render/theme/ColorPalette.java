package com.botmaker.ui.render.theme;

import com.botmaker.palette.BlockCategory;

public class ColorPalette {
    // Category colors (matching your current palette)
    private String outputColor = "#3498DB";
    private String inputColor = "#9B59B6";
    private String variablesColor = "#F39C12";
    private String flowColor = "#E67E22";
    private String loopsColor = "#2ECC71";
    private String controlColor = "#E74C3C";
    private String functionsColor = "#8E44AD";
    private String visionColor = "#1ABC9C"; // Vision Color
    private String gameColor = "#16A085"; // Game / launch actions
    private String utilityColor = "#7F8C8D";

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

    // Getters for each color category
    public String forCategory(BlockCategory category) {
        return switch (category) {
            case OUTPUT -> outputColor;
            case INPUT -> inputColor;
            case VARIABLES -> variablesColor;
            case FLOW -> flowColor;
            case LOOPS -> loopsColor;
            case CONTROL -> controlColor;
            case FUNCTIONS -> functionsColor;
            case VISION -> visionColor;
            case GAME -> gameColor;
            case UTILITY -> utilityColor;
        };
    }

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

    // Derived colors
    public String withOpacity(String color, double opacity) {
        // Convert hex to rgba
        return String.format("rgba(%s, %.2f)", hexToRgb(color), opacity);
    }

    public String lighten(String color, double amount) {
        // Lighten color by percentage
        return adjustBrightness(color, amount);
    }

    public String darken(String color, double amount) {
        // Darken color by percentage
        return adjustBrightness(color, -amount);
    }

    private String hexToRgb(String hex) {
        // Implementation
        return "...";
    }

    private String adjustBrightness(String color, double amount) {
        // Implementation
        return "...";
    }
}