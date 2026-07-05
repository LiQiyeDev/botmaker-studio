package com.botmaker.studio.ui.render.theme;

public class Typography {
    // Font families
    private String primaryFont = "'Segoe UI', sans-serif";
    private String monoFont = "'Consolas', monospace";

    // Font sizes
    private double tinySize = 9.0;
    private double smallSize = 10.0;
    private double normalSize = 11.0;
    private double mediumSize = 13.0;
    private double largeSize = 16.0;
    private double headingSize = 20.0;

    // Font weights
    private String normalWeight = "normal";
    private String boldWeight = "bold";

    public String primaryFont() { return primaryFont; }
    public String monoFont() { return monoFont; }

    public double tiny() { return tinySize; }
    public double small() { return smallSize; }
    public double normal() { return normalSize; }
    public double medium() { return mediumSize; }
    public double large() { return largeSize; }
    public double heading() { return headingSize; }

    public String normalWeight() { return normalWeight; }
    public String boldWeight() { return boldWeight; }
}