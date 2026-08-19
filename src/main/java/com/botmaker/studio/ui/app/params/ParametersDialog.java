package com.botmaker.studio.ui.app.params;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.Bounds;
import com.botmaker.studio.project.activity.ParamVisibility;
import com.botmaker.studio.project.activity.VariableWire;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.services.TagCatalog;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.VariableRailModel;
import com.botmaker.studio.ui.app.ActivityFlowDialog;
import com.botmaker.studio.ui.app.flow.FlowNames;
import com.botmaker.studio.ui.app.params.ParamValueWidgets.ValueEditor;
import com.botmaker.studio.ui.render.components.BotTypePicker;
import com.botmaker.studio.ui.render.components.TagPicklist;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
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
    private Stage stage;

    public ParametersDialog(Window owner, ProjectConfig config, ActivityService activityService) {
        this.owner = owner;
        this.config = config;
        this.activityService = activityService;
    }

    public void show() {
        ActivitiesConfig cfg = activityService.current();
        variables.addAll(cfg.variables());
        // The very catalog the template gallery reads, so a tag means the same thing in both places.
        catalog = ImageTemplateLibrary.tagCatalog(config);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Parameters");

        BorderPane root = new BorderPane();
        root.setLeft(buildRail());
        root.setCenter(buildParamPane());
        root.setBottom(buildBottomBar());

        stage.setScene(ThemedWindows.scene(root, 900, 640));
        rebuildRail();
        stage.show();
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

        newCategory.setMaxWidth(Double.MAX_VALUE);
        newCategory.setTooltip(new Tooltip("Declare a category. It is the same vocabulary the template "
                + "gallery uses, so it appears there too."));
        newCategory.setOnAction(e -> createCategory());

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
     * Declares a new custom category and selects it — the affordance this dialog had none of, which meant
     * filing a variable under a group that did not exist yet was a trip to the Resource Manager and back.
     * The prompt is {@link TagPicklist#promptNewTag} so "what may a tag be called" keeps one answer.
     */
    private void createCategory() {
        TagPicklist.promptNewTag(stage, config).ifPresent(tag -> {
            catalog = ImageTemplateLibrary.declareTag(config, tag);
            selectedTag = tag;
            rebuildRail();
        });
    }

    /**
     * Files a batch of variables under the selected category. The rail already takes one at a time by drag;
     * this is the same edit for the case the drag is tedious in — a category being populated for the first
     * time, where every variable in the project is somewhere else.
     */
    private void moveIntoSelected() {
        if (VariableRailModel.ALL.equals(selectedTag)) return;
        flushAndDiscard();
        String home = ActivityVariable.GENERAL.equals(selectedTag) ? "" : selectedTag;
        List<ActivityVariable> inside = VariableRailModel.in(variables, selectedTag, catalog);
        List<ActivityVariable> outside = variables.stream().filter(v -> !inside.contains(v)).toList();
        if (outside.isEmpty()) {
            error("Every variable is already filed under \u201c" + selectedTag + "\u201d.");
            return;
        }
        List<String> chosen = pickVariables(outside);
        if (chosen.isEmpty()) return;
        for (String name : chosen) {
            int at = indexOf(name);
            if (at >= 0) variables.set(at, variables.get(at).withTag(home));
        }
        error("");
        rebuildRail();
    }

    /** A tick box per variable, in one modal. Returns the names ticked, or an empty list if cancelled. */
    private List<String> pickVariables(List<ActivityVariable> offered) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(4);
        for (ActivityVariable v : offered) {
            CheckBox box = new CheckBox(v.name() + "   \u00b7   " + v.tagOrGeneral());
            box.setUserData(v.name());
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
        return boxes.stream().filter(CheckBox::isSelected).map(b -> (String) b.getUserData()).toList();
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
     * Files the variable called {@code name} under {@code tag} — what a drop onto the rail does, and the same
     * edit the card's own picker makes. Returns whether anything moved, which is what a drop reports back.
     */
    private boolean fileUnder(String name, String tag) {
        ActivityVariable found = variables.stream().filter(v -> v.name().equals(name)).findFirst().orElse(null);
        if (found == null) return false;
        edit(found.name(), current -> current.withTag(ActivityVariable.GENERAL.equals(tag) ? "" : tag));
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
        if (shown.isEmpty()) {
            Label none = new Label("Nothing filed here yet. Add one below.");
            none.getStyleClass().add("dialog-hint-text");
            paramColumn.getChildren().add(none);
        }
        for (ActivityVariable v : shown) paramColumn.getChildren().add(buildParamCard(v));
        paramColumn.getChildren().addAll(new Separator(), buildAddRow());
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
            e.consume();   // otherwise Enter reaches the default Save button and closes the dialog
        });

        BotTypePicker type = new BotTypePicker(BotTypePicker.Purpose.VARIABLE);
        type.setChoice(v.type());
        type.setPrefWidth(180);
        type.choiceProperty().addListener((o, was, is) -> {
            if (is != null && !is.equals(v.type())) edit(v.name(), current -> current.withType(is));
        });

        CheckBox shared = new CheckBox("Show to user");
        shared.setSelected(v.isPublic());
        shared.setTooltip(new Tooltip("Ticked, this appears in the Runner window under its tag's heading. "
                + "Unticked, it is yours alone and never leaves this dialog."));
        shared.setOnAction(e -> editQuietly(v.name(), current -> current.withVisibility(
                shared.isSelected() ? ParamVisibility.PUBLIC : ParamVisibility.EDITOR_ONLY)));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this variable. Any code reading it stops compiling, so check "
                + "first — nothing here scans your source."));
        drop.setOnAction(e -> {
            flushAndDiscard();
            int at = indexOf(v.name());
            if (at >= 0) variables.remove(at);
            rebuildRail();
        });

        // The one thing on the card that starts a drag. The name field cannot be it: a TextField's own drag
        // is how text is selected, and stealing that would cost more than the shortcut is worth.
        Label grip = new Label("⠿");
        grip.getStyleClass().add("dialog-hint-text");
        grip.setTooltip(new Tooltip("Drag onto a tag on the left to file it there."));
        grip.setOnDragDetected(e -> {
            Dragboard board = grip.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(v.name());
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

        if (VariableWire.hasOptions(v.type())) {
            Label heading = new Label("Choices");
            heading.setTooltip(new Tooltip(v.type().isList()
                    ? "The set this variable's values are picked from. The user ticks any number of them."
                    : "The set this variable's value is picked from. The user picks exactly one."));
            grid.add(heading, 0, row);
            grid.add(buildOptionsEditor(v), 1, row);
            row++;
        }

        if (VariableWire.isBounded(v.type().type())) {
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
                editQuietly(v.name(), current -> current.withDescription(is)));
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
            edit(v.name(), current -> current.withTag(ActivityVariable.GENERAL.equals(chosen) ? "" : chosen));
        });
        return picker;
    }

    /**
     * The declared choices for a {@code Choice} variable: one editable row each, plus an add row. Editing the
     * list re-prunes the value ({@link ActivityVariable#withOptions}), so a choice that is deleted cannot
     * survive as a stored value nobody can see any more.
     */
    private Node buildOptionsEditor(ActivityVariable v) {
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
                replaceOptions(v, updated);
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
                replaceOptions(v, updated);
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
            replaceOptions(v, updated);
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
            if (!declared.equals(v.bounds())) edit(v.name(), current -> current.withBounds(declared));
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

    /** A new variable lands in the tag being looked at, which is where somebody adding one means to put it. */
    private Node buildAddRow() {
        TextField name = new TextField();
        name.setPromptText("variable name");
        HBox.setHgrow(name, Priority.ALWAYS);
        BotTypePicker type = new BotTypePicker(BotTypePicker.Purpose.VARIABLE);
        type.setPrefWidth(180);
        Button add = new Button("Add variable");
        add.getStyleClass().add("primary-button");

        Runnable addVariable = () -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!FlowNames.isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            if (isTaken(candidate, null)) {
                error("'" + candidate + "' is already the name of a variable or an activity.");
                return;
            }
            flushAndDiscard();
            String tag = VariableRailModel.ALL.equals(selectedTag)
                    || ActivityVariable.GENERAL.equals(selectedTag) ? "" : selectedTag;
            variables.add(ActivityVariable.create(candidate, type.choice()).withTag(tag));
            error("");
            name.clear();
            rebuildRail();
        };
        add.setOnAction(e -> addVariable.run());
        name.setOnAction(e -> {
            addVariable.run();
            e.consume();
        });

        HBox row = new HBox(6, name, type, add);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // --- editing the working copy -------------------------------------------------------------------------

    /**
     * Whether {@code candidate} is spoken for, ignoring {@code except}.
     *
     * <p>Against the activities <em>and</em> the working copy of the variables: both become fields on the same
     * generated class, so an activity named {@code Mining} and a variable named {@code Mining} are one field
     * declared twice — a project that saves and then will not compile.
     */
    private boolean isTaken(String candidate, String except) {
        return activityService.current().withVariables(variables).nameClash(candidate, except);
    }

    private void commitRename(ActivityVariable v, TextField field) {
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(v.name())) return;
        if (!FlowNames.isValidIdentifier(candidate)) {
            error("Invalid variable name — reverted.");
            field.setText(v.name());
            return;
        }
        if (isTaken(candidate, v.name())) {
            error("'" + candidate + "' is already the name of a variable or an activity — reverted.");
            field.setText(v.name());
            return;
        }
        error("");
        edit(v.name(), current -> current.withName(candidate));
    }

    private void replaceOptions(ActivityVariable v, List<String> options) {
        error("");
        edit(v.name(), current -> current.withOptions(options));
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
    private void edit(String name, UnaryOperator<ActivityVariable> change) {
        flushAndDiscard();
        int at = indexOf(name);
        if (at < 0) return;
        variables.set(at, change.apply(variables.get(at)));
        rebuildRail();
    }

    /**
     * {@link #edit} without the redraw — for the fields that fire on every keystroke or a click, and would
     * otherwise rebuild the column out from under the cursor. It does not flush, so it must not be used for
     * anything the value widgets are also writing.
     */
    private void editQuietly(String name, UnaryOperator<ActivityVariable> change) {
        int at = indexOf(name);
        if (at >= 0) variables.set(at, change.apply(variables.get(at)));
    }

    /** Where the variable called {@code name} currently sits, or -1. Names are unique, which is what makes
     * them the stable handle a widget can hold across a rebuild. */
    private int indexOf(String name) {
        for (int i = 0; i < variables.size(); i++) {
            if (variables.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    /** Writes the on-screen widgets back, then forgets them — see {@link #edit}. */
    private void flushAndDiscard() {
        flushValues();
        valueEditors.clear();
    }

    /** Writes the on-screen value widgets back into the variables they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < variables.size(); i++) {
                // Matched by name, not by identity: every other edit on this card has already replaced the
                // record the widget was built from.
                if (variables.get(i).name().equals(editor.name())) {
                    variables.set(i, variables.get(i).withValue(editor.read().get()));
                    break;
                }
            }
        }
    }

    // --- saving -------------------------------------------------------------------------------------------

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

        // withVariables on what is current, never a rebuilt config: this dialog owns one field of the model
        // and must hand back every other one exactly as it found it.
        ActivitiesConfig updated = activityService.current().withVariables(new ArrayList<>(variables));

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

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
