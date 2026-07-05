package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.services.ActivityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * User-side dialog: fill in the <em>values</em> for the activities the editor defined. The schema (names
 * + types) is read-only here; only values change. Each type gets an appropriate widget. Applying
 * delegates to {@link ActivityService} (rewrites {@code activities.json}; the generated class is
 * regenerated identically).
 */
public class SetActivityValuesDialog {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Window owner;
    private final ActivityService activityService;

    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final List<RowEditor> editors = new ArrayList<>();
    private Stage stage;

    public SetActivityValuesDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    /** A single activity's widget plus a reader that turns its UI state back into a {@link JsonNode}. */
    private record RowEditor(ActivityVariable activity, Supplier<JsonNode> read) {}

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Set Activity Values");

        List<ActivityVariable> activities = activityService.current().activities();

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        if (activities.isEmpty()) {
            root.getChildren().add(new Label("No activities defined yet. Add some in Project → Manage Activities."));
            root.getChildren().add(buildButtonBar(false));
        } else {
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(10);
            int rowIdx = 0;
            for (ActivityVariable a : activities) {
                grid.add(new Label(a.name()), 0, rowIdx);
                Node widget = buildWidget(a);
                grid.add(widget, 1, rowIdx);
                GridPane.setHgrow(widget, Priority.ALWAYS);
                rowIdx++;
            }
            ScrollPane scroll = new ScrollPane(grid);
            scroll.setFitToWidth(true);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            root.getChildren().addAll(scroll, buildButtonBar(true));
        }

        stage.setScene(new Scene(root, 460, 420));
        stage.show();
    }

    /** Builds the value widget for {@code a}, seeded from its current value, and registers its reader. */
    private Node buildWidget(ActivityVariable a) {
        JsonNode current = a.value();
        Node widget;
        Supplier<JsonNode> reader;
        switch (a.type()) {
            case BOOL -> {
                CheckBox cb = new CheckBox();
                cb.setSelected(current != null && current.asBoolean(false));
                widget = cb;
                reader = () -> NODES.booleanNode(cb.isSelected());
            }
            case INT -> {
                TextField tf = new TextField(current != null ? String.valueOf(current.asInt(0)) : "0");
                widget = tf;
                reader = () -> NODES.numberNode(parseIntOr(tf.getText(), 0));
            }
            case DOUBLE -> {
                TextField tf = new TextField(current != null ? String.valueOf(current.asDouble(0.0)) : "0.0");
                widget = tf;
                reader = () -> NODES.numberNode(parseDoubleOr(tf.getText(), 0.0));
            }
            case TIME -> {
                TextField tf = new TextField(current != null ? current.asText("00:00") : "00:00");
                tf.setPromptText("HH:mm");
                widget = tf;
                reader = () -> NODES.textNode(normalizeTime(tf.getText()));
            }
            case DATE -> {
                DatePicker dp = new DatePicker();
                dp.setValue(parseDateOr(current != null ? current.asText(null) : null, LocalDate.now()));
                widget = dp;
                reader = () -> NODES.textNode(dp.getValue() != null ? dp.getValue().toString() : LocalDate.now().toString());
            }
            default -> { // TEXT
                TextField tf = new TextField(current != null ? current.asText("") : "");
                widget = tf;
                reader = () -> NODES.textNode(tf.getText() == null ? "" : tf.getText());
            }
        }
        if (widget instanceof TextField || widget instanceof DatePicker) {
            ((javafx.scene.control.Control) widget).setMaxWidth(Double.MAX_VALUE);
        }
        editors.add(new RowEditor(a, reader));
        return widget;
    }

    private HBox buildButtonBar(boolean enableSave) {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        statusLabel.setStyle("-fx-text-fill: #b00020;");

        Button cancel = new Button("Close");
        cancel.setOnAction(e -> stage.close());
        Button apply = new Button("Save");
        apply.setDefaultButton(true);
        apply.setDisable(!enableSave);
        apply.setOnAction(e -> apply(apply, cancel));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, cancel, apply);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void apply(Button apply, Button cancel) {
        statusLabel.setText("");
        List<ActivityVariable> updated = new ArrayList<>();
        for (RowEditor ed : editors) {
            updated.add(ed.activity().withValue(ed.read().get()));
        }
        setBusy(apply, cancel, true);
        activityService.update(new ActivitiesConfig(updated))
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(apply, cancel, false);
                    if (err != null) {
                        statusLabel.setText(rootMessage(err));
                    } else {
                        stage.close();
                    }
                }));
    }

    private void setBusy(Button apply, Button cancel, boolean busy) {
        progress.setVisible(busy);
        apply.setDisable(busy);
        cancel.setDisable(busy);
    }

    // --- value parsing helpers (best-effort; fall back to defaults) ---

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDoubleOr(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String normalizeTime(String s) {
        try { return LocalTime.parse(s.trim()).toString(); }
        catch (DateTimeParseException e) { return "00:00"; }
    }

    private static LocalDate parseDateOr(String s, LocalDate def) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return def; }
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
