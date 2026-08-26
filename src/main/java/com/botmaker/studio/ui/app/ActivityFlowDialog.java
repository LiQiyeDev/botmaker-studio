package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.activity.ActivitiesConfig;
import com.botmaker.studio.project.activity.ActivityDefinition;
import com.botmaker.studio.project.activity.ActivityFlow;
import com.botmaker.studio.project.activity.FlowEdge;
import com.botmaker.studio.project.activity.ActivityPreset;
import com.botmaker.studio.project.activity.ActivityVariable;
import com.botmaker.studio.project.activity.FlowNode;
import com.botmaker.studio.services.ActivityService;
import com.botmaker.studio.ui.app.flow.ActivityDraft;
import com.botmaker.studio.ui.app.flow.FlowCanvas;
import com.botmaker.studio.ui.app.flow.FlowNames;
import com.botmaker.studio.ui.app.flow.NewActivityDialog;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
 * cards, wire them into the order they should run, and tick the ones you want.
 *
 * <p>Three panes: the {@link FlowCanvas} in the middle, a side panel with the selected card's name,
 * outcomes and go-home/popup ticks, and a top bar of presets — named on/off selections that
 * flip the enable ticks without touching the wiring. Parameters themselves are <em>defined</em> in
 * {@code ParametersDialog} (Project ▸ Parameters…), not here: this dialog is about the graph. Saving delegates to {@link ActivityService#update},
 * which rewrites {@code activities.json}, regenerates {@code Activities.java} /
 * {@code ActivityRegistry.java} (in flow order) and creates a stub for each new activity.
 */
public class ActivityFlowDialog {

    private final Window owner;
    private final ActivityService activityService;

    private final FlowCanvas canvas = new FlowCanvas();
    private final List<ActivityPreset> presets = new ArrayList<>();
    /**
     * Retired activities, kept so they are written back on save. They are deliberately <em>not</em> dropped:
     * the editor never deletes {@code activities/<Name>.java}, and that surviving file still refers to the
     * activity's generated {@code Activities} fields — which only exist while the definition does.
     */

    private final Label statusLabel = new Label();

    /** The generated driver's step budget; edited in the no-selection panel alongside the globals. */
    private int maxSteps = ActivityFlow.DEFAULT_MAX_STEPS;

    /** The generated driver's pause between activities, in ms; edited beside {@link #maxSteps}. */
    private int stepDelayMs = ActivityFlow.DEFAULT_STEP_DELAY_MS;

    /** Whether a newly added activity starts with its "go home first" tick on. */
    private boolean goHomeByDefault = true;

    /**
     * Set by {@link #loadCurrent()} when the saved flow carries no card positions at all, so the canvas is
     * arranged for the user on open instead of stacking every card in one column. Deliberately <em>not</em> an
     * unconditional auto-arrange: positions are persisted, and a layout someone placed by hand must survive
     * being looked at.
     */
    private boolean arrangeOnOpen;
    private final Label orderLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final VBox sidePanel = new VBox(10);
    private final ComboBox<ActivityPreset> presetCombo = new ComboBox<>();

    /**
     * Autosave, coalesced. Every edit on the canvas asks to be written, and writing means regenerating
     * {@code Activities.java} and the registry — which is far too much work to do per pixel of a card drag.
     * So an edit restarts this timer instead, and the save happens once the user stops.
     */
    private final javafx.animation.PauseTransition autosaveDelay =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));

    /** What autosave has to say for itself: "Saving…", "Saved", or "Not saved" when the write was refused. */
    private final Label savedLabel = new Label();

    /** There are edits not yet written. */
    private boolean dirty;
    /** A write is in flight; the next one waits for it rather than racing it. */
    private boolean saving;
    /** Close was pressed with edits outstanding — close as soon as they are safely on disk. */
    private boolean closeWhenSaved;

    private Button closeButton;
    private Stage stage;

    public ActivityFlowDialog(Window owner, ActivityService activityService) {
        this.owner = owner;
        this.activityService = activityService;
    }

    public void show() {
        StudioWindow window = StudioWindow.modal("activity-flow", "Activity Flow", owner)
                .size(1040, 680).minSize(760, 480);
        stage = window.stage();

        loadCurrent();

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(canvas);
        root.setRight(buildSidePanel());
        root.setBottom(buildBottomBar());

        canvas.setOnMessage(this::error);
        canvas.setOnChainChanged(this::refreshOrderLabel);
        canvas.setOnCanvasDoubleClick(this::createActivityAt);
        // Wired *after* loadCurrent, so seeding the canvas doesn't read as a dozen edits by the user; and the
        // history is cleared for the same reason — the flow as it was loaded is the state undo bottoms out at.
        canvas.setOnFlowMutated(this::markDirty);
        canvas.history().clear();
        autosaveDelay.setOnFinished(e -> flush());
        canvas.selectedProperty().addListener((o, was, is) -> showInSidePanel(is));
        showInSidePanel(null);
        refreshOrderLabel();

        // The window's own ✕ is the same door as the Close button, and it must not be the one that loses the
        // last edit: both go through closeRequested, which flushes anything outstanding first.
        stage.setOnCloseRequest(e -> {
            if (dirty || saving || closeWhenSaved) {
                e.consume();
                closeRequested();
            }
        });
        window.show(root);
        // Cards have real bounds only after the first layout pass; re-draw so the wires land on the ports —
        // and auto-arrange there too, since it stacks cards by their real heights and would otherwise lay the
        // first-ever open out against the fallback height.
        Platform.runLater(() -> {
            if (arrangeOnOpen) canvas.autoArrange();
            else canvas.refresh();
        });
    }

    /** Seeds the canvas from the saved config: a card per activity, at its stored spot or a fresh one. */
    private void loadCurrent() {
        ActivitiesConfig current = activityService.current();
        ActivityFlow flow = current.flow();
        boolean anyPlaced = false;
        for (ActivityDefinition a : current.activities()) {
            Optional<FlowNode> placed = flow.node(a.name());
            anyPlaced |= placed.isPresent();
            Point2D at = placed.map(n -> new Point2D(n.x(), n.y())).orElseGet(canvas::nextFreeSpot);
            canvas.add(ActivityDraft.of(a, at.getX(), at.getY()));
        }
        // Only when nothing at all was placed: one saved position is enough to mean someone laid this out.
        arrangeOnOpen = !anyPlaced && !current.activities().isEmpty();
        canvas.edges().setAll(flow.edges());
        canvas.setStart(flow.start());
        maxSteps = flow.maxSteps();
        stepDelayMs = flow.stepDelayMs();
        goHomeByDefault = current.goHomeByDefault();
        canvas.select(null);
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
            // One step, not one per activity: a preset flips every switch at once, and taking that back should
            // be a single ↶ rather than a dozen.
            canvas.mutate("apply preset " + preset.name(), () -> {
                for (ActivityDraft d : canvas.drafts()) d.enabledProperty().set(preset.enables(d.name()));
            });
            error("");
        });

        Button savePreset = new Button("Save selection as preset…");
        savePreset.setOnAction(e -> saveCurrentSelectionAsPreset());

        Button addActivity = new Button("Add activity");
        addActivity.setTooltip(new javafx.scene.control.Tooltip(
                "Name it and declare its outcomes up front. Double-clicking empty canvas opens the same "
                        + "dialog, and drops the card where you clicked."));
        addActivity.setOnAction(e -> createActivityAt(canvas.nextFreeSpot()));

        Button recenter = new Button("⌖ Recenter");
        recenter.setTooltip(new javafx.scene.control.Tooltip("Reset the zoom and scroll back to the cards"));
        recenter.setOnAction(e -> canvas.recenter());

        Button arrange = new Button("⇄ Auto-arrange");
        arrange.setTooltip(new javafx.scene.control.Tooltip(
                "Lay the cards out in layers, by how many steps they are from the start, with anything "
                        + "unreachable below"));
        arrange.setOnAction(e -> canvas.autoArrange());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, new Label("Presets:"), presetCombo, applyPreset, savePreset,
                new Separator(javafx.geometry.Orientation.VERTICAL), undoButton(), redoButton(),
                new Separator(javafx.geometry.Orientation.VERTICAL), recenter, arrange,
                spacer, addActivity);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    /**
     * The two arrows that make autosave bearable. With no Save button there is no "close without saving" to
     * retreat to, so every mutation has to be reversible in the editor itself; these are that. They are
     * flow-local by design — the code editor has its own undo, and one stack spanning both would let ↶ in a
     * dialog take back a block edit behind it.
     *
     * <p>Disabled when there is nothing to take back, and captioned with what that would be, so ↶ is never a
     * guess. The arrows go grey after a rename for a reason {@code FlowCanvas.invalidateHistory} explains.
     */
    private Button undoButton() {
        Button undo = new Button("↶");
        undo.disableProperty().bind(canvas.history().canUndoProperty().not());
        undo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new javafx.scene.control.Tooltip(labelled("Undo", canvas.history().undoLabelProperty().get())),
                canvas.history().undoLabelProperty()));
        undo.setOnAction(e -> canvas.history().undo());
        return undo;
    }

    private Button redoButton() {
        Button redo = new Button("↷");
        redo.disableProperty().bind(canvas.history().canRedoProperty().not());
        redo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new javafx.scene.control.Tooltip(labelled("Redo", canvas.history().redoLabelProperty().get())),
                canvas.history().redoLabelProperty()));
        redo.setOnAction(e -> canvas.history().redo());
        return redo;
    }

    private static String labelled(String verb, String step) {
        return step == null || step.isBlank() ? verb + " — nothing to " + verb.toLowerCase() : verb + " " + step;
    }

    /**
     * Opens the new-activity prompt and drops the resulting card at {@code at}. Both entry points come through
     * here: the "Add activity" button (at the next free spot) and a double-click on empty canvas (under the
     * cursor). The dialog itself owns the name and outcome validation.
     */
    private void createActivityAt(Point2D at) {
        Optional<ActivityDraft> made =
                new NewActivityDialog(stage, placedNames(), goHomeByDefault).showAt(at.getX(), at.getY());
        if (made.isEmpty()) return;
        canvas.add(made.get());
        refreshPresetCombo();   // the built-in "Everything" preset is derived from what's on the canvas
        error("");
    }

    private Set<String> placedNames() {
        Set<String> names = new HashSet<>();
        for (ActivityDraft d : canvas.drafts()) names.add(d.name());
        return names;
    }

    private void saveCurrentSelectionAsPreset() {
        TextInputDialog prompt = new TextInputDialog();
        ThemedWindows.apply(prompt);
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
        markDirty();
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

    /** Rebuilds the side panel for {@code draft} — the graph's properties, not its settings (see the class doc). */
    private void showInSidePanel(ActivityDraft draft) {
        sidePanel.getChildren().clear();

        if (draft == null) {
            sidePanel.getChildren().addAll(heading("Variables"), buildParametersLink());
            sidePanel.getChildren().addAll(new Separator(), buildFlowLimitsSection());
            return;
        }

        TextField name = new TextField(draft.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (is) return;
            String candidate = name.getText() == null ? "" : name.getText().trim();
            renameDraft(draft, candidate, name);
        });
        TextField description = new TextField(draft.description());
        description.textProperty().addListener((o, was, is) -> {
            draft.descriptionProperty().set(is);
            markDirty();
        });

        CheckBox goHome = new CheckBox("Go home first");
        goHome.selectedProperty().bindBidirectional(draft.goHomeProperty());
        // The canvas records what it owns — position, wiring, the enable switch. These three live on the draft
        // and nowhere else, so they ask for the save themselves. They are not undoable, which is the honest
        // answer for a text field and a tick you can simply set back.
        goHome.selectedProperty().addListener((o, was, is) -> markDirty());
        goHome.setTooltip(new javafx.scene.control.Tooltip(
                "Call GoHome.run() immediately before this activity, so it starts from a known screen. Same "
                        + "tick as the ⌂ on the card."));

        CheckBox popupCheck = new CheckBox("Check for popups");
        popupCheck.selectedProperty().bindBidirectional(draft.popupCheckProperty());
        popupCheck.selectedProperty().addListener((o, was, is) -> markDirty());
        popupCheck.setTooltip(new javafx.scene.control.Tooltip(
                "Let Popups.run() dismiss popups before each vision step of this activity. Turn it off for an "
                        + "activity that works through a popup itself — otherwise the guard closes it "
                        + "underneath."));

        GridPane head = new GridPane();
        head.setHgap(8);
        head.setVgap(6);
        head.addRow(0, new Label("Name"), name);
        head.addRow(1, new Label("Description"), description);
        head.add(goHome, 1, 2);
        head.add(popupCheck, 1, 3);
        GridPane.setHgrow(name, Priority.ALWAYS);
        GridPane.setHgrow(description, Priority.ALWAYS);

        Button delete = new Button("Delete activity");
        delete.setTooltip(new javafx.scene.control.Tooltip(
                "Removes it from the flow and deletes its generated code and its " + draft.name()
                        + ".java on save. This cannot be undone."));
        delete.setOnAction(e -> deleteActivity(draft));

        sidePanel.getChildren().addAll(heading("Activity"), head, new Separator(),
                heading("Outcomes"), buildOutcomeEditor(draft), new Separator(),
                heading("Variables"), buildParametersLink(),
                new Separator(), delete);
    }

    /**
     * The outcome list for one activity: what it can report having happened, one card port each.
     *
     * <p>Deliberately says nothing about <em>where</em> an outcome goes — that is the canvas's job. Keeping
     * the two apart is the whole reason an activity's code never names another activity.
     */
    private Node buildOutcomeEditor(ActivityDraft draft) {
        VBox box = new VBox(6);

        Label explain = new Label("What this activity can report. Return one from its run() method and wire "
                + "each one on the canvas. Every activity also has a NEXT outcome, and any outcome "
                + "you leave unwired ends the run.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");
        box.getChildren().add(explain);

        for (String outcome : List.copyOf(draft.outcomes())) {
            TextField field = new TextField(outcome);
            field.focusedProperty().addListener((o, was, is) -> {
                if (is) return;
                renameOutcome(draft, outcome, field.getText(), field);
            });
            // Enter has to commit here too: it is the obvious way to finish a rename, and without a consuming
            // handler it would reach the default Save button and close the dialog with the old name still set.
            field.setOnAction(e -> {
                renameOutcome(draft, outcome, field.getText(), field);
                e.consume();
            });
            Button remove = new Button("✕");
            remove.setTooltip(new javafx.scene.control.Tooltip(
                    "Remove this outcome. Any wire leaving it is removed too."));
            remove.setOnAction(e -> {
                draft.outcomes().remove(outcome);
                showInSidePanel(draft);
            });
            HBox row = new HBox(6, field, remove);
            HBox.setHgrow(field, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
        }

        TextField newOutcome = new TextField();
        newOutcome.setPromptText("new outcome (e.g. bag full)");
        Button add = new Button("Add");
        Runnable addOutcome = () -> {
            String candidate = FlowNames.normalizeOutcome(newOutcome.getText());
            String problem = FlowNames.outcomeProblem(draft.outcomes(), draft.name(), candidate, null);
            if (problem != null) { error(problem); return; }
            draft.outcomes().add(candidate);
            newOutcome.clear();
            error("");
            showInSidePanel(draft);
        };
        add.setOnAction(e -> addOutcome.run());
        newOutcome.setOnAction(e -> {
            addOutcome.run();
            e.consume();
        });
        HBox addRow = new HBox(6, newOutcome, add);
        HBox.setHgrow(newOutcome, Priority.ALWAYS);
        addRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(addRow);
        return box;
    }

    /** Renames an outcome, carrying its wire across — as renaming an activity carries its wires. */
    private void renameOutcome(ActivityDraft draft, String oldName, String typed, TextField field) {
        String candidate = FlowNames.normalizeOutcome(typed);
        if (candidate.equals(oldName)) {
            field.setText(oldName);   // normalisation may have changed the text without changing the outcome
            return;
        }
        String problem = FlowNames.outcomeProblem(draft.outcomes(), draft.name(), candidate, oldName);
        if (problem != null) {
            error(problem);
            field.setText(oldName);
            return;
        }
        int at = draft.outcomes().indexOf(oldName);
        if (at < 0) return;
        draft.outcomes().set(at, candidate);
        field.setText(candidate);
        List<FlowEdge> rewired = new ArrayList<>(canvas.edges().size());
        for (FlowEdge e : canvas.edges()) {
            boolean mine = e.from().equals(draft.name()) && e.outcomeOrNext().equals(oldName);
            rewired.add(mine ? e.withOutcome(candidate) : e);
        }
        canvas.edges().setAll(rewired);
        error("");
        canvas.refresh();
    }

    /**
     * Removes {@code draft} from the flow for good — the card, its wires, its generated code and, on save, its
     * hand-written {@code <Name>.java}.
     *
     * <p>This replaced "Archive activity", which promised a reversible retirement and could not deliver one:
     * the definition, the enable-flag field, the registry entry, the driver case, the flow edges and the stub
     * file all had to leave and come back together, and any one of them out of step is a project that does not
     * compile. A user who wants to stop an activity running without losing it turns its <em>enable flag</em>
     * off — that is what the flag is for, it survives everything, and it needs no second mechanism.
     *
     * <p>So this one is honest about being destructive, and asks first, naming the file it will delete.
     */
    private void deleteActivity(ActivityDraft draft) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Its wires, its generated code and the file you wrote its steps in (" + draft.name()
                        + ".java) are all removed when you save. This cannot be undone.\n\n"
                        + "To stop it running without losing it, turn its switch off instead.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(stage);
        confirm.setTitle("Delete activity");
        confirm.setHeaderText("Delete \u201c" + draft.name() + "\u201d?");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        canvas.remove(draft); // also drops any wires into or out of it
        refreshPresetCombo();
        error("");
    }

    /**
     * The two loop-safety controls, both about a flow that legitimately cycles.
     *
     * <p>The <b>step budget</b> is how many activities one run may hand off to before the generated driver
     * gives up: nothing structural says when a loop should stop, so this is what separates "farms all night"
     * from a cycle with no way out. It bounds transitions <em>between</em> activities; the SDK's watchdog
     * covers being stuck inside one.
     *
     * <p>The <b>pause between activities</b> is the other half: the budget eventually stops a runaway, but a
     * fast activity looping back to itself gives the <em>user</em> no moment to intervene, because the bot is
     * holding the mouse the whole time. A default second between activities is that moment.
     */
    private Node buildFlowLimitsSection() {
        VBox box = new VBox(6);
        box.getChildren().add(heading("Loop safety"));

        Label explain = new Label("A flow can loop on purpose. This is how many activities one run may go "
                + "through before the bot gives up and stops — it's what catches a loop with no exit.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");

        TextField field = new TextField(String.valueOf(maxSteps));
        field.setPrefColumnCount(6);
        field.focusedProperty().addListener((o, was, is) -> {
            if (is) return;
            commitMaxSteps(field);
        });
        field.setOnAction(e -> {
            commitMaxSteps(field);
            e.consume();   // otherwise Enter reaches the default Save button and closes the dialog
        });

        HBox row = new HBox(8, new Label("Max steps per run"), field);
        row.setAlignment(Pos.CENTER_LEFT);

        Label delayExplain = new Label("How long the bot pauses between two activities. A fast activity that "
                + "loops back to itself otherwise never lets go of the mouse — this is your window to hit "
                + "Stop. Set 0 for no pause.");
        delayExplain.setWrapText(true);
        delayExplain.getStyleClass().add("dialog-hint-text");

        TextField delayField = new TextField(String.valueOf(stepDelayMs));
        delayField.setPrefColumnCount(6);
        delayField.focusedProperty().addListener((o, was, is) -> {
            if (is) return;
            commitStepDelay(delayField);
        });
        delayField.setOnAction(e -> {
            commitStepDelay(delayField);
            e.consume();   // otherwise Enter reaches the default Save button and closes the dialog
        });

        HBox delayRow = new HBox(8, new Label("Pause between activities (ms)"), delayField);
        delayRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox goHome = new CheckBox("New activities go home first");
        goHome.setSelected(goHomeByDefault);
        goHome.setTooltip(new javafx.scene.control.Tooltip(
                "Whether a newly added activity starts with its ⌂ tick on. Each activity can still be changed "
                        + "individually on its card."));
        goHome.selectedProperty().addListener((o, was, is) -> {
            goHomeByDefault = is;
            markDirty();
        });

        box.getChildren().addAll(explain, row, delayExplain, delayRow, goHome);
        return box;
    }

    private void commitStepDelay(TextField field) {
        try {
            int parsed = Integer.parseInt(field.getText().trim());
            // 0 is allowed here, unlike the step budget: "run flat out" is a real choice, it just forfeits the
            // gap that lets you stop a runaway loop.
            if (parsed < 0) throw new NumberFormatException();
            stepDelayMs = parsed;
            error("");
            markDirty();
        } catch (NumberFormatException bad) {
            error("The pause must be a whole number of milliseconds, 0 or more.");
            field.setText(String.valueOf(stepDelayMs));
        }
    }

    private void commitMaxSteps(TextField field) {
        try {
            int parsed = Integer.parseInt(field.getText().trim());
            if (parsed <= 0) throw new NumberFormatException();
            maxSteps = parsed;
            error("");
            markDirty();
        } catch (NumberFormatException bad) {
            // A zero or negative budget generates a driver that stops before running anything at all.
            error("The step limit must be a whole number above zero.");
            field.setText(String.valueOf(maxSteps));
        }
    }

    private void renameDraft(ActivityDraft draft, String candidate, TextField field) {
        if (candidate.equals(draft.name())) return;
        if (!FlowNames.isValidIdentifier(candidate)) {
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
     * The one pointer from the graph editor to the value editor — a button, not a list.
     *
     * <p>This panel used to print the variables filed under the selected activity. Two things were wrong with
     * that. It read as though the activity <em>owned</em> them, when a tag is a filing label and nothing else:
     * a variable tagged {@code Mining} is readable from every activity. And it was a column of values you
     * could not change, beside a canvas that is about wiring — the questions "what runs next" and "how many
     * retries" have no reason to share a panel.
     *
     * <p>So the whole list is gone and only the way out remains. <b>Project &rarr; Parameters…</b> is the one
     * editor, and it sees every variable at once — the only place a knob two activities share can sensibly be
     * changed.
     */
    private Node buildParametersLink() {
        Label what = new Label("Values belong to the project, not to a card: a variable filed under an "
                + "activity is still readable from all the others. Add, retype and share them in one place.");
        what.setWrapText(true);
        what.getStyleClass().add("dialog-hint-text");

        Button open = new Button("Open Parameters…");
        open.setOnAction(e ->
                new ParametersDialog(stage, activityService.projectConfig(), activityService).show());

        return new VBox(6, what, open);
    }

    // --- bottom bar: run-order preview + what autosave is doing ---

    private Node buildBottomBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        orderLabel.getStyleClass().add("dialog-hint-text");
        statusLabel.getStyleClass().add("dialog-error-text");
        savedLabel.getStyleClass().add("dialog-hint-text");

        // No Save button. Everything on this canvas is a gesture — drag a card, draw a wire, flip a switch —
        // and a gesture that needs confirming afterwards is a gesture you can lose by closing the window. So
        // the flow saves itself, ↶ is what takes a change back, and Close only ever closes.
        closeButton = new Button("Close");
        closeButton.setDefaultButton(true);
        closeButton.setOnAction(e -> closeRequested());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, progress, savedLabel, statusLabel, spacer, closeButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox bar = new VBox(6, orderLabel, buttons);
        bar.setPadding(new Insets(10));
        return bar;
    }

    /**
     * Summarises what the current wiring will do.
     *
     * <p>It used to print the run order, which a branching flow no longer has — where the bot goes depends on
     * what each activity reports at runtime, so the only honest static answers are where it starts and what it
     * can get to.
     */
    private void refreshOrderLabel() {
        if (canvas.edges().isEmpty()) {
            orderLabel.setText("No wiring yet — activities run in the order they were added.");
            return;
        }
        List<String> reachable = canvas.chain();
        List<String> orphans = canvas.orphans();
        List<String> unwired = canvas.unwiredOutcomes();
        StringBuilder summary = new StringBuilder("Starts at ")
                .append(canvas.resolvedStart().isEmpty() ? "—" : canvas.resolvedStart())
                .append("  ·  ").append(reachable.size()).append(" activities reachable");
        // Not a warning: an outcome with no wire is how a run ends, so this is a count, not a complaint.
        if (!unwired.isEmpty()) {
            summary.append("  ·  ").append(unwired.size()).append(" outcomes end the run");
        }
        if (!orphans.isEmpty()) {
            summary.append("    ⚠ not in the flow (won't run): ").append(String.join(", ", orphans));
        }
        // Also not a warning, but worth saying out loud: an unwired DISABLED port is a silent stop, and it is
        // silent precisely where the drawing shows the flow carrying on.
        List<String> stopsWhenOff = canvas.unwiredWhenDisabled();
        if (!stopsWhenOff.isEmpty()) {
            summary.append("    ·  stops the run if switched off: ").append(String.join(", ", stopsWhenOff));
        }
        orderLabel.setText(summary.toString());
    }

    /** Notes that something changed and asks for a save — coalesced, so a drag writes once, not per frame. */
    private void markDirty() {
        dirty = true;
        savedLabel.setText("Saving…");
        autosaveDelay.playFromStart();
    }

    /** The flow as it currently stands, ready to be written. */
    private ActivitiesConfig currentConfig() {
        List<ActivityDefinition> activities = new ArrayList<>();
        List<FlowNode> nodes = new ArrayList<>();
        for (ActivityDraft d : canvas.drafts()) {
            activities.add(d.toDefinition());
            nodes.add(new FlowNode(d.name(), d.x(), d.y()));
        }
        // Built from what is current, so the variables this dialog never shows survive the save untouched.
        return activityService.current()
                .withActivities(activities)
                .withFlow(new ActivityFlow(nodes, new ArrayList<>(canvas.edges()), canvas.start(),
                        maxSteps, stepDelayMs))
                .withPresets(new ArrayList<>(presets))
                .withGoHomeByDefault(goHomeByDefault);
    }

    /**
     * Writes the flow, if there is anything to write and nothing already in flight.
     *
     * <p>Serialised rather than parallel on purpose: {@link ActivityService#update} rewrites
     * {@code activities.json} and regenerates two source files, and two of those overlapping is a project in
     * an order nobody chose. A change that arrives mid-write simply leaves {@link #dirty} set, and the write
     * that lands starts the next one.
     */
    private void flush() {
        if (!dirty || saving) return;
        ActivitiesConfig cfg = currentConfig();
        String problem = validate(cfg);
        if (problem != null) {
            // Refused, not failed — a duplicate name or a bad identifier. Stay dirty so the fix saves it, and
            // let go of any pending close: closing now would leave the edit only in the window.
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
        activityService.update(cfg).whenComplete((ok, err) -> Platform.runLater(() -> {
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

    /**
     * Returns an error message if the config can't be generated (bad/duplicate names, collisions), else null.
     *
     * <p>Shared with {@code ParametersDialog}, which can produce the same collisions from the other side —
     * a param renamed there can collide with an activity name set here. One validator, so the two dialogs
     * cannot disagree about what is writable.
     */
    public static String validate(ActivitiesConfig cfg) {
        Set<String> actNames = new HashSet<>();
        Set<String> registryFields = new HashSet<>();
        for (ActivityDefinition a : cfg.activities()) {
            if (!FlowNames.isValidIdentifier(a.name())) return "Invalid activity name: '" + a.name() + "'.";
            if (!actNames.add(a.name())) return "Duplicate activity name: '" + a.name() + "'.";
            // The registry's singleton per activity is named by upper-casing, so two activities differing only
            // in case would generate one field twice. (Their stub files would also collide on a
            // case-insensitive filesystem, so this is a broken project either way — just say so here.)
            if (!registryFields.add(a.name().toUpperCase())) {
                return "'" + a.name() + "' clashes with another activity whose name differs only in case.";
            }
            // Outcomes become constants of the activity's generated Outcome enum. Checked against the declared
            // list, not allOutcomes(): that one de-duplicates defensively, so validating it would report a
            // clash as clean and leave the user with an outcome that silently has no port.
            Set<String> outcomeNames = new HashSet<>();
            outcomeNames.add(FlowEdge.NEXT_OUTCOME);
            outcomeNames.add(FlowEdge.DISABLED_OUTCOME);
            for (String outcome : a.outcomes()) {
                if (!FlowNames.isValidIdentifier(outcome)) {
                    return "Invalid outcome in " + a.name() + ": '" + outcome + "'.";
                }
                if (!outcomeNames.add(outcome)) {
                    if (FlowEdge.NEXT_OUTCOME.equals(outcome)) {
                        return a.name() + " already has a NEXT outcome — every activity does.";
                    }
                    if (FlowEdge.DISABLED_OUTCOME.equals(outcome)) {
                        return a.name() + " can't declare a DISABLED outcome — that port is always there, "
                                + "and an activity can't report it because it didn't run.";
                    }
                    return "Duplicate outcome '" + outcome + "' in " + a.name() + ".";
                }
            }
        }
        // Every generated field name must be a unique valid identifier. The fields are declared on two
        // classes since the 2026-08-25 split — the flags on Activities, the values on Parameters — but they
        // are validated as one namespace, for the reason ActivitiesConfig.nameClash records: which class a
        // name belongs to is answered by the name alone, so a name on both has no answer.
        Set<String> fields = new HashSet<>();
        for (ActivityVariable v : cfg.allVariables()) {
            if (!FlowNames.isValidIdentifier(v.name())) return "Invalid generated field name: '" + v.name() + "'.";
            if (!fields.add(v.name())) return "Name collision on generated field '" + v.name()
                    + "'. Rename an activity, param or global.";
        }
        return null;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
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
