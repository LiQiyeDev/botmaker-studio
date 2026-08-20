package com.botmaker.studio.ui.app;

import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.ui.render.components.BotTypePicker;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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
 * Describes a function before it is written — or a function that exists, before it is changed: name, what it
 * gives back, and what it takes.
 *
 * <p>What "+ Add Function" did before was write {@code public static void newMethod()} into the class and
 * leave the user to rename it in place — which meant the second one collided with the first, a {@code void}
 * function could not be made to return anything without hand-editing the signature, and a parameter could not
 * be added at all from the block editor. All three are things the button implied it was doing.
 *
 * <p>The dialog refuses rather than fixes: an illegal name, or one whose whole signature the class already
 * declares, disables the confirm button and says why, instead of silently uniquifying to {@code newMethod2}.
 * The rules are {@link FunctionDraft}'s, and are pure — this class only renders them. The live signature line
 * under the fields is the same {@link FunctionDraft#signature()} the rules see, so what is shown and what is
 * checked cannot disagree.
 *
 * <h2>Edit mode</h2>
 *
 * <p>The same dialog, opened on a method that exists, is how its signature is changed — the header used to
 * carry a live name field, a return-type chip and a chip pair per parameter, each of which rewrote the file
 * on its own, so the file passed through signatures nobody asked for on the way to the one they wanted.
 * Editing here means the whole signature is decided before anything is written, and written once.
 */
public final class AddFunctionDialog {

    private final Window owner;
    private final Set<String> takenSignatures;

    /** The signature being changed, or null when a new function is being described. */
    private final FunctionDraft editing;

    private final TextField nameField = new TextField();
    private final BotTypePicker returnPicker = new BotTypePicker(BotTypePicker.Purpose.RETURN_TYPE);
    private final VBox parameterRows = new VBox(6);
    private final List<ParameterRow> rows = new ArrayList<>();
    private final Label signatureLabel = new Label();
    private final Label problemLabel = new Label();
    private final Button confirmButton = new Button();

    /**
     * The return type when the editor cannot describe it — an activity's {@code Outcome}. Non-null here means
     * the picker is replaced by a chip and this exact text is written back: the dialog exists to edit the
     * <em>name and the inputs</em> of such a function, which is the part it can describe, rather than refusing
     * the whole signature over a type nobody asked it to change.
     */
    private SignatureType keptReturnType;

    private FunctionDraft result;

    /**
     * @param takenSignatures every signature the target class already declares — {@link
     *                        FunctionDraft#signatureKey() keys}, read from the AST, so the generated members
     *                        an activity no longer draws still count
     */
    public AddFunctionDialog(Window owner, Set<String> takenSignatures) {
        this(owner, takenSignatures, null);
    }

    /**
     * @param editing         the signature this dialog is changing, pre-filled into the fields; null to
     *                        describe a new function
     * @param takenSignatures as above — and it must <em>not</em> contain {@code editing}'s own key, or the
     *                        signature would be refused for colliding with itself
     */
    public AddFunctionDialog(Window owner, Set<String> takenSignatures, FunctionDraft editing) {
        this.owner = owner;
        this.takenSignatures = takenSignatures == null ? Set.of() : Set.copyOf(takenSignatures);
        this.editing = editing;
    }

    /** Shows the dialog and blocks; empty when the user cancelled. */
    public Optional<FunctionDraft> showAndWait() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(editing == null ? "Add Function" : "Edit Function");
        confirmButton.setText(editing == null ? "Add Function" : "Save Signature");

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.getChildren().addAll(nameRow(), returnRow(), parametersSection(), preview(), buttonBar(stage));

        nameField.setPromptText("what the function does — clickLoginButton");
        nameField.textProperty().addListener((obs, old, now) -> revalidate());
        returnPicker.choiceProperty().addListener((obs, old, now) -> revalidate());
        if (editing != null) prefill(editing);
        revalidate();

        stage.setScene(ThemedWindows.scene(root, 520, 420));
        stage.setMinWidth(460);
        nameField.requestFocus();
        stage.showAndWait();
        return Optional.ofNullable(result);
    }

    /**
     * Fills the fields from an existing signature. The parameter rows are built by the same {@link
     * #addParameter()} the "+" uses, then overwritten — so an edited row and a fresh one are the same widget,
     * with the same type-follows-name behaviour, rather than two shapes that can drift apart.
     */
    private void prefill(FunctionDraft draft) {
        nameField.setText(draft.name());
        draft.returnType().described().ifPresent(returnPicker::setChoice);
        for (FunctionDraft.Parameter parameter : draft.parameters()) {
            addParameter();
            ParameterRow row = rows.getLast();
            parameter.type().described().ifPresentOrElse(row.picker::setChoice, () -> row.keep(parameter.type()));
            row.nameField.setText(parameter.name());
            // The row *is* that parameter from here on, wherever the ▲▼ take it — see FunctionDraft.Parameter.
            row.origin = parameter.origin();
        }
    }

    /** A type shown but not offered: what the file says, greyed, with no control to change it. */
    private static Label keptChip(SignatureType type) {
        Label chip = new Label(type.sourceName());
        chip.getStyleClass().add("kept-type-chip");
        chip.setTooltip(new Tooltip(type.sourceName()
                + " isn't one of the types the editor offers, so it is kept exactly as the Java file writes it."
                + " The name and the inputs are still yours to change here."));
        return chip;
    }

    // -------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------

    private HBox nameRow() {
        HBox.setHgrow(nameField, Priority.ALWAYS);
        return labelled("Name", nameField);
    }

    private HBox returnRow() {
        if (editing != null && editing.returnType().isKept()) {
            keptReturnType = editing.returnType();
            return labelled("Gives back", new HBox(keptChip(keptReturnType)));
        }
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
            if (draft.problem(takenSignatures).isPresent()) return;
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
                .map(r -> new FunctionDraft.Parameter(r.nameField.getText(), r.type(), r.origin))
                .toList();
        SignatureType returns =
                keptReturnType != null ? keptReturnType : SignatureType.of(returnPicker.choice());
        return new FunctionDraft(nameField.getText(), returns, params);
    }

    private void revalidate() {
        FunctionDraft draft = draft();
        signatureLabel.setText(draft.signature());

        Optional<String> problem = draft.problem(takenSignatures);
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
        /** Set when this parameter's type is one the editor only carries; the picker is then not shown. */
        private SignatureType kept;
        /** Which parameter of the edited method this row is, or {@code NEW} for a row the "+" made. */
        private int origin = FunctionDraft.Parameter.NEW;

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

        /** This row's type: the picked one, or the one being carried through unchanged. */
        private SignatureType type() {
            return kept != null ? kept : SignatureType.of(picker.choice());
        }

        private void keep(SignatureType type) {
            kept = type;
            redrawParameters();
        }

        private HBox node() {
            HBox.setHgrow(nameField, Priority.ALWAYS);
            nameField.setPrefWidth(140);
            Region typeNode = kept != null ? keptChip(kept) : picker;
            if (kept == null) picker.setPrefWidth(180);
            HBox.setHgrow(typeNode, Priority.ALWAYS);
            HBox row = new HBox(8, nameField, typeNode, up, down, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }
    }
}
