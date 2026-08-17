package com.botmaker.studio.ui.app.capture;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.ui.render.components.TagPicklist;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "name them all" step for a {@code Capture many} pass: one row per captured crop (thumbnail + name
 * field + a Discard toggle). {@code Save all} validates every kept row — sanitized, non-blank, and unique
 * both against templates already on disk ({@link ImageTemplateLibrary#exists}) and against the other kept
 * names in this same batch — then returns the kept {@code (name, image)} pairs for the caller to save.
 */
public final class BatchTemplateNamingDialog {

    private BatchTemplateNamingDialog() {}

    /**
     * A crop the user chose to keep, paired with its validated (sanitized, unique) template name and the
     * tags chosen for it.
     *
     * <p>{@code index} is the crop's position in the list handed to {@link #show} — carried through because
     * only the kept rows come back, so a caller that keyed something else off that list (the "Pick all"
     * session keys an <em>argument slot</em>) cannot recover it positionally once a row is discarded.
     */
    public record NamedTemplate(int index, String name, BufferedImage image, List<String> tags) {}

    /**
     * The dialog's whole result: the kept templates, each carrying its own tags.
     *
     * <p>The tags ride along rather than being applied here because the templates do not exist yet — the
     * caller is what saves them, so it is also what tags them, after the save succeeds.
     */
    public record Batch(List<NamedTemplate> templates) {

        static Batch none() {
            return new Batch(List.of());
        }

        /**
         * {@code name → tags} for the rows in {@code saved} — what {@code ImageTemplateLibrary.applyTags}
         * takes. Narrowed to the names that reached the disk, since a template whose save failed must not
         * leave an assignment behind for a file that isn't there.
         */
        public Map<String, List<String>> tagsFor(Collection<String> saved) {
            Map<String, List<String>> byName = new LinkedHashMap<>();
            for (NamedTemplate t : templates) {
                if (saved.contains(t.name())) byName.put(t.name(), t.tags());
            }
            return byName;
        }
    }

    private record Row(BufferedImage image, TextField name, CheckBox discard, TagPicklist tags) {}

    /**
     * Shows the modal naming dialog for {@code crops} and returns the kept, named templates (empty if the
     * user cancelled or discarded them all). Must be called on the FX thread.
     *
     * <p>{@code suggestedTag} seeds every row's tags — the open activity's tag when the capture started from
     * one, since that is the grouping the user would otherwise choose by hand. It is only a default: each
     * row's picklist can be changed, and the "Tag them all" control at the bottom re-applies a selection
     * across every kept row for the case where the whole batch belongs together.
     */
    public static Batch show(Window owner, ProjectConfig config, List<BufferedImage> crops, String suggestedTag) {
        Dialog<Batch> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Name captured templates");
        dialog.setHeaderText("Name each template, or tick Discard to skip it.");

        ButtonType saveAll = new ButtonType("Save all", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveAll, ButtonType.CANCEL);

        List<String> seedTags = suggestedTag == null ? List.of() : List.of(suggestedTag);
        List<Row> rows = new ArrayList<>();
        VBox list = new VBox(8);
        list.setPadding(new Insets(12));
        for (int i = 0; i < crops.size(); i++) {
            BufferedImage crop = crops.get(i);
            TagPicklist tags = new TagPicklist(config);
            tags.select(seedTags);
            Row row = new Row(crop, new TextField(), new CheckBox("Discard"), tags);
            row.name().setPromptText("template name");
            rows.add(row);
            list.getChildren().add(buildRow(i + 1, row));
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(420, 8 + crops.size() * 88));

        TagPicklist batchTags = new TagPicklist(config);
        batchTags.select(seedTags);
        Button applyToAll = new Button("Apply to all");
        applyToAll.setOnAction(e -> {
            List<String> chosen = batchTags.selected();
            for (Row row : rows) {
                row.tags().reloadCatalog();   // a tag declared in the batch picklist has to reach the rows
                row.tags().select(chosen);
            }
        });
        HBox tagRow = new HBox(8, new Label("Tag them all as:"), batchTags, applyToAll);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        tagRow.setPadding(new Insets(4, 12, 0, 12));
        HBox.setHgrow(batchTags, javafx.scene.layout.Priority.ALWAYS);

        VBox pane = new VBox(8, scroll, tagRow);
        dialog.getDialogPane().setContent(pane);

        // Intercept "Save all" so validation failures keep the dialog open instead of closing it.
        dialog.getDialogPane().lookupButton(saveAll).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (validate(owner, config, rows) == null) e.consume();
        });

        dialog.setResultConverter(bt -> {
            if (bt != saveAll) return Batch.none();
            List<NamedTemplate> kept = validate(owner, config, rows);
            return kept == null ? Batch.none() : new Batch(kept);
        });
        return dialog.showAndWait().orElse(Batch.none());
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
        // Discarded rows grey out both editors — there is nothing to name or file.
        row.name().disableProperty().bind(row.discard().selectedProperty());
        row.tags().disableProperty().bind(row.discard().selectedProperty());

        HBox box = new HBox(10, badge, thumb, row.name(), row.tags(), row.discard());
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
            if (ImageTemplateLibrary.isReservedName(name)) {
                return fail(owner, "Row " + (i + 1) + ": \"" + name + "\" is reserved — choose another name.");
            }
            seen.add(lower);
            kept.add(new NamedTemplate(i, name, row.image(), row.tags().selected()));
        }
        return kept;
    }

    private static List<NamedTemplate> fail(Window owner, String message) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING, message);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
        return null;
    }
}
