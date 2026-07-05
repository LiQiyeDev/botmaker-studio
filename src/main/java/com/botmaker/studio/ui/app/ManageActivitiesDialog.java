package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.services.ActivityService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-side dialog: define the project's <em>activities</em> (global config variables) — their names
 * and types. Values are filled separately by the user via {@link SetActivityValuesDialog}. Applying
 * delegates to {@link ActivityService}, which rewrites {@code activities.json} and regenerates the
 * {@code Activities} class off the FX thread.
 */
public class ManageActivitiesDialog {

    private final Window owner;
    private final ActivityService activityService;

    private final ObservableList<ActivityVariable> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private Stage stage;

    public ManageActivitiesDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Activities");

        rows.setAll(activityService.current().activities());

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildTable(), buildAddRow(), buildButtonBar());

        stage.setScene(new Scene(root, 520, 440));
        stage.show();
    }

    private VBox buildTable() {
        TableView<ActivityVariable> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPlaceholder(new Label("No activities yet. Add one below."));

        TableColumn<ActivityVariable, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name()));
        nameCol.setCellFactory(col -> new NameCell());

        TableColumn<ActivityVariable, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().type().displayName()));
        typeCol.setCellFactory(col -> new TypeCell());

        table.getColumns().addAll(List.of(nameCol, typeCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> removeBtn.setDisable(sel == null));
        removeBtn.setOnAction(e -> {
            ActivityVariable sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) rows.remove(sel);
        });

        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Activities");
        heading.setStyle("-fx-font-weight: bold;");
        Label hint = new Label("Double-click a name or type to edit it. Values are set in Project → Set Activity Values.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        VBox box = new VBox(6, heading, table, hint, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    /** Editable name cell; commits replace the immutable row, keeping type + value. */
    private final class NameCell extends TableCell<ActivityVariable, String> {
        private final TextField field = new TextField();

        NameCell() {
            field.setOnAction(e -> commit());
            field.focusedProperty().addListener((o, was, is) -> { if (!is && isEditing()) commit(); });
        }

        private void commit() {
            int idx = getIndex();
            String name = field.getText() == null ? "" : field.getText().trim();
            if (idx >= 0 && idx < rows.size() && !name.isEmpty()) {
                ActivityVariable r = rows.get(idx);
                if (!name.equals(r.name())) rows.set(idx, new ActivityVariable(name, r.type(), r.value()));
            }
            cancelEdit();
        }

        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            field.setText(getItem());
            setText(null); setGraphic(field);
            field.requestFocus(); field.selectAll();
        }
        @Override public void cancelEdit() { super.cancelEdit(); setText(getItem()); setGraphic(null); }
        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) { setText(null); setGraphic(null); }
            else if (isEditing()) { field.setText(item); setText(null); setGraphic(field); }
            else { setText(item); setGraphic(null); }
        }
    }

    /** Editable type cell backed by a {@code ComboBox<ActivityType>}; changing type resets the value. */
    private final class TypeCell extends TableCell<ActivityVariable, String> {
        private final ComboBox<ActivityType> combo = new ComboBox<>(FXCollections.observableArrayList(ActivityType.values()));

        TypeCell() {
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> {
                if (isEditing() && combo.getValue() != null) commit(combo.getValue());
            });
        }

        private void commit(ActivityType type) {
            int idx = getIndex();
            if (idx >= 0 && idx < rows.size()) {
                ActivityVariable r = rows.get(idx);
                if (type != r.type()) rows.set(idx, ActivityVariable.create(r.name(), type));
            }
            cancelEdit();
        }

        private ActivityType currentType() {
            int idx = getIndex();
            return (idx >= 0 && idx < rows.size()) ? rows.get(idx).type() : ActivityType.TEXT;
        }

        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            combo.setValue(currentType());
            setText(null); setGraphic(combo);
            combo.requestFocus();
        }
        @Override public void cancelEdit() { super.cancelEdit(); setText(getItem()); setGraphic(null); }
        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) { setText(null); setGraphic(null); }
            else if (isEditing()) { setText(null); setGraphic(combo); }
            else { setText(item); setGraphic(null); }
        }
    }

    private HBox buildAddRow() {
        TextField nameField = new TextField();
        nameField.setPromptText("activity name (e.g. maxRetries)");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        ComboBox<ActivityType> typeCombo = new ComboBox<>(FXCollections.observableArrayList(ActivityType.values()));
        typeCombo.setValue(ActivityType.TEXT);

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!isValidIdentifier(name)) { error("Enter a valid name (letters, digits, _; not starting with a digit)."); return; }
            if (rows.stream().anyMatch(r -> r.name().equals(name))) { error("'" + name + "' already exists."); return; }
            rows.add(ActivityVariable.create(name, typeCombo.getValue()));
            nameField.clear();
            statusLabel.setText("");
        });

        HBox row = new HBox(8, nameField, typeCombo, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildButtonBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());
        Button apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> apply(apply, cancel));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, cancel, apply);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void apply(Button apply, Button cancel) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText("");

        List<ActivityVariable> result = new ArrayList<>(rows);
        // Validate names: valid identifiers, unique.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ActivityVariable a : result) {
            if (!isValidIdentifier(a.name())) { error("Invalid activity name: '" + a.name() + "'."); return; }
            if (!seen.add(a.name())) { error("Duplicate activity name: '" + a.name() + "'."); return; }
        }

        setBusy(apply, cancel, true);
        activityService.update(new ActivitiesConfig(result))
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(apply, cancel, false);
                    if (err != null) error(rootMessage(err));
                    else stage.close();
                }));
    }

    private void setBusy(Button apply, Button cancel, boolean busy) {
        progress.setVisible(busy);
        apply.setDisable(busy);
        cancel.setDisable(busy);
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return true;
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
