package com.botmaker.studio.ui.render.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;

public class StyleComponents {

    /**
     * Creates a "Pill" shaped container (used for parameters and method calls).
     */
    public static HBox createPillContainer(String backgroundColor, Node... content) {
        HBox container = new HBox(4);
        container.setAlignment(Pos.CENTER_LEFT);
        // We use inline styles here because these often have dynamic colors,
        // but ideally this should map to CSS classes.
        container.setStyle(
                "-fx-background-color: " + backgroundColor + ";" +
                        "-fx-background-radius: 12;" +
                        "-fx-padding: 3 8 3 8;"
        );
        if (content != null) {
            container.getChildren().addAll(content);
        }
        return container;
    }
}