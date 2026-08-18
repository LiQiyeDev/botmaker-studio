package com.botmaker.studio.ui.app;

import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.ui.render.components.BotTypePicker;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Describes a function before it is written: name, what it gives back, and what it takes.
 *
 * <p>What "+ Add Function" did before was write {@code public static void newMethod()} into the class and
 * leave the user to rename it in place — which meant the second one collided with the first, a {@code void}
 * function could not be made to return anything without hand-editing the signature, and a parameter could not
 * be added at all from the block editor. All three are things the button implied it was doing.
 *
 * <p>The dialog refuses rather than fixes: an illegal or taken name disables the confirm button and says why,
 * instead of silently uniquifying to {@code newMethod2}. The rules are {@link FunctionDraft}'s, and are pure —
 * this class only renders them. The live signature line under the fields is the same {@link
 * FunctionDraft#signature()} the rules see, so what is shown and what is checked cannot disagree.
 */
public final class AddFunctionDialog {

    private final Window owner;
    private final Set<String> takenNames;

    private final TextField nameField = new TextField();
    private final BotTypePicker returnPicker = new BotTypePicker(BotTypePicker.Purpose.RETURN_TYPE);
    private final VBox parameterRows = new VBox(6);
    private final List<ParameterRow> rows = new ArrayList<>();
    private final Label signatureLabel = new Label();
    private final Label problemLabel = new Label();
    private final Button confirmButton = new Button("Add Function");

    private FunctionDraft result;

    /**
     * @param takenNames every method name the target class already declares — read from the AST, so the
     *                   generated members an activity no longer draws still count
     */
    public AddFunctionDialog(Window owner, Set<String> takenNames) {
        this.owner = owner;
        this.takenNames = takenNames == null ? Set.of() : Set.copyOf(takenNames);
    }

    /** Shows the dialog and blocks; empty when the user cancelled. */
    public Optional<FunctionDraft> showAndWait() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Add Function");

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.getChildren().addAll(nameRow(), returnRow(), parametersSection(), preview(), buttonBar(stage));

        nameField.setPromptText("what the function does — clickLoginButton");
        nameField.textProperty().addListener((obs, old, now) -> revalidate());
        returnPicker.choiceProperty().addListener((obs, old, now) -> revalidate());
        revalidate();

        stage.setScene(ThemedWindows.scene(root, 520, 420));
        stage.setMinWidth(460);
        nameField.requestFocus();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    // -------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------

    private HBox nameRow() {
        HBox.setHgrow(nameField, Priority.ALWAYS);
        return labelled("Name", nameField);
    }

    private HBox returnRow() {
        HBox.setHgrow(returnPicker, Priority.ALWAYS);
        return labelled("Gives back", returnPicker);
    }

    private VBox parametersSection() {
        Button add = new Button("+ Add parameter");
        add.setOnAction(e -> addParameter());

        VBox box = new VBox(8, new Label("Takes"), parameterRows, add);
        VBox.setVgrow(parameterRows, Priority.ALWAYS);
        return box;
    }

    private void addParameter() {
        ParameterRow row = new ParameterRow();
        rows.add(row);
        redrawParameters();
        row.nameField.requestFocus();
    }

    /**
     * Rebuilds the rows in list order. Reordering is a list move plus this — the controls carry no index of
     * their own, so there is no second ordering that can disagree with the one the draft is built from.
     */
    private void redrawParameters() {
        parameterRows.getChildren().setAll(rows.stream().map(ParameterRow::node).toList());
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).up.setDisable(i == 0);
            rows.get(i).down.setDisable(i == rows.size() - 1);
        }
        revalidate();
    }

    private void move(ParameterRow row, int by) {
        int from = rows.indexOf(row);
        int to = from + by;
        if (from < 0 || to < 0 || to >= rows.size()) return;
        rows.remove(from);
        rows.add(to, row);
        redrawParameters();
    }

    private HBox labelled(String text, Region control) {
        Label label = new Label(text);
        label.setMinWidth(90);
        HBox row = new HBox(10, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox preview() {
        signatureLabel.getStyleClass().add("dialog-code-preview");
        signatureLabel.setWrapText(true);
        problemLabel.getStyleClass().add("dialog-error-text");
        problemLabel.setWrapText(true);
        return new VBox(6, signatureLabel, problemLabel);
    }

    private HBox buttonBar(Stage stage) {
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());

        confirmButton.getStyleClass().add("primary-button");
        confirmButton.setDefaultButton(true);
        confirmButton.setOnAction(e -> {
            FunctionDraft draft = draft();
            if (draft.problem(takenNames).isPresent()) return;
            result = draft;
            stage.close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, spacer, cancel, confirmButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    // -------------------------------------------------------------------------
    // The draft, and what is wrong with it
    // -------------------------------------------------------------------------

    private FunctionDraft draft() {
        List<FunctionDraft.Parameter> params = rows.stream()
                .map(r -> new FunctionDraft.Parameter(r.nameField.getText(), r.picker.choice()))
                .toList();
        return new FunctionDraft(nameField.getText(), returnPicker.choice(), params);
    }

    private void revalidate() {
        FunctionDraft draft = draft();
        signatureLabel.setText(draft.signature());

        Optional<String> problem = draft.problem(takenNames);
        // A dialog opened on an empty name is not "wrong" yet — it is unfinished. Saying so before the user
        // has typed anything is nagging, so the confirm button carries that state and the message stays quiet.
        boolean untouched = nameField.getText().isBlank();
        problemLabel.setText(untouched ? "" : problem.orElse(""));
        problemLabel.setVisible(!problemLabel.getText().isEmpty());
        confirmButton.setDisable(problem.isPresent());
    }

    /** One parameter's controls, plus its place in the order. */
    private final class ParameterRow {
        private final TextField nameField = new TextField();
        private final BotTypePicker picker = new BotTypePicker(BotTypePicker.Purpose.PARAMETER);
        private final Button up = new Button("▲");
        private final Button down = new Button("▼");
        private final Button remove = new Button("✕");

        private ParameterRow() {
            nameField.setPromptText("name");
            nameField.setText(picker.choice().suggestedName());
            nameField.textProperty().addListener((obs, old, now) -> revalidate());
            // Renaming the field by hand wins: only a name still equal to the old type's suggestion follows
            // the type, so choosing Point then Rect renames "point" to "area" but never overwrites "target".
            picker.choiceProperty().addListener((obs, old, now) -> {
                if (old != null && nameField.getText().equals(old.suggestedName())) {
                    nameField.setText(now.suggestedName());
                }
                revalidate();
            });

            for (Button b : List.of(up, down, remove)) b.getStyleClass().add("row-icon-button");
            up.setOnAction(e -> move(this, -1));
            down.setOnAction(e -> move(this, 1));
            remove.setOnAction(e -> {
                rows.remove(this);
                redrawParameters();
            });
        }

        private HBox node() {
            HBox.setHgrow(nameField, Priority.ALWAYS);
            HBox.setHgrow(picker, Priority.ALWAYS);
            nameField.setPrefWidth(140);
            picker.setPrefWidth(180);
            HBox row = new HBox(8, nameField, picker, up, down, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }
    }
}
