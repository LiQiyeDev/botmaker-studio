package com.botmaker.ui.render.components;

import javafx.scene.control.ComboBox;

import java.util.function.Consumer;

public class SelectorComponents {

    public static ComboBox<String> createOperatorSelector(String[] names, String[] symbols, String currentSymbol, Consumer<String> onSymbolChange) {
        ComboBox<String> selector = new ComboBox<>();
        selector.getItems().addAll(names);
        selector.getStyleClass().add("operator-selector"); // or math-operator-selector based on context
        selector.setEditable(false);

        // Find display name for current symbol
        String currentName = names[0];
        for (int i = 0; i < symbols.length; i++) {
            if (symbols[i].equals(currentSymbol)) {
                currentName = names[i];
                break;
            }
        }
        selector.setValue(currentName);

        selector.setOnAction(e -> {
            String selectedName = selector.getValue();
            String newSymbol = null;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(selectedName)) {
                    newSymbol = symbols[i];
                    break;
                }
            }
            if (newSymbol != null && !newSymbol.equals(currentSymbol)) {
                onSymbolChange.accept(newSymbol);
            }
        });

        return selector;
    }

    public static ComboBox<String> createSimpleSelector(String[] options, String current, Consumer<String> onChange) {
        ComboBox<String> selector = new ComboBox<>();
        selector.getItems().addAll(options);
        selector.setValue(current);
        selector.setOnAction(e -> {
            if (!selector.getValue().equals(current)) {
                onChange.accept(selector.getValue());
            }
        });
        return selector;
    }
}