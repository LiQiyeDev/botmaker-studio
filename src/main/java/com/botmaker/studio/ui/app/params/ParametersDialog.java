package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityType;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.ui.app.ActivityFlowDialog;
import com.botmaker.studio.ui.app.flow.FlowNames;
import com.botmaker.studio.ui.app.params.ParamValueWidgets.ValueEditor;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a bot's parameters are defined — the project's globals and each activity's own params, with
 * the type, the value, and who the parameter is <em>for</em>.
 *
 * <h2>Why it is not in the flow editor</h2>
 *
 * <p>Params used to be edited in the Activity Flow dialog's side panel, which meant the graph editor was also
 * the settings editor: to change a number you opened the canvas, found the card, and edited a cramped column
 * beside it — and the globals were only reachable by deselecting everything, which is not a thing anyone
 * guesses. The flow editor is about where the bot goes next; this is about what it is configured with. They
 * are now separate dialogs, and the flow editor's panel only reports what exists.
 *
 * <h2>The visibility axis</h2>
 *
 * <p>Each param declares a {@link ParamVisibility}. {@link ParamVisibility#PUBLIC} params are the ones the
 * Runner window will offer to whoever runs the bot; everything else stays the editor's business. The default
 * is editor-only, so publishing a setting is always a decision someone made here.
 */
public final class ParametersDialog {

    /** The globals row's entry in the scope list — not an activity, so it gets a name no activity can have. */
    private static final String GLOBALS = "Global variables";

    private final Window owner;
    private final ActivityService activityService;

    private final ListView<String> scopes = new ListView<>();
    private final VBox paramColumn = new VBox(10);
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    /** The working copy: the globals, and each live activity's params by activity name. */
    private final List<ActivityVariable> globals = new ArrayList<>();
    private final Map<String, List<ActivityVariable>> paramsByActivity = new LinkedHashMap<>();

    /** Readers for the value widgets currently on screen; re-created whenever the column is rebuilt. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    private Stage stage;

    public ParametersDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    public void show() {
        loadCurrent();

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Parameters");

        BorderPane root = new BorderPane();
        root.setLeft(buildScopeList());
        root.setCenter(buildParamPane());
        root.setBottom(buildBottomBar());

        stage.setScene(ThemedWindows.scene(root, 860, 620));
        selectScope(GLOBALS);
        stage.show();
    }

    private void loadCurrent() {
        ActivitiesConfig cfg = activityService.current();
        globals.addAll(cfg.globals());
        for (ActivityDefinition a : cfg.liveActivities()) {
            paramsByActivity.put(a.name(), new ArrayList<>(a.params()));
        }
    }

    // --- left: which set of params is being edited ---

    private Node buildScopeList() {
        List<String> items = new ArrayList<>();
        items.add(GLOBALS);
        items.addAll(paramsByActivity.keySet());
        scopes.getItems().setAll(items);
        scopes.setPrefWidth(200);
        scopes.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    return;
                }
                setText(name + "  (" + variablesOf(name).size() + ")");
            }
        });
        scopes.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is != null) selectScope(is);
        });
        return scopes;
    }

    /** The list this scope name edits — the working copy itself, so edits to it are the edits. */
    private List<ActivityVariable> variablesOf(String scope) {
        return GLOBALS.equals(scope) ? globals : paramsByActivity.getOrDefault(scope, new ArrayList<>());
    }

    private String currentScope() {
        String selected = scopes.getSelectionModel().getSelectedItem();
        return selected == null ? GLOBALS : selected;
    }

    private void selectScope(String scope) {
        if (!scope.equals(scopes.getSelectionModel().getSelectedItem())) {
            scopes.getSelectionModel().select(scope);
            return; // the listener re-enters with the selection made
        }
        rebuildParams();
    }

    // --- right: the params of the selected scope ---

    private Node buildParamPane() {
        paramColumn.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(paramColumn);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Rebuilds the whole column. Values on screen are flushed first, so no rebuild loses a typed number. */
    private void rebuildParams() {
        flushValues();
        valueEditors.clear();
        paramColumn.getChildren().clear();

        String scope = currentScope();
        List<ActivityVariable> variables = variablesOf(scope);

        Label title = new Label(GLOBALS.equals(scope) ? GLOBALS : scope + " — parameters");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label explain = new Label(GLOBALS.equals(scope)
                ? "Settings not tied to any one activity. Read from your code as Activities.<name>."
                : "Settings for this activity only. Read from your code as Activities." + scope + "_<name>.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");
        paramColumn.getChildren().addAll(title, explain);

        if (variables.isEmpty()) {
            Label none = new Label("Nothing here yet. Add a parameter below.");
            none.getStyleClass().add("dialog-hint-text");
            paramColumn.getChildren().add(none);
        }
        for (ActivityVariable v : List.copyOf(variables)) {
            paramColumn.getChildren().add(buildParamCard(v, variables));
        }
        paramColumn.getChildren().addAll(new Separator(), buildAddRow(variables));
        scopes.refresh();   // the per-scope count in the left list
    }

    /** One parameter: what it is called, what it holds, who it is for, and what it is set to. */
    private Node buildParamCard(ActivityVariable v, List<ActivityVariable> owner) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.getStyleClass().add("param-card");

        TextField name = new TextField(v.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (!is) commitRename(v, owner, name);
        });
        name.setOnAction(e -> {
            commitRename(v, owner, name);
            e.consume();   // otherwise Enter reaches the default Save button and closes the dialog
        });

        ComboBox<ActivityType> type = new ComboBox<>();
        type.getItems().setAll(ActivityType.values());
        type.setValue(v.type());
        type.setButtonCell(typeCell());
        type.setCellFactory(list -> typeCell());
        type.setOnAction(e -> replace(owner, v, v.withType(type.getValue())));

        ComboBox<ParamVisibility> visibility = new ComboBox<>();
        visibility.getItems().setAll(ParamVisibility.values());
        visibility.setValue(v.visibility());
        visibility.setButtonCell(visibilityCell());
        visibility.setCellFactory(list -> visibilityCell());
        visibility.setTooltip(new Tooltip(
                "Who gets to set this. A public parameter appears in the Runner window for whoever runs the "
                        + "bot; an editor-only one is yours alone and never leaves this dialog."));
        visibility.setOnAction(e -> replace(owner, v, v.withVisibility(visibility.getValue())));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this parameter. Any code reading it stops compiling, so check "
                + "first — nothing here scans your source."));
        drop.setOnAction(e -> {
            flushValues();
            owner.remove(v);
            rebuildParams();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, name, type, visibility, spacer, drop);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        if (v.type().hasOptions()) {
            grid.add(new Label("Choices"), 0, row);
            grid.add(buildOptionsEditor(v, owner), 1, row);
            row++;
        }

        grid.add(new Label("Value"), 0, row);
        Node widget = ParamValueWidgets.build(v, valueEditors);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        TextField description = new TextField(v.description());
        description.setPromptText("what this is for (shown as a tooltip, and to the user when public)");
        description.textProperty().addListener((o, was, is) -> replaceQuietly(owner, v, v.withDescription(is)));
        grid.add(new Label("Note"), 0, row);
        grid.add(description, 1, row);
        GridPane.setHgrow(description, Priority.ALWAYS);

        VBox card = new VBox(grid);
        card.setPadding(new Insets(4, 0, 4, 0));
        return card;
    }

    /**
     * The declared choices for a {@code CHOICE}/{@code MULTI_CHOICE} param: one editable row each, plus an
     * add row. Editing the list re-prunes the value ({@link ActivityVariable#withOptions}), so a choice that
     * is deleted cannot survive as a stored setting nobody can see any more.
     */
    private Node buildOptionsEditor(ActivityVariable v, List<ActivityVariable> owner) {
        VBox box = new VBox(4);
        List<String> options = v.options();
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            int at = i;
            TextField field = new TextField(option);
            HBox.setHgrow(field, Priority.ALWAYS);
            Runnable commit = () -> {
                String typed = field.getText() == null ? "" : field.getText().trim();
                if (typed.isEmpty() || typed.equals(option)) {
                    field.setText(option);
                    return;
                }
                if (options.contains(typed)) {
                    error("'" + typed + "' is already a choice here.");
                    field.setText(option);
                    return;
                }
                List<String> updated = new ArrayList<>(options);
                updated.set(at, typed);
                replaceOptions(owner, v, updated);
            };
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
            Button remove = new Button("✕");
            remove.getStyleClass().add("row-icon-button");
            remove.setOnAction(e -> {
                List<String> updated = new ArrayList<>(options);
                updated.remove(at);
                replaceOptions(owner, v, updated);
            });
            HBox row = new HBox(6, field, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }

        TextField fresh = new TextField();
        fresh.setPromptText("new choice");
        HBox.setHgrow(fresh, Priority.ALWAYS);
        Button add = new Button("Add");
        Runnable addOption = () -> {
            String typed = fresh.getText() == null ? "" : fresh.getText().trim();
            if (typed.isEmpty()) return;
            if (options.contains(typed)) {
                error("'" + typed + "' is already a choice here.");
                return;
            }
            List<String> updated = new ArrayList<>(options);
            updated.add(typed);
            replaceOptions(owner, v, updated);
        };
        add.setOnAction(e -> addOption.run());
        fresh.setOnAction(e -> {
            addOption.run();
            e.consume();
        });
        HBox addRow = new HBox(6, fresh, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(addRow);
        return box;
    }

    private Node buildAddRow(List<ActivityVariable> owner) {
        TextField name = new TextField();
        name.setPromptText(owner == globals ? "global name" : "param name");
        HBox.setHgrow(name, Priority.ALWAYS);
        ComboBox<ActivityType> type = new ComboBox<>();
        type.getItems().setAll(ActivityType.values());
        type.setValue(ActivityType.TEXT);
        type.setButtonCell(typeCell());
        type.setCellFactory(list -> typeCell());
        Button add = new Button("Add parameter");
        add.getStyleClass().add("primary-button");

        Runnable addParam = () -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!FlowNames.isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            if (owner.stream().anyMatch(v -> v.name().equals(candidate))) {
                error("'" + candidate + "' already exists here.");
                return;
            }
            flushValues();
            owner.add(ActivityVariable.create(candidate, type.getValue()));
            error("");
            name.clear();
            rebuildParams();
        };
        add.setOnAction(e -> addParam.run());
        name.setOnAction(e -> {
            addParam.run();
            e.consume();
        });

        HBox row = new HBox(6, name, type, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // --- editing the working copy ---

    private void commitRename(ActivityVariable v, List<ActivityVariable> owner, TextField field) {
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(v.name())) return;
        if (!FlowNames.isValidIdentifier(candidate)) {
            error("Invalid parameter name — reverted.");
            field.setText(v.name());
            return;
        }
        if (owner.stream().anyMatch(other -> other != v && other.name().equals(candidate))) {
            error("'" + candidate + "' already exists here — reverted.");
            field.setText(v.name());
            return;
        }
        error("");
        replace(owner, v, v.withName(candidate));
    }

    private void replaceOptions(List<ActivityVariable> owner, ActivityVariable v, List<String> options) {
        error("");
        replace(owner, v, v.withOptions(options));
    }

    /** Swaps a variable for an edited copy and redraws — for the edits that change what the card shows. */
    private void replace(List<ActivityVariable> owner, ActivityVariable v, ActivityVariable updated) {
        flushValues();
        int at = owner.indexOf(v);
        if (at < 0) return;
        owner.set(at, updated);
        rebuildParams();
    }

    /**
     * Swaps a variable for an edited copy without redrawing — for the description field, which fires on every
     * keystroke and would otherwise rebuild the column out from under the cursor.
     */
    private void replaceQuietly(List<ActivityVariable> owner, ActivityVariable v, ActivityVariable updated) {
        int at = owner.indexOf(v);
        if (at >= 0) owner.set(at, updated);
    }

    /** Writes the on-screen value widgets back into the variables they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        List<ActivityVariable> target = variablesOf(currentScope());
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < target.size(); i++) {
                // Matched by name, not by identity: every other edit on this card has already replaced the
                // record the widget was built from.
                if (target.get(i).name().equals(editor.variable().name())) {
                    target.set(i, target.get(i).withValue(editor.read().get()));
                    break;
                }
            }
        }
    }

    // --- saving ---

    private Node buildBottomBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        statusLabel.getStyleClass().add("dialog-error-text");

        Button close = new Button("Cancel");
        close.setOnAction(e -> stage.close());
        Button save = new Button("Save");
        save.setDefaultButton(true);
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> save(save, close));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, close, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private void save(Button save, Button close) {
        error("");
        flushValues();

        ActivitiesConfig current = activityService.current();
        List<ActivityDefinition> activities = new ArrayList<>();
        for (ActivityDefinition a : current.activities()) {
            List<ActivityVariable> edited = paramsByActivity.get(a.name());
            // An archived activity has no scope here, so it keeps the params it was archived with — the whole
            // point of archiving is that it comes back as it was.
            activities.add(edited == null ? a : a.withParams(edited));
        }
        ActivitiesConfig updated = new ActivitiesConfig(activities, new ArrayList<>(globals),
                current.flow(), current.presets(), current.goHomeByDefault());

        String problem = ActivityFlowDialog.validate(updated);
        if (problem != null) {
            error(problem);
            return;
        }

        setBusy(save, close, true);
        activityService.update(updated).whenComplete((ok, err) -> Platform.runLater(() -> {
            setBusy(save, close, false);
            if (err != null) error(rootMessage(err));
            else stage.close();
        }));
    }

    private void setBusy(Button save, Button close, boolean busy) {
        progress.setVisible(busy);
        save.setDisable(busy);
        close.setDisable(busy);
    }

    private void error(String message) {
        statusLabel.setText(message);
    }

    private static ListCell<ActivityType> typeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ActivityType type, boolean empty) {
                super.updateItem(type, empty);
                setText(empty || type == null ? null : type.displayName());
            }
        };
    }

    private static ListCell<ParamVisibility> visibilityCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ParamVisibility visibility, boolean empty) {
                super.updateItem(visibility, empty);
                setText(empty || visibility == null ? null : visibility.displayName());
            }
        };
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
