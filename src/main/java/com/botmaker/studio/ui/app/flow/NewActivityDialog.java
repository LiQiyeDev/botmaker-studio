package com.botmaker.studio.ui.app.flow;

import com.botmaker.studio.project.activity.FlowEdge;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The "new activity" prompt: name, description, go-home tick and the activity's outcomes, all before the card
 * exists. It replaces the top bar's bare name field, which could only ever produce an activity with no outcomes
 * — leaving the user to discover the side panel before their card had anything but a "then" port to wire from.
 *
 * <p>Opened from the "Add activity" button and from a double-click on empty canvas; the caller supplies the
 * point the card should land on, so a double-click drops it under the cursor.
 *
 * <p>The implicit {@link FlowEdge#NEXT_OUTCOME} is shown as a fixed first row that can't be edited or removed.
 * It is not part of the declared outcome list ({@link ActivityDraft#outcomes()} excludes it, and
 * {@link ActivityDraft#allOutcomes()} puts it back), so showing it as an ordinary row would either lose it on
 * save or duplicate it — but hiding it entirely makes the card grow a port the dialog never mentioned.
 */
public final class NewActivityDialog {

    private final Window owner;
    /** The activity names already on the canvas — a new one may not collide with them. */
    private final Collection<String> taken;
    private final boolean goHomeByDefault;

    private final ObservableList<String> outcomes = FXCollections.observableArrayList();
    private final TextField name = new TextField();
    private final TextField description = new TextField();
    private final CheckBox goHome = new CheckBox("Go home first");
    private final CheckBox popupCheck = new CheckBox("Check for popups");
    private final Label error = new Label();
    private final VBox outcomeRows = new VBox(6);

    private Stage stage;
    private ActivityDraft created;

    public NewActivityDialog(Window owner, Collection<String> taken, boolean goHomeByDefault) {
        this.owner = owner;
        this.taken = taken;
        this.goHomeByDefault = goHomeByDefault;
    }

    /**
     * Shows the dialog and blocks until it closes. Returns the new activity, placed at {@code (x, y)}, or empty
     * if the user cancelled.
     */
    public Optional<ActivityDraft> showAt(double x, double y) {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("New activity");

        name.setPromptText("e.g. Resources");
        description.setPromptText("what it does (optional)");
        goHome.setSelected(goHomeByDefault);
        goHome.setTooltip(new Tooltip(
                "Call GoHome.run() immediately before this activity, so it starts from a known screen."));
        popupCheck.setSelected(true);
        popupCheck.setTooltip(new Tooltip(
                "Let Popups.run() dismiss popups before each vision step of this activity. Turn it off for an "
                        + "activity that works through a popup itself — otherwise the guard closes it underneath."));

        GridPane head = new GridPane();
        head.setHgap(8);
        head.setVgap(6);
        head.addRow(0, new Label("Name"), name);
        head.addRow(1, new Label("Description"), description);
        head.add(goHome, 1, 2);
        head.add(popupCheck, 1, 3);
        GridPane.setHgrow(name, Priority.ALWAYS);
        GridPane.setHgrow(description, Priority.ALWAYS);

        error.setStyle("-fx-text-fill: #b00020;");
        error.setWrapText(true);

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        Button create = new Button("Create");
        create.setDefaultButton(true);
        create.setOnAction(e -> commit(x, y));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, error, spacer, cancel, create);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, heading("Activity"), head, new Separator(),
                heading("Outcomes"), buildOutcomes(), new Separator(), buttons);
        root.setPadding(new Insets(16));

        rebuildOutcomeRows();
        stage.setScene(new Scene(root, 460, 460));
        // After the stage is up: a requestFocus before the window exists has nothing to focus.
        Platform.runLater(name::requestFocus);
        stage.showAndWait();
        return Optional.ofNullable(created);
    }

    private Node buildOutcomes() {
        Label explain = new Label("What this activity can report. Return one from its run() method, then wire "
                + "each one on the canvas. Every activity also has a \"then\" outcome, and any outcome you "
                + "leave unwired ends the run. You can add more later from the side panel.");
        explain.setWrapText(true);
        explain.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        TextField newOutcome = new TextField();
        newOutcome.setPromptText("new outcome (e.g. bag full)");
        HBox.setHgrow(newOutcome, Priority.ALWAYS);
        Button add = new Button("Add");
        Runnable addOutcome = () -> {
            String candidate = FlowNames.normalizeOutcome(newOutcome.getText());
            String problem = FlowNames.outcomeProblem(outcomes, activityLabel(), candidate, null);
            if (problem != null) { error.setText(problem); return; }
            outcomes.add(candidate);
            newOutcome.clear();
            error.setText("");
            rebuildOutcomeRows();
        };
        add.setOnAction(e -> addOutcome.run());
        // Consumed, or the un-consumed ActionEvent reaches the default Create button and the dialog closes on
        // the Enter that was meant to add the outcome.
        newOutcome.setOnAction(e -> {
            addOutcome.run();
            e.consume();
        });
        HBox addRow = new HBox(6, newOutcome, add);
        addRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(6, explain, outcomeRows, addRow);
    }

    /** The fixed NEXT row plus one removable row per declared outcome. */
    private void rebuildOutcomeRows() {
        outcomeRows.getChildren().clear();

        Label next = new Label("then  (" + FlowEdge.NEXT_OUTCOME + ")");
        next.setStyle("-fx-text-fill: #666;");
        Label always = new Label("always present");
        always.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        HBox nextRow = new HBox(8, next, always);
        nextRow.setAlignment(Pos.CENTER_LEFT);
        outcomeRows.getChildren().add(nextRow);

        for (String outcome : List.copyOf(outcomes)) {
            Label label = new Label(outcome);
            HBox spacer = new HBox();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button("✕");
            remove.setOnAction(e -> {
                outcomes.remove(outcome);
                rebuildOutcomeRows();
            });
            HBox row = new HBox(6, label, spacer, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            outcomeRows.getChildren().add(row);
        }
    }

    private void commit(double x, double y) {
        String candidate = name.getText() == null ? "" : name.getText().trim();
        String problem = FlowNames.activityNameProblem(candidate, taken);
        if (problem != null) {
            error.setText(problem);
            name.requestFocus();
            return;
        }
        String text = description.getText() == null ? "" : description.getText().trim();
        // Enabled from the start: an activity you just asked for and then have to tick on is a papercut, and the
        // canvas already shows a disabled card greyed out if you change your mind.
        created = new ActivityDraft(candidate, text, true, List.of(), List.copyOf(outcomes),
                goHome.isSelected(), popupCheck.isSelected(), x, y);
        stage.close();
    }

    /** What to call this activity in an outcome error, before it necessarily has a name. */
    private String activityLabel() {
        String typed = name.getText() == null ? "" : name.getText().trim();
        return typed.isEmpty() ? "this activity" : typed;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }
}
