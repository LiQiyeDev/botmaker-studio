package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.services.ActivityService;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Editor-side dialog: define the project's <em>activities</em> (two-tier). Each activity has a name +
 * description and its own list of config <em>params</em> ({@link ActivityVariable}s — the "how to do it");
 * free-standing global variables live in their own section. Applying delegates to {@link ActivityService},
 * which rewrites {@code activities.json}, regenerates {@code Activities.java}/{@code ActivityRegistry.java}
 * and creates a subclass stub for each new activity. Enable-flag values ("whether to do it") are set by the
 * operator in {@link SetActivityValuesDialog}.
 */
public class ManageActivitiesDialog {

    private final Window owner;
    private final ActivityService activityService;

    private final ObservableList<ActRow> activityRows = FXCollections.observableArrayList();
    private final ObservableList<ActivityVariable> globalRows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private Stage stage;

    public ManageActivitiesDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    /** Mutable UI holder for one activity while editing (converted to an immutable {@link ActivityDefinition} on apply). */
    private static final class ActRow {
        String name;
        String description;
        boolean enabledDefault;
        final ObservableList<ActivityVariable> params = FXCollections.observableArrayList();

        ActRow(String name, String description, boolean enabledDefault, List<ActivityVariable> params) {
            this.name = name;
            this.description = description;
            this.enabledDefault = enabledDefault;
            this.params.setAll(params);
        }
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Activities");

        ActivitiesConfig current = activityService.current();
        for (ActivityDefinition a : current.activities()) {
            activityRows.add(new ActRow(a.name(), a.description(), a.enabled(), a.params()));
        }
        globalRows.setAll(current.globals());

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(
                buildActivitiesSection(),
                new Separator(),
                buildGlobalsSection(),
                buildButtonBar());

        stage.setScene(new Scene(root, 620, 620));
        stage.show();
    }

    // --- Activities section: an activities table + a params table for the selected activity ---

    private VBox buildActivitiesSection() {
        TableView<ActRow> table = new TableView<>(activityRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPrefHeight(150);
        table.setPlaceholder(new Label("No activities yet. Add one below."));

        TableColumn<ActRow, String> nameCol = new TableColumn<>("Activity");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        nameCol.setCellFactory(col -> new ActNameCell());
        TableColumn<ActRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description));
        descCol.setCellFactory(col -> new ActDescCell());
        table.getColumns().addAll(List.of(nameCol, descCol));

        // Params table for the currently-selected activity.
        Label paramsHeading = new Label("Config params of selected activity");
        paramsHeading.setStyle("-fx-font-weight: bold;");
        TableView<ActivityVariable> paramsTable = buildVariableTable("Add a param below to tune how this activity runs.");
        paramsTable.setDisable(true);

        HBox paramAddRow = buildVariableAddRow(paramsTable, "param name (e.g. maxRuns)");
        paramAddRow.setDisable(true);
        Button removeParam = removeButton(paramsTable);
        removeParam.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean has = sel != null;
            paramsTable.setDisable(!has);
            paramAddRow.setDisable(!has);
            removeParam.setDisable(!has);
            paramsTable.setItems(has ? sel.params : FXCollections.observableArrayList());
        });

        Button addActivity = new Button("Add activity");
        TextField actName = new TextField();
        actName.setPromptText("activity name (e.g. Resources)");
        HBox.setHgrow(actName, Priority.ALWAYS);
        TextField actDesc = new TextField();
        actDesc.setPromptText("description (optional)");
        HBox.setHgrow(actDesc, Priority.ALWAYS);
        addActivity.setOnAction(e -> {
            String name = actName.getText() == null ? "" : actName.getText().trim();
            if (!isValidIdentifier(name)) { error("Enter a valid activity name (letters, digits, _; not starting with a digit)."); return; }
            if (activityRows.stream().anyMatch(r -> r.name.equals(name))) { error("Activity '" + name + "' already exists."); return; }
            ActRow row = new ActRow(name, actDesc.getText() == null ? "" : actDesc.getText().trim(), false, List.of());
            activityRows.add(row);
            table.getSelectionModel().select(row);
            actName.clear();
            actDesc.clear();
            statusLabel.setText("");
        });
        HBox addRow = new HBox(8, actName, actDesc, addActivity);
        addRow.setAlignment(Pos.CENTER_LEFT);

        // Reorder: the activities' order here is the run order (it drives ActivityRegistry.ALL).
        Button moveUp = new Button("Move up");
        Button moveDown = new Button("Move down");
        moveUp.setDisable(true);
        moveDown.setDisable(true);
        Runnable refreshMoveButtons = () -> {
            int i = table.getSelectionModel().getSelectedIndex();
            moveUp.setDisable(i <= 0);
            moveDown.setDisable(i < 0 || i >= activityRows.size() - 1);
        };
        table.getSelectionModel().selectedIndexProperty().addListener((o, was, is) -> refreshMoveButtons.run());
        activityRows.addListener((javafx.collections.ListChangeListener<ActRow>) c -> refreshMoveButtons.run());
        moveUp.setOnAction(e -> moveSelected(table, -1));
        moveDown.setOnAction(e -> moveSelected(table, +1));

        Button removeActivity = new Button("Remove activity");
        removeActivity.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> removeActivity.setDisable(is == null));
        removeActivity.setOnAction(e -> {
            ActRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) activityRows.remove(sel);
        });
        HBox actButtons = new HBox(8, moveUp, moveDown, removeActivity);
        actButtons.setAlignment(Pos.CENTER_RIGHT);

        HBox paramButtons = new HBox(removeParam);
        paramButtons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Activities");
        heading.setStyle("-fx-font-weight: bold;");
        Label hint = new Label("Double-click a cell to edit; drag order with Move up/down (top runs first). "
                + "Enable flags are set in Project → Set Activity Values.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        return new VBox(6, heading, table, addRow, actButtons, hint,
                paramsHeading, paramsTable, paramAddRow, paramButtons);
    }

    /** Swaps the selected activity with its neighbour {@code delta} rows away (−1 up, +1 down), keeping it selected. */
    private void moveSelected(TableView<ActRow> table, int delta) {
        int i = table.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= activityRows.size()) return;
        ActRow moved = activityRows.get(i);
        activityRows.set(i, activityRows.get(j));
        activityRows.set(j, moved);
        table.getSelectionModel().select(j);
    }

    private VBox buildGlobalsSection() {
        Label heading = new Label("Global variables (not tied to an activity)");
        heading.setStyle("-fx-font-weight: bold;");
        TableView<ActivityVariable> table = buildVariableTable("No globals. These are optional.");
        table.setItems(globalRows);
        table.setPrefHeight(120);
        HBox add = buildVariableAddRow(table, "global name (e.g. serverRegion)");
        Button remove = removeButton(table);
        HBox buttons = new HBox(remove);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        return new VBox(6, heading, table, add, buttons);
    }

    /** A reusable editable table (Name / Type / Description) over an {@link ActivityVariable} list. */
    private TableView<ActivityVariable> buildVariableTable(String placeholder) {
        TableView<ActivityVariable> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPlaceholder(new Label(placeholder));

        TableColumn<ActivityVariable, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        nameCol.setCellFactory(col -> new VarNameCell());
        TableColumn<ActivityVariable, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().type().displayName()));
        typeCol.setCellFactory(col -> new VarTypeCell());
        TableColumn<ActivityVariable, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description()));
        descCol.setCellFactory(col -> new VarDescCell());
        table.getColumns().addAll(List.of(nameCol, typeCol, descCol));
        return table;
    }

    private HBox buildVariableAddRow(TableView<ActivityVariable> table, String namePrompt) {
        TextField nameField = new TextField();
        nameField.setPromptText(namePrompt);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        ComboBox<ActivityType> typeCombo = new ComboBox<>(FXCollections.observableArrayList(ActivityType.values()));
        typeCombo.setValue(ActivityType.TEXT);
        TextField descField = new TextField();
        descField.setPromptText("description (optional)");
        HBox.setHgrow(descField, Priority.ALWAYS);
        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> {
            ObservableList<ActivityVariable> items = table.getItems();
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (!isValidIdentifier(name)) { error("Enter a valid name (letters, digits, _; not starting with a digit)."); return; }
            if (items.stream().anyMatch(r -> r.name().equals(name))) { error("'" + name + "' already exists here."); return; }
            String desc = descField.getText() == null ? "" : descField.getText().trim();
            items.add(ActivityVariable.create(name, typeCombo.getValue(), desc));
            nameField.clear();
            descField.clear();
            statusLabel.setText("");
        });
        HBox row = new HBox(8, nameField, typeCombo, descField, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button removeButton(TableView<ActivityVariable> table) {
        Button remove = new Button("Remove");
        remove.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> remove.setDisable(is == null));
        remove.setOnAction(e -> {
            ActivityVariable sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) table.getItems().remove(sel);
        });
        return remove;
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

        List<ActivityDefinition> activities = new ArrayList<>();
        for (ActRow r : activityRows) {
            activities.add(new ActivityDefinition(r.name, r.enabledDefault, r.description, new ArrayList<>(r.params)));
        }
        ActivitiesConfig cfg = new ActivitiesConfig(activities, new ArrayList<>(globalRows));

        String problem = validate(cfg);
        if (problem != null) { error(problem); return; }

        setBusy(apply, cancel, true);
        activityService.update(cfg)
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(apply, cancel, false);
                    if (err != null) error(rootMessage(err));
                    else stage.close();
                }));
    }

    /** Returns an error message if the config is invalid (bad/duplicate names / field collisions), else null. */
    private static String validate(ActivitiesConfig cfg) {
        Set<String> actNames = new HashSet<>();
        for (ActivityDefinition a : cfg.activities()) {
            if (!isValidIdentifier(a.name())) return "Invalid activity name: '" + a.name() + "'.";
            if (!actNames.add(a.name())) return "Duplicate activity name: '" + a.name() + "'.";
            Set<String> paramNames = new HashSet<>();
            for (ActivityVariable p : a.params()) {
                if (!isValidIdentifier(p.name())) return "Invalid param name in " + a.name() + ": '" + p.name() + "'.";
                if (!paramNames.add(p.name())) return "Duplicate param '" + p.name() + "' in " + a.name() + ".";
            }
        }
        // Every generated Activities.<field> name must be a unique valid identifier.
        Set<String> fields = new HashSet<>();
        for (ActivityVariable v : cfg.allVariables()) {
            if (!isValidIdentifier(v.name())) return "Invalid generated field name: '" + v.name() + "'.";
            if (!fields.add(v.name())) return "Name collision on generated field '" + v.name()
                    + "'. Rename an activity, param or global.";
        }
        return null;
    }

    // --- editable cells for the activities table (ActRow) ---

    private final class ActNameCell extends EditableCell<ActRow> {
        @Override void commitValue(ActRow row, String value) { if (!value.isEmpty()) row.name = value; }
        @Override String currentValue(ActRow row) { return row.name; }
    }

    private final class ActDescCell extends EditableCell<ActRow> {
        @Override void commitValue(ActRow row, String value) { row.description = value; }
        @Override String currentValue(ActRow row) { return row.description; }
    }

    /** Generic editable text cell that mutates a row field then refreshes the table. */
    private abstract class EditableCell<T> extends TableCell<T, String> {
        private final TextField field = new TextField();

        EditableCell() {
            field.setOnAction(e -> commit());
            field.focusedProperty().addListener((o, was, is) -> { if (!is && isEditing()) commit(); });
        }

        abstract void commitValue(T row, String value);
        abstract String currentValue(T row);

        private void commit() {
            int idx = getIndex();
            List<T> items = getTableView().getItems();
            String value = field.getText() == null ? "" : field.getText().trim();
            if (idx >= 0 && idx < items.size()) {
                commitValue(items.get(idx), value);
                getTableView().refresh();
            }
            cancelEdit();
        }

        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            field.setText(getItem() == null ? "" : getItem());
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

    // --- editable cells for ActivityVariable tables (params + globals), backed by the cell's own table items ---

    private static final class VarNameCell extends TableCell<ActivityVariable, String> {
        private final TextField field = new TextField();
        VarNameCell() {
            field.setOnAction(e -> commit());
            field.focusedProperty().addListener((o, was, is) -> { if (!is && isEditing()) commit(); });
        }
        private void commit() {
            int idx = getIndex();
            ObservableList<ActivityVariable> items = getTableView().getItems();
            String name = field.getText() == null ? "" : field.getText().trim();
            if (idx >= 0 && idx < items.size() && !name.isEmpty()) {
                ActivityVariable r = items.get(idx);
                if (!name.equals(r.name())) items.set(idx, new ActivityVariable(name, r.type(), r.value(), r.description()));
            }
            cancelEdit();
        }
        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            field.setText(getItem()); setText(null); setGraphic(field);
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

    private static final class VarDescCell extends TableCell<ActivityVariable, String> {
        private final TextField field = new TextField();
        VarDescCell() {
            field.setOnAction(e -> commit());
            field.focusedProperty().addListener((o, was, is) -> { if (!is && isEditing()) commit(); });
        }
        private void commit() {
            int idx = getIndex();
            ObservableList<ActivityVariable> items = getTableView().getItems();
            String desc = field.getText() == null ? "" : field.getText().trim();
            if (idx >= 0 && idx < items.size()) {
                ActivityVariable r = items.get(idx);
                if (!desc.equals(r.description())) items.set(idx, r.withDescription(desc));
            }
            cancelEdit();
        }
        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            field.setText(getItem() == null ? "" : getItem()); setText(null); setGraphic(field);
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

    private static final class VarTypeCell extends TableCell<ActivityVariable, String> {
        private final ComboBox<ActivityType> combo = new ComboBox<>(FXCollections.observableArrayList(ActivityType.values()));
        VarTypeCell() {
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> { if (isEditing() && combo.getValue() != null) commit(combo.getValue()); });
        }
        private void commit(ActivityType type) {
            int idx = getIndex();
            ObservableList<ActivityVariable> items = getTableView().getItems();
            if (idx >= 0 && idx < items.size()) {
                ActivityVariable r = items.get(idx);
                if (type != r.type()) items.set(idx, ActivityVariable.create(r.name(), type, r.description()));
            }
            cancelEdit();
        }
        private ActivityType currentType() {
            int idx = getIndex();
            ObservableList<ActivityVariable> items = getTableView().getItems();
            return (idx >= 0 && idx < items.size()) ? items.get(idx).type() : ActivityType.TEXT;
        }
        @Override public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            combo.setValue(currentType()); setText(null); setGraphic(combo); combo.requestFocus();
        }
        @Override public void cancelEdit() { super.cancelEdit(); setText(getItem()); setGraphic(null); }
        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) { setText(null); setGraphic(null); }
            else if (isEditing()) { setText(null); setGraphic(combo); }
            else { setText(item); setGraphic(null); }
        }
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
