package com.botmaker.studio.ui.render.theme;

public class Effects {
    private double smallRadius = 4.0;
    private double normalRadius = 6.0;
    private double largeRadius = 10.0;
    private double pillRadius = 12.0;

    private String shadowMedium = "dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 2)";
    private String shadowLarge = "dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 4)";

    public double smallRadius() { return smallRadius; }
    public double normalRadius() { return normalRadius; }
    public double largeRadius() { return largeRadius; }
    public double pillRadius() { return pillRadius; }

    public String shadowSmall() {
        String shadowSmall = "dropshadow(gaussian, rgba(0,0,0,0.1), 2, 0, 0, 1)";
        return shadowSmall; }
    public String shadowMedium() { return shadowMedium; }
    public String shadowLarge() { return shadowLarge; }
}