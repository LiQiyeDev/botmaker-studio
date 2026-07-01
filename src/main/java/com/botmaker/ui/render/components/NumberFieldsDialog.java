package com.botmaker.ui.render.components;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * A tiny modal popup with one integer {@link TextField} per named field — the manual-entry path for the
 * {@link RectPicker} / {@link PointPicker}. On OK it hands back the parsed values (missing/blank → 0).
 */
public final class NumberFieldsDialog {

    private NumberFieldsDialog() {}

    /**
     * Shows the dialog. {@code labels} names each field (e.g. {@code x, y, width, height}); {@code current}
     * seeds the fields (may be {@code null}). {@code onAccept} receives one int per label on OK.
     */
    public static void show(String title, String[] labels, int[] current, Window owner, Consumer<int[]> onAccept) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));

        TextField[] fields = new TextField[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextField tf = new TextField(current != null && i < current.length ? Integer.toString(current[i]) : "0");
            tf.setPrefColumnCount(5);
            fields[i] = tf;
            grid.add(new Label(labels[i]), 0, i);
            grid.add(tf, 1, i);
        }

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            int[] out = new int[labels.length];
            for (int i = 0; i < labels.length; i++) out[i] = parseInt(fields[i].getText());
            onAccept.accept(out);
        }
    }

    /** Lenient int parse: trims, drops a trailing type suffix, returns 0 on anything unparseable. */
    public static int parseInt(String raw) {
        if (raw == null) return 0;
        String s = raw.trim().replaceAll("[^0-9-].*$", "");
        try {
            return s.isEmpty() || s.equals("-") ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
