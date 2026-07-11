package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "name them all" step for a {@code Capture many} pass: one row per captured crop (thumbnail + name
 * field + a Discard toggle). {@code Save all} validates every kept row — sanitized, non-blank, and unique
 * both against templates already on disk ({@link ImageTemplateLibrary#exists}) and against the other kept
 * names in this same batch — then returns the kept {@code (name, image)} pairs for the caller to save.
 */
public final class BatchTemplateNamingDialog {

    private BatchTemplateNamingDialog() {}

    /** A crop the user chose to keep, paired with its validated (sanitized, unique) template name. */
    public record NamedTemplate(String name, BufferedImage image) {}

    private record Row(BufferedImage image, TextField name, CheckBox discard) {}

    /**
     * Shows the modal naming dialog for {@code crops} and returns the kept, named templates (empty if the
     * user cancelled or discarded them all). Must be called on the FX thread.
     */
    public static List<NamedTemplate> show(Window owner, ProjectConfig config, List<BufferedImage> crops) {
        Dialog<List<NamedTemplate>> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Name captured templates");
        dialog.setHeaderText("Name each template, or tick Discard to skip it.");

        ButtonType saveAll = new ButtonType("Save all", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveAll, ButtonType.CANCEL);

        List<Row> rows = new ArrayList<>();
        VBox list = new VBox(8);
        list.setPadding(new Insets(12));
        for (int i = 0; i < crops.size(); i++) {
            BufferedImage crop = crops.get(i);
            Row row = new Row(crop, new TextField(), new CheckBox("Discard"));
            row.name().setPromptText("template name");
            rows.add(row);
            list.getChildren().add(buildRow(i + 1, row));
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(420, 8 + crops.size() * 88));
        dialog.getDialogPane().setContent(scroll);

        // Intercept "Save all" so validation failures keep the dialog open instead of closing it.
        dialog.getDialogPane().lookupButton(saveAll).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (validate(owner, config, rows) == null) e.consume();
        });

        dialog.setResultConverter(bt -> bt == saveAll ? validate(owner, config, rows) : List.of());
        return dialog.showAndWait().orElse(List.of());
    }

    private static HBox buildRow(int index, Row row) {
        Label badge = new Label(String.valueOf(index));
        badge.setMinWidth(20);
        badge.setAlignment(Pos.CENTER);

        ImageView thumb = new ImageView(ScreenCaptureService.toFxImage(row.image()));
        thumb.setPreserveRatio(true);
        thumb.setFitWidth(96);
        thumb.setFitHeight(72);

        HBox.setHgrow(row.name(), javafx.scene.layout.Priority.ALWAYS);
        // Discarded rows grey out the name field.
        row.name().disableProperty().bind(row.discard().selectedProperty());

        HBox box = new HBox(10, badge, thumb, row.name(), row.discard());
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4));
        box.setStyle("-fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        box.setMinHeight(Region.USE_PREF_SIZE);
        return box;
    }

    /**
     * Validates the kept rows and returns the resulting templates, or {@code null} (after warning) if any
     * kept name is blank, collides with an existing template, or duplicates another kept name in the batch.
     */
    private static List<NamedTemplate> validate(Window owner, ProjectConfig config, List<Row> rows) {
        List<NamedTemplate> kept = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.discard().isSelected()) continue;
            String name = ImageTemplateLibrary.sanitizeName(row.name().getText());
            if (name.isBlank()) {
                return fail(owner, "Row " + (i + 1) + ": please enter a name (or tick Discard).");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (seen.contains(lower)) {
                return fail(owner, "Row " + (i + 1) + ": the name \"" + name + "\" is used more than once.");
            }
            if (ImageTemplateLibrary.exists(config, name)) {
                return fail(owner, "Row " + (i + 1) + ": a template named \"" + name + "\" already exists.");
            }
            seen.add(lower);
            kept.add(new NamedTemplate(name, row.image()));
        }
        return kept;
    }

    private static List<NamedTemplate> fail(Window owner, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
        return null;
    }
}
