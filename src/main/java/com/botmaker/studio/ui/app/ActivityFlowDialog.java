package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowNode;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.ui.app.flow.ActivityDraft;
import com.botmaker.studio.ui.app.flow.ActivityValueWidgets;
import com.botmaker.studio.ui.app.flow.ActivityValueWidgets.ValueEditor;
import com.botmaker.studio.ui.app.flow.FlowCanvas;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Activity Flow editor — the single place activities are defined, configured, ordered and switched on.
 * It replaces the old pair of dialogs (schema in one, values in another) with a canvas: drop activities as
 * cards, wire them into the order they should run, tick the ones you want, and fill in their params in the
 * side panel.
 *
 * <p>Three panes: the {@link FlowCanvas} in the middle, a side panel showing the selected card's params (or
 * the project's globals when nothing is selected), and a top bar of presets — named on/off selections that
 * flip the enable ticks without touching the wiring. Saving delegates to {@link ActivityService#update},
 * which rewrites {@code activities.json}, regenerates {@code Activities.java} /
 * {@code ActivityRegistry.java} (in flow order) and creates a stub for each new activity.
 */
public class ActivityFlowDialog {

    private final Window owner;
    private final ActivityService activityService;

    private final FlowCanvas canvas = new FlowCanvas();
    private final List<ActivityPreset> presets = new ArrayList<>();
    private final List<ActivityVariable> globals = new ArrayList<>();

    private final Label statusLabel = new Label();
    private final Label orderLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final VBox sidePanel = new VBox(10);
    private final ComboBox<ActivityPreset> presetCombo = new ComboBox<>();

    /** Readers for whatever the side panel is currently showing; re-created each time the selection changes. */
    private final List<ValueEditor> panelEditors = new ArrayList<>();
    private ActivityDraft panelDraft;

    private Stage stage;

    public ActivityFlowDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Activity Flow");

        loadCurrent();

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(canvas);
        root.setRight(buildSidePanel());
        root.setBottom(buildBottomBar());

        canvas.setOnMessage(this::error);
        canvas.setOnChainChanged(this::refreshOrderLabel);
        canvas.selectedProperty().addListener((o, was, is) -> showInSidePanel(is));
        showInSidePanel(null);
        refreshOrderLabel();

        stage.setScene(new Scene(root, 1040, 680));
        stage.show();
        // Cards have real bounds only after the first layout pass; re-draw so the wires land on the ports.
        Platform.runLater(canvas::refresh);
    }

    /** Seeds the canvas from the saved config: a card per activity, at its stored spot or a fresh one. */
    private void loadCurrent() {
        ActivitiesConfig current = activityService.current();
        ActivityFlow flow = current.flow();
        for (ActivityDefinition a : current.activities()) {
            Optional<FlowNode> placed = flow.node(a.name());
            Point2D at = placed.map(n -> new Point2D(n.x(), n.y())).orElseGet(canvas::nextFreeSpot);
            canvas.add(ActivityDraft.of(a, at.getX(), at.getY()));
        }
        canvas.edges().setAll(flow.edges());
        canvas.select(null);
        globals.addAll(current.globals());
        presets.addAll(current.presets());
        canvas.refresh();
    }

    // --- top bar: presets + add activity ---

    private Node buildTopBar() {
        presetCombo.setPromptText("Preset…");
        presetCombo.setPrefWidth(180);
        presetCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ActivityPreset preset) { return preset == null ? "" : preset.name(); }
            @Override public ActivityPreset fromString(String s) { return null; } // display-only
        });
        refreshPresetCombo();

        Button applyPreset = new Button("Apply");
        applyPreset.setOnAction(e -> {
            ActivityPreset preset = presetCombo.getValue();
            if (preset == null) { error("Pick a preset first."); return; }
            for (ActivityDraft d : canvas.drafts()) d.enabledProperty().set(preset.enables(d.name()));
            error("");
        });

        Button savePreset = new Button("Save selection as preset…");
        savePreset.setOnAction(e -> saveCurrentSelectionAsPreset());

        TextField newName = new TextField();
        newName.setPromptText("new activity (e.g. Resources)");
        newName.setPrefWidth(200);
        Button addActivity = new Button("Add activity");
        addActivity.setOnAction(e -> addActivity(newName));
        newName.setOnAction(e -> addActivity(newName));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, new Label("Presets:"), presetCombo, applyPreset, savePreset,
                spacer, newName, addActivity);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private void addActivity(TextField nameField) {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (!isValidIdentifier(name)) {
            error("Enter a valid activity name (letters, digits, _; not starting with a digit).");
            return;
        }
        if (canvas.drafts().stream().anyMatch(d -> d.name().equals(name))) {
            error("Activity '" + name + "' already exists.");
            return;
        }
        Point2D at = canvas.nextFreeSpot();
        canvas.add(new ActivityDraft(name, "", false, List.of(), at.getX(), at.getY()));
        nameField.clear();
        error("");
    }

    private void saveCurrentSelectionAsPreset() {
        TextInputDialog prompt = new TextInputDialog();
        prompt.initOwner(stage);
        prompt.setTitle("Save preset");
        prompt.setHeaderText("Name this selection of activities");
        prompt.setContentText("Preset name:");
        Optional<String> chosen = prompt.showAndWait();
        if (chosen.isEmpty()) return;

        String name = chosen.get().trim();
        if (name.isEmpty()) { error("A preset needs a name."); return; }

        List<String> on = new ArrayList<>();
        for (ActivityDraft d : canvas.drafts()) {
            if (d.enabled()) on.add(d.name());
        }
        presets.removeIf(p -> p.name().equals(name)); // re-saving a name overwrites it
        presets.add(new ActivityPreset(name, on));
        refreshPresetCombo();
        presetCombo.getSelectionModel().select(presets.size() - 1);
        error("");
    }

    /** The built-in presets plus the user's saved ones — built-ins are derived from what's on the canvas. */
    private void refreshPresetCombo() {
        List<String> all = new ArrayList<>();
        for (ActivityDraft d : canvas.drafts()) all.add(d.name());
        List<ActivityPreset> items = new ArrayList<>();
        items.add(ActivityPreset.everything(all));
        items.add(ActivityPreset.nothing());
        items.addAll(presets);
        presetCombo.getItems().setAll(items);
    }

    // --- side panel: the selected activity's schema + values, or the project globals ---

    private Node buildSidePanel() {
        sidePanel.setPadding(new Insets(12));
        sidePanel.setPrefWidth(300);
        sidePanel.setMinWidth(300);
        ScrollPane scroll = new ScrollPane(sidePanel);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(320);
        return scroll;
    }

    /**
     * Rebuilds the side panel for {@code draft}. Params are read back into {@link #panelEditors}; the panel
     * is flushed into the draft before it is replaced, so switching cards never loses a typed value.
     */
    private void showInSidePanel(ActivityDraft draft) {
        flushSidePanel();
        panelEditors.clear();
        panelDraft = draft;
        sidePanel.getChildren().clear();

        if (draft == null) {
            sidePanel.getChildren().addAll(heading("Global variables"),
                    new Label("Config not tied to any one activity. Select a card to edit that activity."));
            sidePanel.getChildren().add(buildVariableEditor(globals, null));
            return;
        }

        TextField name = new TextField(draft.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (is) return;
            String candidate = name.getText() == null ? "" : name.getText().trim();
            renameDraft(draft, candidate, name);
        });
        TextField description = new TextField(draft.description());
        description.textProperty().addListener((o, was, is) -> draft.descriptionProperty().set(is));

        GridPane head = new GridPane();
        head.setHgap(8);
        head.setVgap(6);
        head.addRow(0, new Label("Name"), name);
        head.addRow(1, new Label("Description"), description);
        GridPane.setHgrow(name, Priority.ALWAYS);
        GridPane.setHgrow(description, Priority.ALWAYS);

        Button remove = new Button("Remove activity");
        remove.setOnAction(e -> canvas.remove(draft));

        sidePanel.getChildren().addAll(heading("Activity"), head, new Separator(),
                heading("Config params"), buildVariableEditor(draft.params(), draft), new Separator(), remove);
    }

    private void renameDraft(ActivityDraft draft, String candidate, TextField field) {
        if (candidate.equals(draft.name())) return;
        if (!isValidIdentifier(candidate)) {
            error("Invalid activity name — reverted.");
            field.setText(draft.name());
            return;
        }
        if (canvas.drafts().stream().anyMatch(d -> d != draft && d.name().equals(candidate))) {
            error("Activity '" + candidate + "' already exists — reverted.");
            field.setText(draft.name());
            return;
        }
        draft.nameProperty().set(candidate); // the card re-labels and its wires follow the new name
        refreshPresetCombo();
        error("");
    }

    /**
     * An editor over a variable list: one row per variable with its value widget, plus an add/remove row.
     * Used for both an activity's params ({@code owner} non-null) and the project globals ({@code owner} null).
     */
    private Node buildVariableEditor(List<ActivityVariable> variables, ActivityDraft owner) {
        VBox box = new VBox(8);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;
        for (ActivityVariable v : variables) {
            Label label = new Label(v.name());
            label.setTooltip(v.description() == null || v.description().isBlank()
                    ? null : new javafx.scene.control.Tooltip(v.description()));
            grid.add(label, 0, row);
            Node widget = ActivityValueWidgets.build(v, panelEditors);
            grid.add(widget, 1, row);
            GridPane.setHgrow(widget, Priority.ALWAYS);

            Button drop = new Button("✕");
            drop.setOnAction(e -> {
                flushSidePanel();
                variables.remove(v);
                showInSidePanel(owner);
            });
            grid.add(drop, 2, row);
            row++;
        }
        if (variables.isEmpty()) {
            Label none = new Label(owner == null ? "No globals yet." : "No params yet.");
            none.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            box.getChildren().add(none);
        }
        box.getChildren().addAll(grid, buildAddVariableRow(variables, owner));
        return box;
    }

    private Node buildAddVariableRow(List<ActivityVariable> variables, ActivityDraft owner) {
        TextField name = new TextField();
        name.setPromptText(owner == null ? "global name" : "param name");
        HBox.setHgrow(name, Priority.ALWAYS);
        ComboBox<ActivityType> type = new ComboBox<>();
        type.getItems().setAll(ActivityType.values());
        type.setValue(ActivityType.TEXT);
        Button add = new Button("Add");
        add.setOnAction(e -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            if (variables.stream().anyMatch(v -> v.name().equals(candidate))) {
                error("'" + candidate + "' already exists here.");
                return;
            }
            flushSidePanel();
            variables.add(ActivityVariable.create(candidate, type.getValue()));
            showInSidePanel(owner);
            error("");
        });
        HBox row = new HBox(6, name, type, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Writes the side panel's widget values back into the variables they came from. */
    private void flushSidePanel() {
        if (panelEditors.isEmpty()) return;
        List<ActivityVariable> target = panelDraft == null ? globals : panelDraft.params();
        for (ValueEditor editor : panelEditors) {
            int at = indexOfNamed(target, editor.variable().name());
            if (at >= 0) target.set(at, editor.readVariable());
        }
    }

    private static int indexOfNamed(List<ActivityVariable> variables, String name) {
        for (int i = 0; i < variables.size(); i++) {
            if (variables.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    // --- bottom bar: run-order preview + save ---

    private Node buildBottomBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        orderLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #444;");

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.setOnAction(e -> save(save, close));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, progress, statusLabel, spacer, close, save);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(6, orderLabel, buttons);
        bar.setPadding(new Insets(10));
        return bar;
    }

    /** Shows the run order the current wiring produces, and warns about cards the chain never reaches. */
    private void refreshOrderLabel() {
        List<String> chain = canvas.chain();
        List<String> orphans = canvas.orphans();
        String order = canvas.edges().isEmpty()
                ? "No wiring yet — activities run in the order they were added."
                : "Runs: " + String.join(" → ", chain);
        if (!orphans.isEmpty()) {
            order += "    ⚠ not in the flow (won't run): " + String.join(", ", orphans);
        }
        orderLabel.setText(order);
    }

    private void save(Button save, Button close) {
        error("");
        flushSidePanel();

        List<ActivityDefinition> activities = new ArrayList<>();
        List<FlowNode> nodes = new ArrayList<>();
        for (ActivityDraft d : canvas.drafts()) {
            activities.add(d.toDefinition());
            nodes.add(new FlowNode(d.name(), d.x(), d.y()));
        }
        ActivitiesConfig cfg = new ActivitiesConfig(activities, new ArrayList<>(globals),
                new ActivityFlow(nodes, new ArrayList<>(canvas.edges())), new ArrayList<>(presets));

        String problem = validate(cfg);
        if (problem != null) { error(problem); return; }

        setBusy(save, close, true);
        activityService.update(cfg).whenComplete((ok, err) -> Platform.runLater(() -> {
            setBusy(save, close, false);
            if (err != null) error(rootMessage(err));
            else stage.close();
        }));
    }

    /** Returns an error message if the config can't be generated (bad/duplicate names, collisions), else null. */
    static String validate(ActivitiesConfig cfg) {
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

    private void setBusy(Button save, Button close, boolean busy) {
        progress.setVisible(busy);
        save.setDisable(busy);
        close.setDisable(busy);
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }

    static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
