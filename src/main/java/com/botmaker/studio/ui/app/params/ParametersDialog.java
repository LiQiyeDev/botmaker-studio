package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.sdk.authoring.TagCatalog;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.activity.ValueWire;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.sdk.authoring.TemplateLibrary;
import com.botmaker.studio.services.VariableRailModel;
import com.botmaker.studio.state.SnapshotHistory;
import com.botmaker.studio.ui.app.ActivityFlowDialog;
import com.botmaker.studio.ui.app.StudioWindow;
import com.botmaker.studio.ui.app.flow.FlowNames;
import com.botmaker.studio.ui.app.params.ParamValueWidgets.ValueEditor;
import com.botmaker.studio.ui.render.components.ValueTypePicker;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.css.PseudoClass;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one place a bot's variables are defined: what each is called, what it holds, who it is for, and what it
 * is set to.
 *
 * <h2>One list, organised by tag</h2>
 *
 * <p>Every variable belongs to the project. What the rail on the left offers is a <em>view</em> of that one
 * list — <i>All</i>, <i>General</i> for the untagged, then the activity tags and the custom ones — over the
 * same {@link TagCatalog} the template gallery uses, so renaming an activity renames its group in both places
 * and there is no second tag vocabulary to drift. Filing a variable under "Mining" does not scope it to
 * Mining: it is still {@code Activities.<name>} from anywhere, which is the whole reason a delay two
 * activities wait for is one variable rather than a copy each.
 *
 * <h2>Why it is not in the flow editor</h2>
 *
 * <p>Values used to be edited in the Activity Flow dialog's side panel, which meant the graph editor was also
 * the settings editor: to change a number you opened the canvas, found the card, and edited a cramped column
 * beside it. The flow editor is about where the bot goes next; this is about what it is configured with.
 *
 * <h2>The audience axis</h2>
 *
 * <p>Each variable is either offered to whoever runs the bot or kept to the editor
 * ({@link ParamVisibility}). It is a tick box, ticked by default: a variable exists to be configured, and the
 * dropdown it replaced meant every new one was invisible in the Runner until somebody remembered the dropdown
 * was there.
 *
 * <h2>Nothing to save</h2>
 *
 * <p>Edits are written as they are made and taken back with ↶ — see {@link #buildBottomBar} for why the Save
 * button had to go, and {@link #commitPending} for what counts as one step.
 */
public final class ParametersDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final ActivityService activityService;

    /** Lit on the rail row a dragged variable is over — styled in {@code blocks.css}, never inline. */
    private static final PseudoClass RAIL_DROP = PseudoClass.getPseudoClass("rail-drop");

    private final ListView<VariableRailModel.Row> rail = new ListView<>();
    private final Button newCategory = new Button("+ New category\u2026");
    private final Button moveHere = new Button("Move variables here\u2026");
    private final VBox paramColumn = new VBox(10);
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    /** The working copy — one flat list, exactly as it is stored. */
    private final List<ActivityVariable> variables = new ArrayList<>();

    /** Readers for the value widgets currently on screen; re-created whenever the column is rebuilt. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    private TagCatalog catalog = TagCatalog.empty();
    private String selectedTag = VariableRailModel.ALL;

    /**
     * Which plugin's section a newly added variable is filed under — the default plugin's (the SDK's) until
     * a second plugin is installed and the user picks its section in the add row.
     */
    private String selectedGroup = ParameterGroup.DEFAULT_ID;
    private Stage stage;

    /**
     * Undo/redo over the variable list, in the {@linkplain SnapshotHistory#SnapshotHistory(Consumer)
     * restore-only} form: this dialog knows both halves of every step, because it decides when one has
     * happened (see {@link #commitPending}).
     */
    private final SnapshotHistory<List<ActivityVariable>> history = new SnapshotHistory<>(this::restore);

    /** The list as of the last recorded step — the "before" the next one is measured against. */
    private List<ActivityVariable> committed = List.of();

    /**
     * Typing is not an event this dialog can hear. The value widgets are twenty shapes with twenty different
     * change signals and the note field fires per keystroke, so instead of listening, the state is compared
     * against {@link #committed} on a slow tick: identical, nothing happened; different, that is one step.
     * A burst of typing therefore becomes one ↶, which is what a person means by "take back what I typed".
     */
    private static final Duration TYPING_TICK = Duration.millis(700);
    private Timeline typingWatch;

    /**
     * Autosave, coalesced — the same arrangement the flow editor uses, for the same reason: a write
     * regenerates {@code Activities.java} and the registry, which is far too much work to do per keystroke.
     */
    private final PauseTransition autosaveDelay = new PauseTransition(Duration.millis(400));

    /** What autosave has to say for itself: "Saving…", "Saved", or "Not saved" when the write was refused. */
    private final Label savedLabel = new Label();

    private boolean dirty;
    private boolean saving;
    private boolean closeWhenSaved;
    private Button closeButton;

    public ParametersDialog(Window owner, ProjectConfig config, ActivityService activityService) {
        this.owner = owner;
        this.config = config;
        this.activityService = activityService;
    }

    public void show() {
        ActivitiesConfig cfg = activityService.current();
        variables.addAll(cfg.variables());
        // The very catalog the template gallery reads, so a tag means the same thing in both places.
        catalog = TemplateLibrary.tagCatalog(config.resourcesRoot());

        StudioWindow window = StudioWindow.modal("parameters", "Parameters", owner)
                .size(900, 640).minSize(700, 460);
        stage = window.stage();

        BorderPane root = new BorderPane();
        root.setLeft(buildRail());
        root.setCenter(buildParamPane());
        // The add bar is pinned above the buttons rather than sitting at the end of the scrolling column,
        // which is where it was and where a project with twenty parameters hid it: adding one meant scrolling
        // past every parameter you already had, and in the category you were looking at it was the first thing
        // you wanted. It reads the selected category when it fires, so one bar serves every category.
        root.setBottom(new VBox(buildAddRow(), buildBottomBar()));

        rebuildRail();

        // Wired after the list is seeded, so loading the project does not read as an edit by the user: the
        // variables as they were opened are the state undo bottoms out at.
        committed = List.copyOf(variables);
        history.setOnChanged(this::markDirty);
        autosaveDelay.setOnFinished(e -> flush());
        typingWatch = new Timeline(new KeyFrame(TYPING_TICK, e -> commitPending("the value you typed")));
        typingWatch.setCycleCount(Animation.INDEFINITE);
        typingWatch.play();

        // The window's own ✕ is the same door as the Close button, and it must not be the one that loses the
        // last edit: both flush anything outstanding first.
        stage.setOnCloseRequest(e -> {
            if (dirty || saving || closeWhenSaved) {
                e.consume();
                closeRequested();
            }
        });
        stage.setOnHidden(e -> {
            typingWatch.stop();
            autosaveDelay.stop();
        });

        window.show(root);
    }

    // --- left: the tag rail ------------------------------------------------------------------------------

    private Node buildRail() {
        rail.setPrefWidth(220);
        rail.setCellFactory(list -> new ListCell<>() {
            {
                // Dropping a variable onto a tag files it there — the same edit the row's picker makes, for
                // people who reach for the rail they are already looking at.
                setOnDragOver(e -> {
                    if (acceptsDrop(e)) e.acceptTransferModes(TransferMode.MOVE);
                    e.consume();
                });
                setOnDragEntered(e -> {
                    if (acceptsDrop(e)) pseudoClassStateChanged(RAIL_DROP, true);
                });
                setOnDragExited(e -> pseudoClassStateChanged(RAIL_DROP, false));
                setOnDragDropped(e -> {
                    pseudoClassStateChanged(RAIL_DROP, false);
                    if (!acceptsDrop(e)) return;
                    e.setDropCompleted(fileUnder(e.getDragboard().getString(),
                            ((VariableRailModel.TagRow) getItem()).tag()));
                    e.consume();
                });
            }

            /** A drop lands only on a real tag row, and only from this dialog's own drag. */
            private boolean acceptsDrop(DragEvent e) {
                return getItem() instanceof VariableRailModel.TagRow tag
                        && !VariableRailModel.ALL.equals(tag.tag())
                        && e.getGestureSource() != this
                        && e.getDragboard().hasString();
            }

            @Override protected void updateItem(VariableRailModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("rail-heading");
                if (empty || row == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                switch (row) {
                    case VariableRailModel.Heading heading -> {
                        setText(heading.text());
                        // A heading is a label that happens to live in a list, so it must not look or behave
                        // like a row you can land on — arrow-keying onto one would select nothing at all.
                        setDisable(true);
                        getStyleClass().add("rail-heading");
                    }
                    case VariableRailModel.TagRow tag -> {
                        // "General" beside "All variables" reads as a second everything-bucket; saying what
                        // it holds is cheaper than a heading alone at telling the two apart.
                        String label = ActivityVariable.GENERAL.equals(tag.tag())
                                ? tag.tag() + " (no category)" : tag.tag();
                        setText(label + "  (" + tag.count() + ")");
                        setDisable(false);
                    }
                }
            }
        });
        rail.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is instanceof VariableRailModel.TagRow tag) {
                flushValues();
                selectedTag = tag.tag();
                rebuildParams();
                refreshRailActions();
            }
        });

        // "＋ New category" stood here until 2026-09-01. Declaring one meant prompting for a tag name, and
        // what a tag may be called — the sanitizing, the clash check, the wording — is the picture library's
        // rule, which left with it. The rail still shows and files under every declared category; declaring a
        // new one is the Tag Manager's job, where that rule lives.
        newCategory.setVisible(false);
        newCategory.setManaged(false);

        moveHere.setMaxWidth(Double.MAX_VALUE);
        moveHere.setTooltip(new Tooltip("File several variables under this category at once, instead of "
                + "dragging them one at a time."));
        moveHere.setOnAction(e -> moveIntoSelected());

        VBox column = new VBox(6, rail, newCategory, moveHere);
        VBox.setVgrow(rail, Priority.ALWAYS);
        return column;
    }

    /** Both rail buttons say what they act on, so neither is offered where it would mean nothing. */
    private void refreshRailActions() {
        boolean real = !VariableRailModel.ALL.equals(selectedTag);
        moveHere.setText(real ? "Move variables to \u201c" + selectedTag + "\u201d\u2026" : "Move variables here\u2026");
        moveHere.setDisable(!real);
    }

    /**
     * Files a batch of variables under the selected category. The rail already takes one at a time by drag;
     * this is the same edit for the case the drag is tedious in — a category being populated for the first
     * time, where every variable in the project is somewhere else.
     */
    private void moveIntoSelected() {
        if (VariableRailModel.ALL.equals(selectedTag)) return;
        String home = ActivityVariable.GENERAL.equals(selectedTag) ? "" : selectedTag;
        List<ActivityVariable> inside = VariableRailModel.in(variables, selectedTag, catalog);
        List<ActivityVariable> outside = variables.stream().filter(v -> !inside.contains(v)).toList();
        if (outside.isEmpty()) {
            error("Every variable is already filed under \u201c" + selectedTag + "\u201d.");
            return;
        }
        List<ActivityVariable> chosen = pickVariables(outside);
        if (chosen.isEmpty()) return;
        // One step for the batch: filing eight variables at once should be one ↶, not eight.
        change("filing " + chosen.size() + " variables under " + selectedTag, () -> {
            for (ActivityVariable picked : chosen) {
                int at = indexOf(picked.group(), picked.name());
                if (at >= 0) variables.set(at, variables.get(at).withTag(home));
            }
            error("");
            rebuildRail();
        });
    }

    /**
     * A tick box per variable, in one modal. Returns the variables ticked, or an empty list if cancelled.
     *
     * <p>The records themselves rather than their names: a name is unique only inside its plugin's section,
     * so a list of names could no longer say <em>which</em> {@code Timeout} was ticked.
     */
    private List<ActivityVariable> pickVariables(List<ActivityVariable> offered) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(4);
        for (ActivityVariable v : offered) {
            CheckBox box = new CheckBox(v.name() + "   \u00b7   " + v.tagOrGeneral());
            box.setUserData(v);
            boxes.add(box);
            column.getChildren().add(box);
        }
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(stage);
        dialog.setTitle("Move variables");
        dialog.setHeaderText(null);
        ButtonType move = new ButtonType("Move", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(move, ButtonType.CANCEL);
        Label hint = new Label("Tick the variables to file under \u201c" + selectedTag + "\u201d.");
        VBox box = new VBox(8, hint, scroll);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != move) return List.of();
        return boxes.stream().filter(CheckBox::isSelected)
                .map(b -> (ActivityVariable) b.getUserData()).toList();
    }

    /** Redraws the rail (the counts move on every add, delete and re-tag) and keeps the selection. */
    private void rebuildRail() {
        List<VariableRailModel.Row> rows = VariableRailModel.rows(variables, catalog);
        rail.getItems().setAll(rows);
        VariableRailModel.Row keep = rows.stream()
                .filter(r -> r instanceof VariableRailModel.TagRow t && t.tag().equals(selectedTag))
                .findFirst()
                .orElse(rows.isEmpty() ? null : rows.getFirst());
        rail.getSelectionModel().select(keep);
        if (keep instanceof VariableRailModel.TagRow tag) selectedTag = tag.tag();
        rebuildParams();
        refreshRailActions();
    }

    /**
     * Files the dragged variable under {@code tag} — what a drop onto the rail does, and the same edit the
     * card's own picker makes. Returns whether anything moved, which is what a drop reports back.
     *
     * <p>{@code dragged} is the {@code group\nname} pair the grip put on the dragboard; a payload with no
     * newline is read as a bare name in the default section, which is what every earlier drag was.
     */
    private boolean fileUnder(String dragged, String tag) {
        if (dragged == null) return false;
        int cut = dragged.indexOf('\n');
        String group = cut < 0 ? ParameterGroup.DEFAULT_ID : dragged.substring(0, cut);
        String name = cut < 0 ? dragged : dragged.substring(cut + 1);
        int at = indexOf(group, name);
        if (at < 0) return false;
        edit(variables.get(at), "the category", current ->
                current.withTag(ActivityVariable.GENERAL.equals(tag) ? "" : tag));
        return true;
    }

    /** The tags a variable may be filed under: the declared ones, plus "no tag". */
    private List<String> filingChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(ActivityVariable.GENERAL);
        choices.addAll(catalog.names());
        return choices;
    }

    // --- right: the variables of the selected tag ---------------------------------------------------------

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

        Label title = new Label(ActivityVariable.GENERAL.equals(selectedTag)
                ? selectedTag + " (no category)" : selectedTag);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label explain = new Label("Every variable belongs to the whole bot and is read from your code as "
                + "Activities.<name>. A category only says where it is listed — never who may read it.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");
        paramColumn.getChildren().addAll(title, explain);

        List<ActivityVariable> shown = VariableRailModel.in(variables, selectedTag, catalog);
        for (ParameterGroup group : sections()) {
            List<ActivityVariable> mine = shown.stream().filter(v -> v.isIn(group.id())).toList();
            paramColumn.getChildren().add(sectionHeader(group, mine.isEmpty()));
            for (ActivityVariable v : mine) paramColumn.getChildren().add(buildParamCard(v));
        }
    }

    /**
     * The sections to draw, in order: one per plugin that declares a {@link ParameterGroup}, then one per
     * group this project's file names that no installed plugin claims.
     *
     * <p><b>Every declared group gets a heading even when it is empty</b>, because the heading is how a user
     * discovers that a plugin has parameters at all — an empty section reads as "nothing set up yet", an
     * absent one as "this plugin has no settings", and only the first is true.
     *
     * <p><b>An unclaimed group is still drawn</b>, under its raw id: those variables are in the file, they are
     * about to be written back, and hiding them would make a project silently lose settings while the plugin
     * that owns them is uninstalled. It is the same fail-soft rule an unregistered {@code ValueType} follows.
     */
    private List<ParameterGroup> sections() {
        List<ParameterGroup> groups = new ArrayList<>(PluginHost.parameterGroups(sdkPin()));
        if (groups.isEmpty()) groups.add(ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters"));
        for (ActivityVariable v : variables) {
            if (groups.stream().noneMatch(g -> g.id().equals(v.group()))) {
                groups.add(ParameterGroup.of(v.group(), v.group().isEmpty() ? "Parameters" : v.group()));
            }
        }
        return groups;
    }

    /** The heading of one plugin's section, and — when it holds nothing yet — the line that says so. */
    private Node sectionHeader(ParameterGroup group, boolean empty) {
        Label heading = new Label(group.title());
        heading.getStyleClass().add("param-section-heading");
        VBox box = new VBox(2, heading);
        box.setPadding(new Insets(6, 0, 0, 0));
        if (empty) {
            Label none = new Label("Nothing filed here yet — add one in the bar at the bottom.");
            none.getStyleClass().add("dialog-hint-text");
            box.getChildren().add(none);
        }
        return box;
    }

    /**
     * The SDK version the open project pins, or null when it cannot be read.
     *
     * <p>Read from the pom each time rather than cached: the *Upgrade SDK* dialog moves it while this window
     * can be open, and a stale pin would draw the previous version's sections.
     */
    private String sdkPin() {
        try {
            return MavenService.readSdkVersion(config.projectPath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One variable: what it is called, what it holds, who it is for, where it is filed, what it is set to. */
    private Node buildParamCard(ActivityVariable v) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.getStyleClass().add("param-card");

        TextField name = new TextField(v.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (!is) commitRename(v, name);
        });
        name.setOnAction(e -> {
            commitRename(v, name);
            e.consume();   // Enter commits the name here; it is not also a keystroke for anything else
        });

        ValueTypePicker type = new ValueTypePicker();
        type.setChoice(v.type());
        type.setPrefWidth(180);
        type.choiceProperty().addListener((o, was, is) -> {
            if (is == null) return;
            if (!is.equals(v.type())) edit(v, "the type", current -> current.withType(is));
        });

        CheckBox shared = new CheckBox("Show to user");
        shared.setSelected(v.isPublic());
        shared.setTooltip(new Tooltip("Ticked, this appears in the Runner window under its tag's heading. "
                + "Unticked, it is yours alone and never leaves this dialog."));
        shared.setOnAction(e -> editQuietly(v, current -> current.withVisibility(
                shared.isSelected() ? ParamVisibility.PUBLIC : ParamVisibility.EDITOR_ONLY)));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this variable. Any code reading it stops compiling, so check "
                + "first — nothing here scans your source."));
        drop.setOnAction(e -> change("removing " + v.name(), () -> {
            int at = indexOf(v.group(), v.name());
            if (at >= 0) variables.remove(at);
            rebuildRail();
        }));

        // The one thing on the card that starts a drag. The name field cannot be it: a TextField's own drag
        // is how text is selected, and stealing that would cost more than the shortcut is worth.
        Label grip = new Label("⠿");
        grip.getStyleClass().add("dialog-hint-text");
        grip.setTooltip(new Tooltip("Drag onto a tag on the left to file it there."));
        grip.setOnDragDetected(e -> {
            Dragboard board = grip.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            // The pair, not the name: a name only identifies a variable inside its own plugin's section, and
            // a drop has to move the one that was picked up. \n cannot occur in either half — a group id is
            // trimmed and a variable name is a Java identifier.
            content.putString(v.group() + "\n" + v.name());
            board.setContent(content);
            e.consume();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, grip, name, type, spacer, drop);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        grid.add(new Label("Category"), 0, row);
        grid.add(buildTagPicker(v), 1, row);
        row++;

        // A closed-set type brings its own choices (every direction, every mouse button), so there is nothing
        // here for the author to write down — offering an "add a choice" row over them would invite a second,
        // hand-typed copy of a list the SDK already owns.
        if (ValueWire.hasOptions(v.type()) && ValueWire.fixedOptions(v.type().type()).isEmpty()) {
            Label heading = new Label("Choices");
            heading.setTooltip(new Tooltip(v.type().isList()
                    ? "The set this variable's values are picked from. The user ticks any number of them."
                    : "The set this variable's value is picked from. The user picks exactly one."));
            grid.add(heading, 0, row);
            grid.add(buildOptionsEditor(v), 1, row);
            row++;
        }

        if (ValueWire.isBounded(v.type().type())) {
            grid.add(new Label("Range"), 0, row);
            grid.add(buildBoundsEditor(v), 1, row);
            row++;
        }

        grid.add(new Label("Value"), 0, row);
        Node widget = ParamValueWidgets.build(v, config, valueEditors);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        TextField description = new TextField(v.description());
        description.setPromptText("what this is for (shown as a tooltip, and to the user when shared)");
        description.textProperty().addListener((o, was, is) ->
                editQuietly(v, current -> current.withDescription(is)));
        grid.add(new Label("Note"), 0, row);
        grid.add(description, 1, row);
        GridPane.setHgrow(description, Priority.ALWAYS);
        row++;

        grid.add(shared, 1, row);

        VBox card = new VBox(grid);
        card.setPadding(new Insets(4, 0, 4, 0));
        return card;
    }

    /** Where this variable is listed — one tag or none, never several: a variable has one home. */
    private Node buildTagPicker(ActivityVariable v) {
        ComboBox<String> picker = new ComboBox<>();
        picker.getItems().setAll(filingChoices());
        picker.setValue(catalog.isDeclared(v.tag()) ? v.tag() : ActivityVariable.GENERAL);
        picker.setOnAction(e -> {
            String chosen = picker.getValue();
            edit(v, "the category", current ->
                    current.withTag(ActivityVariable.GENERAL.equals(chosen) ? "" : chosen));
        });
        return picker;
    }

    /**
     * The declared choices for a {@code Choice} variable: one editable row each, plus an add row. Editing the
     * list re-prunes the value ({@link ActivityVariable#withOptions}), so a choice that is deleted cannot
     * survive as a stored value nobody can see any more.
     */
    private Node buildOptionsEditor(ActivityVariable v) {
        ValueType base = v.type().type();
        ValueEditors.Context ctx = new ValueEditors.Context(config, v.bounds());
        VBox box = new VBox(4);
        List<String> options = v.options();

        for (int i = 0; i < options.size(); i++) {
            box.getChildren().add(optionRow(v, base, ctx, options, i));
        }

        // The add row is the base type's own editor, not a text field. A choice is a value of the variable's
        // type, so writing one down should be the same gesture as setting one: a template comes out of the
        // gallery with its picture, a colour off the screen, a duration as hours and minutes. Typed as text it
        // was a name recalled from memory — and a misremembered one is a choice that silently matches nothing.
        ValueEditors.Editor fresh = ValueEditors.editorFor(base, null, ctx);
        HBox.setHgrow(fresh.node(), Priority.ALWAYS);
        Button add = new Button("Add");
        Runnable addOption = () -> {
            String typed = fresh.read().get();
            typed = typed == null ? "" : typed.trim();
            if (typed.isEmpty()) return;
            if (options.contains(typed)) {
                error("'" + typed + "' is already a choice here.");
                return;
            }
            List<String> updated = new ArrayList<>(options);
            updated.add(typed);
            replaceOptions(v, updated);
        };
        add.setOnAction(e -> addOption.run());
        if (fresh.node() instanceof TextField field) {
            field.setPromptText("new choice");
            field.setOnAction(e -> {
                addOption.run();
                e.consume();
            });
        }
        HBox addRow = new HBox(6, fresh.node(), add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(addRow);
        return box;
    }

    /**
     * One declared choice.
     *
     * <p>Text stays editable in place — a typo in a label is fixed by fixing it. Every other type is shown the
     * way it is shown everywhere else (a thumbnail, a swatch, a spelled-out length) and changed by removing it
     * and adding the right one: an in-place editor for those would need a commit gesture per row, and a
     * three-item choice list is not where that ceremony earns its keep.
     */
    private Node optionRow(ActivityVariable v, ValueType base, ValueEditors.Context ctx,
                           List<String> options, int at) {
        String option = options.get(at);
        Node shown;
        if (ValueCatalog.TEXT_ID.equals(base.id())) {
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
                replaceOptions(v, updated);
            };
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
            shown = field;
        } else {
            Label label = new Label(option, ValueEditors.optionGraphic(base, option, ctx));
            HBox.setHgrow(label, Priority.ALWAYS);
            shown = label;
        }

        Button remove = new Button("✕");
        remove.getStyleClass().add("row-icon-button");
        remove.setOnAction(e -> {
            List<String> updated = new ArrayList<>(options);
            updated.remove(at);
            replaceOptions(v, updated);
        });
        HBox row = new HBox(6, shown, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * The declared range of a number: smallest and largest, both optional and <b>independent</b>. Leaving
     * both blank is what most numbers want and is the state a variable starts in; filling in only one is
     * "at most 10" or "at least 1", which is a sentence people say and which used to be unsayable here.
     *
     * <p>Declaring either clamps a stored value that falls outside it, so committing a bound rebuilds the
     * card: the widget the range describes is not the widget that was there before it.
     *
     * <p>There is no step field. For a whole number the step is 1 and saying so adds nothing; for a decimal
     * it was worse than nothing — a declared step of 0.1 puts 0.05 out of the arrows' reach, making the
     * editor a coarser instrument than the type it edits.
     */
    private Node buildBoundsEditor(ActivityVariable v) {
        TextField min = boundField(v.bounds().min(), "no minimum");
        TextField max = boundField(v.bounds().max(), "no maximum");
        Runnable commit = () -> {
            Bounds declared = new Bounds(min.getText(), max.getText());
            if (!declared.equals(v.bounds())) edit(v, "the range", current -> current.withBounds(declared));
        };
        for (TextField field : List.of(min, max)) {
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
        }
        HBox row = new HBox(6, min, new Label("to"), max);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField boundField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setPrefColumnCount(7);
        return field;
    }

    /**
     * A new variable lands in the tag being looked at, which is where somebody adding one means to put it —
     * and in the plugin section the picker names, which is a different question.
     *
     * <p>The tag is a view and the group is a scope: the first says where it is listed, the second says which
     * generated class it becomes a field of and whose namespace its name has to be unique in. The section
     * picker only appears once there is more than one plugin to choose between; with the SDK alone there is
     * nothing to ask, so the row is the row it always was.
     */
    private Node buildAddRow() {
        TextField name = new TextField();
        name.setPromptText("variable name");
        HBox.setHgrow(name, Priority.ALWAYS);
        ValueTypePicker type = new ValueTypePicker();
        type.setPrefWidth(180);
        Button add = new Button("Add variable");
        add.getStyleClass().add("primary-button");

        Runnable addVariable = () -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!FlowNames.isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            if (isTaken(candidate, null, selectedGroup)) {
                error("'" + candidate + "' is already the name of a variable or an activity.");
                return;
            }
            change("adding " + candidate, () -> {
                String tag = VariableRailModel.ALL.equals(selectedTag)
                        || ActivityVariable.GENERAL.equals(selectedTag) ? "" : selectedTag;
                variables.add(ActivityVariable.create(candidate, type.choice(), "", selectedGroup)
                        .withTag(tag));
                error("");
                name.clear();
                rebuildRail();
            });
        };
        add.setOnAction(e -> addVariable.run());
        name.setOnAction(e -> {
            addVariable.run();
            e.consume();
        });

        HBox row = new HBox(6, new Label("New"), name, type, add);
        List<ParameterGroup> groups = PluginHost.parameterGroups(sdkPin());
        if (groups.size() > 1) {
            ComboBox<ParameterGroup> section = new ComboBox<>();
            section.getItems().setAll(groups);
            section.setButtonCell(groupCell());
            section.setCellFactory(list -> groupCell());
            section.getSelectionModel().select(groups.stream()
                    .filter(g -> g.id().equals(selectedGroup)).findFirst().orElse(groups.getFirst()));
            selectedGroup = section.getSelectionModel().getSelectedItem().id();
            section.valueProperty().addListener((o, was, is) -> {
                if (is != null) selectedGroup = is.id();
            });
            row.getChildren().add(3, section);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 0, 10));
        return row;
    }

    // --- editing the working copy -------------------------------------------------------------------------

    /** A section's row: its title, which is the plugin's word for it and not its id. */
    private static ListCell<ParameterGroup> groupCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ParameterGroup item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        };
    }

    /**
     * Whether {@code candidate} is spoken for within {@code groupId}, ignoring {@code except}.
     *
     * <p>Against the activities <em>and</em> the working copy of the variables <em>in that group</em>. The
     * activities are checked in every group because the activity stubs are the host's, one set for the whole
     * project; the variables are not, because each plugin's become fields of its own generated class. Two
     * plugins may both call a variable {@code Timeout} and neither shadows the other — which is the point of
     * the sections, and what the flat namespace used to make impossible.
     */
    private boolean isTaken(String candidate, String except, String groupId) {
        return activityService.current().withVariables(variables).nameClash(candidate, except, groupId);
    }

    private void commitRename(ActivityVariable v, TextField field) {
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(v.name())) return;
        if (!FlowNames.isValidIdentifier(candidate)) {
            error("Invalid variable name — reverted.");
            field.setText(v.name());
            return;
        }
        if (isTaken(candidate, v.name(), v.group())) {
            error("'" + candidate + "' is already the name of a variable or an activity — reverted.");
            field.setText(v.name());
            return;
        }
        error("");
        edit(v, "the name", current -> current.withName(candidate));
    }

    private void replaceOptions(ActivityVariable v, List<String> options) {
        error("");
        edit(v, "the choices", current -> current.withOptions(options));
    }

    /**
     * Applies {@code change} to the variable called {@code name} and redraws — for the edits that change what
     * the card shows.
     *
     * <p><b>By name, and the change is a function, not a finished record.</b> Both halves matter and both were
     * wrong. Flushing the on-screen widgets first replaces every record in the list with a
     * {@link ActivityVariable#withValue value-updated} copy, so the record a control captured when its card was
     * built is no longer <em>in</em> the list: looking it up by equality found nothing and the edit was dropped
     * on the floor — which is what made changing a variable's type do nothing at all once its value had been
     * touched. And a pre-built {@code v.withX(…)} would have carried the stale value back in with it, undoing
     * the flush it was queued behind.
     *
     * <p>The editors are discarded after the flush so the rebuild that follows does not flush them a second
     * time onto the record they no longer describe — a retype must land on the new type's default, not on
     * whatever text the old widget still held.
     */
    private void edit(ActivityVariable v, String what, UnaryOperator<ActivityVariable> change) {
        String name = v.name();
        String group = v.group();
        change(what + " of " + name, () -> {
            int at = indexOf(group, name);
            if (at < 0) return;
            variables.set(at, change.apply(variables.get(at)));
            rebuildRail();
        });
    }

    /**
     * One recorded step: whatever was typed and not yet recorded becomes its own step first, then {@code body}
     * runs and becomes the next one.
     *
     * <p>Two steps rather than one because they are two things the user did, and folding a retype into the
     * number typed before it would make ↶ take back both. The editors are dropped between them for the reason
     * {@link #edit} used to give: a rebuild must not flush the old type's widget onto the new type's default.
     */
    private void change(String label, Runnable body) {
        commitPending("the value you typed");
        valueEditors.clear();
        body.run();
        commitPending(label);
    }

    /**
     * Records everything the list has picked up since the last step, under {@code label} — and does nothing at
     * all when it has picked up nothing, which is what makes it safe to call on a timer.
     *
     * <p>{@link ActivityVariable} is a record, so "has anything changed" is list equality and needs no dirty
     * flag per field. That is also what lets the note field and the visibility tick write straight into the
     * list without announcing themselves: the tick notices.
     */
    private void commitPending(String label) {
        flushValues();
        List<ActivityVariable> now = List.copyOf(variables);
        if (now.equals(committed)) return;
        history.record(label, committed, now);
        committed = now;
    }

    /**
     * Puts a snapshot back: ↶ and ↷ both land here, and so nothing else may. The editors are dropped first,
     * or the rebuild that follows would flush the widgets of the state being undone back over the one being
     * restored.
     */
    private void restore(List<ActivityVariable> snapshot) {
        valueEditors.clear();
        variables.clear();
        variables.addAll(snapshot);
        committed = List.copyOf(snapshot);
        rebuildRail();
    }

    /**
     * {@link #edit} without the redraw <em>and</em> without a step of its own — for the fields that fire on
     * every keystroke or a click, and would otherwise rebuild the column out from under the cursor. It does
     * not flush, so it must not be used for anything the value widgets are also writing.
     *
     * <p>Nothing is lost by not recording here: {@link #commitPending} runs on a timer and picks the change
     * up on its next tick, which is what turns a typed note into one step instead of one per letter.
     */
    private void editQuietly(ActivityVariable v, UnaryOperator<ActivityVariable> change) {
        int at = indexOf(v.group(), v.name());
        if (at >= 0) variables.set(at, change.apply(variables.get(at)));
    }

    /**
     * Where the variable called {@code name} in {@code group} currently sits, or -1.
     *
     * <p><b>The handle is the pair, not the name.</b> A name is unique inside its plugin's section and only
     * there (phase 11), so two plugins may both have a {@code Timeout} — and a widget holding just the name
     * would find whichever came first in the file and write the other one's value into it. The pair is still
     * a <em>value</em> rather than an identity, which is the property the handle needs: every edit on a card
     * replaces the record the widget was built from, so identity would find nothing.
     */
    private int indexOf(String group, String name) {
        for (int i = 0; i < variables.size(); i++) {
            ActivityVariable v = variables.get(i);
            if (v.name().equals(name) && v.isIn(group)) return i;
        }
        return -1;
    }

    /** Writes the on-screen value widgets back into the variables they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < variables.size(); i++) {
                // Matched by (group, name), not by identity: every other edit on this card has already
                // replaced the record the widget was built from, and a name alone no longer picks one
                // variable out of the project — only one out of its own plugin's section.
                if (editor.describes(variables.get(i))) {
                    variables.set(i, variables.get(i).withValue(editor.read().get()));
                    break;
                }
            }
        }
    }

    // --- saving -------------------------------------------------------------------------------------------

    /**
     * No Save button. Every edit is written as it is made, and the two arrows are what makes that bearable.
     *
     * <p>The Save button it replaces was a promise the dialog could not keep. Half the edits on a card —
     * renaming, retyping, changing the category, deleting — already rebuilt the column and the rail the
     * moment they happened, so "Cancel" took back the value you typed and none of the rest. With autosave
     * there is no "close without saving" to retreat to, so every mutation has to be reversible in the editor
     * itself; ↶ and ↷ are that, disabled when there is nothing to take back and captioned with what it would
     * be, the same arrangement as the flow editor.
     */
    private Node buildBottomBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        statusLabel.getStyleClass().add("dialog-error-text");
        savedLabel.getStyleClass().add("dialog-hint-text");

        closeButton = new Button("Close");
        closeButton.getStyleClass().add("primary-button");
        closeButton.setOnAction(e -> closeRequested());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, undoButton(), redoButton(),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                progress, savedLabel, statusLabel, spacer, closeButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private Button undoButton() {
        Button undo = new Button("↶");
        undo.disableProperty().bind(history.canUndoProperty().not());
        undo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new Tooltip(labelled("Undo", history.undoLabelProperty().get())),
                history.undoLabelProperty()));
        // What is on screen but not yet recorded is part of what ↶ takes back, so it becomes a step first —
        // otherwise the number you just typed would survive the undo of the edit before it.
        undo.setOnAction(e -> {
            commitPending("the value you typed");
            history.undo();
        });
        return undo;
    }

    private Button redoButton() {
        Button redo = new Button("↷");
        redo.disableProperty().bind(history.canRedoProperty().not());
        redo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new Tooltip(labelled("Redo", history.redoLabelProperty().get())),
                history.redoLabelProperty()));
        redo.setOnAction(e -> history.redo());
        return redo;
    }

    private static String labelled(String verb, String step) {
        return step == null || step.isBlank() ? verb + " — nothing to " + verb.toLowerCase() : verb + " " + step;
    }

    /** Notes that something changed and asks for a save — coalesced, so a burst of typing writes once. */
    private void markDirty() {
        dirty = true;
        savedLabel.setText("Saving…");
        autosaveDelay.playFromStart();
    }

    /**
     * Writes the variables, if there is anything to write and nothing already in flight.
     *
     * <p>Serialised rather than parallel, for the reason {@code ActivityFlowDialog.flush} gives: an update
     * rewrites {@code activities.json} and regenerates two source files, and two of those overlapping is a
     * project in an order nobody chose.
     */
    private void flush() {
        if (!dirty || saving) return;
        commitPending("the value you typed");

        // withVariables on what is current, never a rebuilt config: this dialog owns one field of the model
        // and must hand back every other one exactly as it found it.
        ActivitiesConfig updated = activityService.current().withVariables(new ArrayList<>(variables));

        String problem = ActivityFlowDialog.validate(updated);
        if (problem != null) {
            // Refused, not failed. Stay dirty so the fix saves it, and let go of any pending close: closing
            // now would leave the edit only in the window.
            error(problem);
            savedLabel.setText("Not saved");
            releaseClose();
            return;
        }
        error("");
        dirty = false;
        saving = true;
        progress.setVisible(true);
        savedLabel.setText("Saving…");
        activityService.update(updated).whenComplete((ok, err) -> Platform.runLater(() -> {
            saving = false;
            progress.setVisible(false);
            if (err != null) {
                dirty = true;   // the next edit, or Close, tries again
                error(rootMessage(err));
                savedLabel.setText("Not saved");
                releaseClose();
                return;
            }
            savedLabel.setText("Saved");
            if (dirty) flush();
            else if (closeWhenSaved) stage.close();
        }));
    }

    /** Close was pressed: write anything outstanding first, then close — never the other way round. */
    private void closeRequested() {
        autosaveDelay.stop();
        commitPending("the value you typed");
        if (!dirty && !saving) {
            stage.close();
            return;
        }
        closeWhenSaved = true;
        closeButton.setDisable(true);
        flush();
    }

    /** Gives the Close button back after a save that didn't land, so the window is never sealed shut. */
    private void releaseClose() {
        closeWhenSaved = false;
        closeButton.setDisable(false);
    }

    private void error(String message) {
        statusLabel.setText(message);
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
