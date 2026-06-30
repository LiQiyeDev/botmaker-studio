package com.botmaker.ui.render.theme;

import com.botmaker.palette.BlockCategory;
import javafx.geometry.Insets;
import javafx.scene.Node;

/**
 * Fluent builder for applying styles to JavaFX nodes.
 * Replaces inline style strings with type-safe, themed styling.
 */
public class StyleBuilder {
    private final StringBuilder styleString = new StringBuilder();
    private final BlockTheme theme;

    private StyleBuilder(BlockTheme theme) {
        this.theme = theme;
    }

    public static StyleBuilder create() {
        return new StyleBuilder(BlockTheme.current());
    }

    public static StyleBuilder withTheme(BlockTheme theme) {
        return new StyleBuilder(theme);
    }

    // Background
    public StyleBuilder backgroundColor(String color) {
        styleString.append("-fx-background-color: ").append(color).append("; ");
        return this;
    }

    public StyleBuilder backgroundGradient(String from, String to) {
        styleString.append("-fx-background-color: linear-gradient(to bottom, ")
                .append(from).append(" 0%, ")
                .append(to).append(" 100%); ");
        return this;
    }

    // Text
    public StyleBuilder textColor(String color) {
        styleString.append("-fx-text-fill: ").append(color).append("; ");
        return this;
    }

    public StyleBuilder fontSize(double size) {
        styleString.append("-fx-font-size: ").append(size).append("px; ");
        return this;
    }

    public StyleBuilder fontWeight(String weight) {
        styleString.append("-fx-font-weight: ").append(weight).append("; ");
        return this;
    }

    public StyleBuilder fontFamily(String family) {
        styleString.append("-fx-font-family: ").append(family).append("; ");
        return this;
    }

    // Border
    public StyleBuilder borderColor(String color) {
        styleString.append("-fx-border-color: ").append(color).append("; ");
        return this;
    }

    public StyleBuilder borderWidth(double width) {
        styleString.append("-fx-border-width: ").append(width).append("px; ");
        return this;
    }

    public StyleBuilder borderWidth(double top, double right, double bottom, double left) {
        styleString.append("-fx-border-width: ")
                .append(top).append("px ")
                .append(right).append("px ")
                .append(bottom).append("px ")
                .append(left).append("px; ");
        return this;
    }

    public StyleBuilder borderRadius(double radius) {
        styleString.append("-fx-border-radius: ").append(radius).append("px; ");
        return this;
    }

    public StyleBuilder borderStyle(String style) {
        styleString.append("-fx-border-style: ").append(style).append("; ");
        return this;
    }

    // Effects
    public StyleBuilder backgroundRadius(double radius) {
        styleString.append("-fx-background-radius: ").append(radius).append("px; ");
        return this;
    }

    public StyleBuilder padding(double padding) {
        styleString.append("-fx-padding: ").append(padding).append("px; ");
        return this;
    }

    public StyleBuilder padding(double top, double right, double bottom, double left) {
        styleString.append("-fx-padding: ")
                .append(top).append("px ")
                .append(right).append("px ")
                .append(bottom).append("px ")
                .append(left).append("px; ");
        return this;
    }

    public StyleBuilder opacity(double opacity) {
        styleString.append("-fx-opacity: ").append(opacity).append("; ");
        return this;
    }

    public StyleBuilder cursor(String cursor) {
        styleString.append("-fx-cursor: ").append(cursor).append("; ");
        return this;
    }

    // Presets using theme
    public StyleBuilder asKeyword() {
        return this
                .textColor(theme.colors().keyword())
                .fontWeight(theme.typography().boldWeight())
                .fontSize(theme.typography().normal());
    }

    public StyleBuilder asType() {
        return this
                .textColor(theme.colors().type())
                .fontWeight(theme.typography().boldWeight())
                .fontSize(theme.typography().normal());
    }

    public StyleBuilder asOperator() {
        return this
                .textColor(theme.colors().operator())
                .fontSize(theme.typography().normal());
    }

    public StyleBuilder asBlockHeader(BlockCategory category) {
        String color = theme.colors().forCategory(category);
        return this
                .backgroundColor(color)
                .textColor("#FFFFFF")
                .fontWeight(theme.typography().boldWeight())
                .padding(8, 10, 8, 10)
                .backgroundRadius(theme.effects().normalRadius());
    }

    public StyleBuilder asPill(String backgroundColor) {
        return this
                .backgroundColor(backgroundColor)
                .backgroundRadius(theme.effects().pillRadius())
                .padding(3, 8, 3, 8);
    }

    // Terminal operation - build and return style string
    public String build() {
        return styleString.toString();
    }

    // Terminal operation - apply to node
    public void applyTo(Node node) {
        node.setStyle(build());
    }
}
