package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
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
import javafx.scene.control.Separator;
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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * User-side dialog: fill in the <em>values</em> for the activities the editor defined. The schema (activity
 * names, params + types, globals) is read-only here; only values change — each activity's enable flag
 * ("whether to do it") plus its param values and the globals. Applying delegates to {@link ActivityService},
 * which rewrites {@code activities.json} and regenerates the sidecar classes.
 */
public class SetActivityValuesDialog {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Window owner;
    private final ActivityService activityService;

    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final List<ActivityEditor> activityEditors = new ArrayList<>();
    private final List<ValueEditor> globalEditors = new ArrayList<>();
    private Stage stage;

    public SetActivityValuesDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    /** An activity's enable-flag reader plus a reader per param. */
    private record ActivityEditor(ActivityDefinition def, BooleanSupplier enabled, List<ValueEditor> params) {}

    /** A single value widget's variable plus a reader turning its UI state back into a {@link JsonNode}. */
    private record ValueEditor(ActivityVariable variable, Supplier<JsonNode> read) {}

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Set Activity Values");

        ActivitiesConfig current = activityService.current();
        List<ActivityDefinition> activities = current.activities();
        List<ActivityVariable> globals = current.globals();

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        if (activities.isEmpty() && globals.isEmpty()) {
            root.getChildren().add(new Label("No activities defined yet. Add some in Project → Manage Activities."));
            root.getChildren().add(buildButtonBar(false));
            stage.setScene(new Scene(root, 460, 200));
            stage.show();
            return;
        }

        VBox sections = new VBox(16);
        for (ActivityDefinition a : activities) {
            sections.getChildren().add(buildActivitySection(a));
        }
        if (!globals.isEmpty()) {
            if (!activities.isEmpty()) sections.getChildren().add(new Separator());
            sections.getChildren().add(buildGlobalsSection(globals));
        }

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().addAll(scroll, buildButtonBar(true));

        stage.setScene(new Scene(root, 480, 480));
        stage.show();
    }

    private VBox buildActivitySection(ActivityDefinition a) {
        CheckBox enabledBox = new CheckBox("Enabled");
        enabledBox.setSelected(a.enabled());
        Label title = new Label(a.name());
        title.setStyle("-fx-font-weight: bold;");
        HBox header = new HBox(12, title, enabledBox);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, header);
        if (!a.description().isBlank()) {
            Label desc = new Label(a.description());
            desc.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            box.getChildren().add(desc);
        }

        List<ValueEditor> paramEditors = new ArrayList<>();
        if (!a.params().isEmpty()) {
            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(8);
            grid.setPadding(new Insets(0, 0, 0, 16));
            int rowIdx = 0;
            for (ActivityVariable p : a.params()) {
                grid.add(new Label(p.name()), 0, rowIdx);
                Node widget = buildWidget(p, paramEditors);
                grid.add(widget, 1, rowIdx);
                GridPane.setHgrow(widget, Priority.ALWAYS);
                rowIdx++;
            }
            box.getChildren().add(grid);
        }
        activityEditors.add(new ActivityEditor(a, enabledBox::isSelected, paramEditors));
        return box;
    }

    private VBox buildGlobalsSection(List<ActivityVariable> globals) {
        Label title = new Label("Global variables");
        title.setStyle("-fx-font-weight: bold;");
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        int rowIdx = 0;
        for (ActivityVariable g : globals) {
            grid.add(new Label(g.name()), 0, rowIdx);
            Node widget = buildWidget(g, globalEditors);
            grid.add(widget, 1, rowIdx);
            GridPane.setHgrow(widget, Priority.ALWAYS);
            rowIdx++;
        }
        return new VBox(8, title, grid);
    }

    /** Builds the value widget for {@code a}, seeded from its current value, and registers its reader in {@code sink}. */
    private Node buildWidget(ActivityVariable a, List<ValueEditor> sink) {
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
        sink.add(new ValueEditor(a, reader));
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
        List<ActivityDefinition> activities = new ArrayList<>();
        for (ActivityEditor ae : activityEditors) {
            List<ActivityVariable> params = new ArrayList<>();
            for (ValueEditor pe : ae.params()) {
                params.add(pe.variable().withValue(pe.read().get()));
            }
            activities.add(new ActivityDefinition(
                    ae.def().name(), ae.enabled().getAsBoolean(), ae.def().description(), params));
        }
        List<ActivityVariable> globals = new ArrayList<>();
        for (ValueEditor ge : globalEditors) {
            globals.add(ge.variable().withValue(ge.read().get()));
        }
        setBusy(apply, cancel, true);
        activityService.update(new ActivitiesConfig(activities, globals))
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
