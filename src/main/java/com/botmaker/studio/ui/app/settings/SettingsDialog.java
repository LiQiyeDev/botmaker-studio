package com.botmaker.studio.ui.app.settings;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.settings.Setting;
import com.botmaker.studio.project.settings.SettingType;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.SettingsRailModel;
import com.botmaker.studio.services.TagCatalog;
import com.botmaker.studio.ui.app.settings.SettingValueWidgets.ValueEditor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one place a project's settings are defined — every value the bot reads while it runs, in one list,
 * organised by tag.
 *
 * <h2>Why one list rather than one per activity</h2>
 *
 * <p>Settings used to belong to an <em>activity</em>: the knob two activities both needed existed twice, under
 * two names, and had to be kept in step by hand; the knob that belonged to the bot as a whole had to live in a
 * separate "globals" list reachable only by deselecting everything. A {@link Setting} belongs to the project,
 * and its {@link Setting#tag() tag} is what organises it for a reader — the same tag vocabulary the image
 * gallery uses ({@link TagCatalog}), so "the Mining settings" is a view of one list, not a second copy of it.
 * Renaming an activity therefore renames its group here and in the gallery at once.
 *
 * <p>This is the {@link com.botmaker.studio.project.settings.SettingsModel#JAVA} dialog. A legacy project
 * still opens {@code ui/app/params/ParametersDialog}, which edits the same knobs under the old ownership; the
 * two are siblings and neither branches inside the other.
 *
 * <h2>What the editor is not asked to get right</h2>
 *
 * <p>Nothing here validates a value. A number past its bound, a half-typed duration, a choice that was deleted
 * out from under it — all of them are read as typed and pulled into range by {@link Setting#withValues}. The
 * only thing that can refuse is a <em>name</em>, because a name is what the generated field is called and a
 * duplicate one is a file that does not compile.
 */
public final class SettingsDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final ActivityService activityService;

    private final ListView<SettingsRailModel.Row> rail = new ListView<>();
    private final VBox settingColumn = new VBox(10);
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    /** The working copy — the project's settings as this dialog has them so far. */
    private final List<Setting> settings = new ArrayList<>();

    /** Readers for the value widgets currently on screen; re-created whenever the column is rebuilt. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    private TagCatalog catalog = TagCatalog.empty();
    private String selectedTag = SettingsRailModel.ALL;
    private Stage stage;

    public SettingsDialog(Window owner, ProjectConfig config, ActivityService activityService) {
        this.owner = owner;
        this.config = config;
        this.activityService = activityService;
    }

    public void show() {
        settings.addAll(activityService.current().settings());
        catalog = ImageTemplateLibrary.tagCatalog(config);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Settings");

        BorderPane root = new BorderPane();
        root.setLeft(buildRail());
        root.setCenter(buildSettingPane());
        root.setBottom(buildBottomBar());

        stage.setScene(ThemedWindows.scene(root, 920, 660));
        rebuildRail();
        stage.show();
    }

    // --- left: the tag rail --------------------------------------------------------------------------------

    private Node buildRail() {
        rail.setPrefWidth(210);
        rail.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SettingsRailModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("rail-heading");
                if (empty || row == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                switch (row) {
                    case SettingsRailModel.Heading heading -> {
                        setText(heading.text());
                        getStyleClass().add("rail-heading");
                        setDisable(true);   // a heading is a label that happens to live in a list
                    }
                    case SettingsRailModel.TagRow tag -> {
                        setText(tag.tag() + "  (" + tag.count() + ")");
                        setDisable(false);
                    }
                }
            }
        });
        rail.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is instanceof SettingsRailModel.TagRow tag && !tag.tag().equals(selectedTag)) {
                flushValues();
                selectedTag = tag.tag();
                rebuildSettings();
            }
        });
        return rail;
    }

    /** Rebuilds the rail (the counts move on every edit) and keeps the selection where it was. */
    private void rebuildRail() {
        List<SettingsRailModel.Row> rows = SettingsRailModel.rows(settings, catalog);
        rail.getItems().setAll(rows);
        SettingsRailModel.Row wanted = rows.stream()
                .filter(r -> r instanceof SettingsRailModel.TagRow t && t.tag().equals(selectedTag))
                .findFirst()
                // The tag went away — an activity was deleted while this was open. Fall back to All rather
                // than to nothing, so the settings that were under it stay reachable.
                .orElse(rows.getFirst());
        if (wanted instanceof SettingsRailModel.TagRow tag) selectedTag = tag.tag();
        rail.getSelectionModel().select(wanted);
        rebuildSettings();
    }

    // --- right: the settings of the selected tag ------------------------------------------------------------

    private Node buildSettingPane() {
        settingColumn.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(settingColumn);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Rebuilds the whole column. Values on screen are flushed first, so no rebuild loses a typed number. */
    private void rebuildSettings() {
        flushValues();
        valueEditors.clear();
        settingColumn.getChildren().clear();

        List<Setting> shown = SettingsRailModel.in(settings, selectedTag, catalog);

        Label title = new Label(selectedTag);
        title.getStyleClass().add("dialog-heading");
        Label explain = new Label(SettingsRailModel.ALL.equals(selectedTag)
                ? "Every setting in this project. Read from your code as Settings.<NAME>."
                : "Settings filed under " + selectedTag + ". Read from your code as Settings.<NAME>.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");
        settingColumn.getChildren().addAll(title, explain);

        if (shown.isEmpty()) {
            Label none = new Label("Nothing here yet. Add a setting below.");
            none.getStyleClass().add("dialog-hint-text");
            settingColumn.getChildren().add(none);
        }
        for (Setting s : shown) settingColumn.getChildren().add(buildCard(s));
        settingColumn.getChildren().addAll(new Separator(), buildAddRow());
    }

    /** One setting: what it is called, what it holds, who it is for, where it is filed, and its value. */
    private Node buildCard(Setting setting) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.getStyleClass().add("param-card");

        TextField name = new TextField(setting.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (!is) commitRename(setting, name);
        });
        name.setOnAction(e -> {
            commitRename(setting, name);
            e.consume();   // otherwise Enter reaches the default Save button and closes the dialog
        });
        HBox.setHgrow(name, Priority.ALWAYS);

        ComboBox<SettingType> type = new ComboBox<>();
        type.getItems().setAll(SettingType.selectable());
        type.setValue(setting.type());
        type.setButtonCell(typeCell());
        type.setCellFactory(list -> typeCell());
        type.setOnAction(e -> replace(setting, setting.withType(type.getValue())));

        ComboBox<String> tag = new ComboBox<>();
        tag.getItems().add(Setting.GENERAL);
        tag.getItems().addAll(catalog.names());
        tag.setValue(catalog.isDeclared(setting.tag()) ? catalog.find(setting.tag()).name() : Setting.GENERAL);
        tag.setTooltip(new Tooltip("Which group this is listed under, here and in the Runner. The list is the "
                + "project's tags — one per activity, plus your own."));
        tag.setOnAction(e -> replace(setting,
                setting.withTag(Setting.GENERAL.equals(tag.getValue()) ? "" : tag.getValue())));

        ComboBox<ParamVisibility> visibility = new ComboBox<>();
        visibility.getItems().setAll(ParamVisibility.values());
        visibility.setValue(setting.visibility());
        visibility.setButtonCell(visibilityCell());
        visibility.setCellFactory(list -> visibilityCell());
        visibility.setTooltip(new Tooltip(
                "Who gets to set this. A public setting appears in the Runner window for whoever runs the "
                        + "bot; an editor-only one is yours alone and never leaves this dialog."));
        visibility.setOnAction(e -> replaceQuietly(setting, setting.withVisibility(visibility.getValue())));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this setting. Any code reading it stops compiling, so check "
                + "first — nothing here scans your source."));
        drop.setOnAction(e -> {
            flushValues();
            settings.remove(indexOf(setting.name()));
            rebuildRail();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, name, type, tag, visibility, spacer, drop);
        head.setAlignment(Pos.CENTER_LEFT);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        if (setting.type().hasOptions()) {
            grid.add(new Label("Choices"), 0, row);
            grid.add(buildOptionsEditor(setting), 1, row);
            row++;
        }
        if (bounded(setting.type())) {
            grid.add(new Label("Range"), 0, row);
            grid.add(buildBoundsEditor(setting), 1, row);
            row++;
        }

        grid.add(new Label("Value"), 0, row);
        Node widget = SettingValueWidgets.build(setting, config, valueEditors);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        TextField label = new TextField(setting.label());
        label.setPromptText("what to call this in the Runner (the field name is used when blank)");
        label.textProperty().addListener((o, was, is) -> replaceQuietly(setting, current(setting).withLabel(is)));
        grid.add(new Label("Label"), 0, row);
        grid.add(label, 1, row);
        GridPane.setHgrow(label, Priority.ALWAYS);

        VBox card = new VBox(grid);
        card.setPadding(new Insets(4, 0, 4, 0));
        return card;
    }

    /**
     * The declared choices for a {@code CHOICE}/{@code MULTI_CHOICE} setting: one editable row each, plus an
     * add row. Editing the list re-prunes the value ({@link Setting#withOptions}), so a choice that is deleted
     * cannot survive as a stored value nobody can see any more.
     */
    private Node buildOptionsEditor(Setting setting) {
        VBox box = new VBox(4);
        List<String> options = setting.options();
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
                error("");
                replace(setting, current(setting).withOptions(updated));
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
                replace(setting, current(setting).withOptions(updated));
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
            error("");
            replace(setting, current(setting).withOptions(updated));
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

    /**
     * Least / most / step, all optional. They are advice to the widget and a clamp on save, never a validation
     * that can fail — so a range tightened after the fact pulls the value in rather than refusing to save it.
     */
    private Node buildBoundsEditor(Setting setting) {
        TextField min = boundField(setting.bounds().min(), "least");
        TextField max = boundField(setting.bounds().max(), "most");
        TextField step = boundField(setting.bounds().step(), "step");
        Runnable commit = () -> replace(setting, current(setting)
                .withBounds(new Setting.Bounds(min.getText(), max.getText(), step.getText())));
        for (TextField field : List.of(min, max, step)) {
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
        }
        HBox row = new HBox(6, min, max, step);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField boundField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setPrefWidth(90);
        return field;
    }

    /** Only the numeric types have a range; a range on a date would be a feature nobody asked for. */
    private static boolean bounded(SettingType type) {
        return type == SettingType.INT || type == SettingType.DOUBLE || type == SettingType.DURATION;
    }

    private Node buildAddRow() {
        TextField name = new TextField();
        name.setPromptText("setting name");
        HBox.setHgrow(name, Priority.ALWAYS);
        ComboBox<SettingType> type = new ComboBox<>();
        type.getItems().setAll(SettingType.selectable());
        type.setValue(SettingType.TEXT);
        type.setButtonCell(typeCell());
        type.setCellFactory(list -> typeCell());
        Button add = new Button("Add setting");
        add.getStyleClass().add("primary-button");

        Runnable addSetting = () -> {
            String field = Setting.toFieldName(name.getText());
            if (field == null) {
                error("Enter a name made of letters, digits and spaces — it becomes a Java field name.");
                return;
            }
            if (indexOf(field) >= 0) {
                error("'" + field + "' already exists in this project.");
                return;
            }
            flushValues();
            // A setting added while a tag is selected is filed under it — the tag is where you are, not a
            // second choice to make. Under "All settings" there is no such place, so it starts unfiled.
            String tag = SettingsRailModel.ALL.equals(selectedTag) || Setting.GENERAL.equals(selectedTag)
                    ? "" : selectedTag;
            settings.add(Setting.create(field, type.getValue(), tag));
            error("");
            name.clear();
            rebuildRail();
        };
        add.setOnAction(e -> addSetting.run());
        name.setOnAction(e -> {
            addSetting.run();
            e.consume();
        });

        HBox row = new HBox(6, name, type, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // --- editing the working copy ---------------------------------------------------------------------------

    private void commitRename(Setting setting, TextField field) {
        String candidate = Setting.toFieldName(field.getText());
        if (candidate != null && candidate.equals(setting.name())) return;
        if (candidate == null) {
            error("Invalid setting name — reverted.");
            field.setText(setting.name());
            return;
        }
        int existing = indexOf(candidate);
        if (existing >= 0 && existing != indexOf(setting.name())) {
            error("'" + candidate + "' already exists — reverted.");
            field.setText(setting.name());
            return;
        }
        error("");
        replace(setting, current(setting).withName(candidate));
    }

    /** The working copy's version of {@code setting} — later edits have replaced the record a widget holds. */
    private Setting current(Setting setting) {
        int at = indexOf(setting.name());
        return at < 0 ? setting : settings.get(at);
    }

    private int indexOf(String name) {
        for (int i = 0; i < settings.size(); i++) {
            if (settings.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    /** Swaps a setting for an edited copy and redraws — for the edits that change what the card shows. */
    private void replace(Setting setting, Setting updated) {
        flushValues();
        int at = indexOf(setting.name());
        if (at < 0) return;
        settings.set(at, updated);
        rebuildRail();
    }

    /**
     * Swaps a setting for an edited copy without redrawing — for the label field, which fires on every
     * keystroke and would otherwise rebuild the column out from under the cursor, and for visibility, which
     * changes nothing the card shows.
     */
    private void replaceQuietly(Setting setting, Setting updated) {
        int at = indexOf(setting.name());
        if (at >= 0) settings.set(at, updated);
    }

    /** Writes the on-screen value widgets back into the settings they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            // Matched by name, not by identity: every other edit on this card has already replaced the record
            // the widget was built from.
            int at = indexOf(editor.name());
            if (at >= 0) settings.set(at, settings.get(at).withValues(editor.read().get()));
        }
    }

    // --- saving ---------------------------------------------------------------------------------------------

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

        String problem = validate();
        if (problem != null) {
            error(problem);
            return;
        }

        setBusy(save, close, true);
        activityService.update(activityService.current().withSettings(List.copyOf(settings)))
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(save, close, false);
                    if (err != null) error(rootMessage(err));
                    else stage.close();
                }));
    }

    /**
     * The one thing this dialog can refuse: a name. A setting's name is the generated field's name, so a
     * duplicate — including one that collides with an activity's enable flag — is a class that does not
     * compile, which is a failure the editor must catch rather than the build.
     */
    private String validate() {
        Set<String> taken = new HashSet<>();
        for (var activity : activityService.current().liveActivities()) taken.add(activity.name());
        for (Setting s : settings) {
            if (Setting.toFieldName(s.name()) == null) return "Invalid setting name: '" + s.name() + "'.";
            if (!taken.add(s.name())) {
                return "'" + s.name() + "' is used twice — a setting cannot share a name with another "
                        + "setting or with an activity.";
            }
        }
        return null;
    }

    private void setBusy(Button save, Button close, boolean busy) {
        progress.setVisible(busy);
        save.setDisable(busy);
        close.setDisable(busy);
    }

    private void error(String message) {
        statusLabel.setText(message);
    }

    private static ListCell<SettingType> typeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(SettingType type, boolean empty) {
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
