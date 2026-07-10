package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Local project history (linear VCS) panel: commit changes, tag a version (private / public), and safely roll
 * back to an earlier commit. Backed by {@link ProjectVcs} (JGit). All git work runs off the FX thread.
 */
public class VcsDialog {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Window owner;
    private final String projectName;
    private final Path projectDir;
    private final ProjectVcs vcs;
    private final BotPublisher publisher;
    private final Optional<BotSource> origin;

    private final ObservableList<ProjectVcs.CommitInfo> commits = FXCollections.observableArrayList();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final TextField messageField = new TextField();
    private final Button commitButton = new Button("Commit");
    private final Button tagButton = new Button("Tag…");
    private final Button rollbackButton = new Button("Roll back to selected");
    private final Button submitPatchButton = new Button("Submit patch…");
    private final ListView<ProjectVcs.CommitInfo> list = new ListView<>(commits);

    private Stage stage;

    public VcsDialog(Window owner, String projectName, Path projectDir, BotPublisher publisher) {
        this.owner = owner;
        this.projectName = projectName;
        this.projectDir = projectDir;
        this.vcs = new ProjectVcs(projectDir);
        this.publisher = publisher;
        this.origin = BotSource.read(projectDir);
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Project History — " + projectName);

        progress.setVisible(false);
        progress.setPrefSize(18, 18);

        messageField.setPromptText("Describe this change…");
        HBox.setHgrow(messageField, Priority.ALWAYS);
        commitButton.setDefaultButton(true);
        commitButton.setOnAction(e -> doCommit());
        tagButton.setOnAction(e -> doTag());
        HBox commitRow = new HBox(8, messageField, commitButton, tagButton);
        commitRow.setAlignment(Pos.CENTER_LEFT);

        list.setPlaceholder(new Label("No history yet."));
        list.setCellFactory(lv -> new CommitCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        rollbackButton.setDisable(true);
        rollbackButton.setOnAction(e -> doRollback());
        list.getSelectionModel().selectedItemProperty().addListener(
                (o, was, now) -> rollbackButton.setDisable(now == null));

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(8, progress, rollbackButton, spacer);
        // Patching upstream only makes sense for an installed community bot (it has provenance).
        if (origin.isPresent()) {
            submitPatchButton.setTooltip(new javafx.scene.control.Tooltip(
                    "Propose your changes to " + origin.get().slug() + " as a pull request."));
            submitPatchButton.setOnAction(e -> doSubmitPatch());
            bottom.getChildren().add(submitPatchButton);
        }
        bottom.getChildren().add(close);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, commitRow, list, bottom);
        root.setPadding(new Insets(14));
        stage.setScene(new Scene(root, 560, 460));
        stage.show();

        refresh();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void doCommit() {
        String message = messageField.getText() == null ? "" : messageField.getText().trim();
        run(() -> vcs.commit(message.isBlank() ? "Update" : message),
                sha -> {
                    messageField.clear();
                    if (sha == null) status("Nothing to commit — the project is unchanged.");
                });
    }

    private void doTag() {
        TextInputDialog nameDialog = new TextInputDialog();
        nameDialog.initOwner(stage);
        nameDialog.setTitle("Tag a version");
        nameDialog.setHeaderText("Tag name (e.g. v1.0.0):");
        Optional<String> name = nameDialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
        if (name.isEmpty()) return;

        ChoiceDialog<String> kind = new ChoiceDialog<>("Private", List.of("Private", "Public"));
        kind.initOwner(stage);
        kind.setTitle("Tag visibility");
        kind.setHeaderText("A public tag marks a release you intend to share; a private tag is local only.");
        boolean isPublic = kind.showAndWait().map("Public"::equals).orElse(false);

        run(() -> {
            if (isPublic) vcs.tagPublic(name.get()); else vcs.tagPrivate(name.get());
            return name.get();
        }, tag -> status((isPublic ? "Public" : "Private") + " tag " + tag + " created."));
    }

    private void doRollback() {
        ProjectVcs.CommitInfo target = list.getSelectionModel().getSelectedItem();
        if (target == null) return;
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Roll the project back to “" + target.message() + "” (" + target.shortSha() + ")?\n\n"
                        + "Your current state is snapshotted first, so nothing is lost.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Roll back to this commit?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        run(() -> { vcs.restoreTo(target.sha()); return null; },
                ignored -> status("Rolled back to " + target.shortSha() + "."));
    }

    private void doSubmitPatch() {
        if (origin.isEmpty() || publisher == null) return;
        BotSource src = origin.get();

        TextInputDialog titleDialog = new TextInputDialog("Patch from BotMaker Studio");
        titleDialog.initOwner(stage);
        titleDialog.setTitle("Submit patch to " + src.slug());
        titleDialog.setHeaderText("Pull request title:");
        Optional<String> title = titleDialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
        if (title.isEmpty()) return;

        setBusy(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.submitPatch(projectDir, src, title.get(),
                                "Submitted from BotMaker Studio.");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        status("Submit patch failed: " + rootMessage(err));
                    } else if (result != null && !result.pullRequestUrl().isBlank()) {
                        status("Pull request opened: " + result.pullRequestUrl());
                        BrowserLauncher.open(result.pullRequestUrl());
                    } else {
                        status("Patch submitted upstream.");
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    /** Runs a blocking git action off the FX thread, then refreshes the history and reports errors. */
    private <T> void run(BlockingCall<T> action, java.util.function.Consumer<T> onSuccess) {
        setBusy(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        status("Failed: " + rootMessage(err));
                    } else {
                        onSuccess.accept(result);
                        refresh();
                    }
                }));
    }

    private void refresh() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return vcs.history();
                    } catch (Exception ex) {
                        return List.<ProjectVcs.CommitInfo>of();
                    }
                })
                .thenAccept(h -> Platform.runLater(() -> commits.setAll(h)));
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        commitButton.setDisable(busy);
        tagButton.setDisable(busy);
        submitPatchButton.setDisable(busy);
        rollbackButton.setDisable(busy || list.getSelectionModel().getSelectedItem() == null);
    }

    private void status(String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    @FunctionalInterface
    private interface BlockingCall<T> {
        T call() throws Exception;
    }

    /** A history row: message + tags on top, short SHA · author · date underneath. */
    private final class CommitCell extends ListCell<ProjectVcs.CommitInfo> {
        @Override
        protected void updateItem(ProjectVcs.CommitInfo c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            String tagSuffix = c.tags().isEmpty() ? "" : "   [" + String.join(", ", c.tags()) + "]";
            Label message = new Label(c.message() + tagSuffix);
            message.setStyle("-fx-font-weight: bold;");
            Label meta = new Label(c.shortSha() + " · " + c.author() + " · " + WHEN.format(c.when()));
            meta.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
            setGraphic(new VBox(2, message, meta));
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
