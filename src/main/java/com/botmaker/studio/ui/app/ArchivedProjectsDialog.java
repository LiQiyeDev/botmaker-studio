package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectInfo;
import com.botmaker.studio.project.ProjectManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Dedicated page for archived (soft-deleted) projects: lists everything under the archive directory and
 * offers <b>Restore</b> (move back into the live projects) or <b>Delete</b> (permanent, recursive removal
 * via {@link ProjectManager#deleteProject}). Keeping the destructive delete on its own page — reachable only
 * after archiving — makes it hard to hit by accident. Invokes {@code onChange} after any mutation so the
 * caller (the project-selection screen) can refresh its live list.
 */
public final class ArchivedProjectsDialog {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");

    private final Window owner;
    private final ProjectManager projectManager;
    private final Runnable onChange;

    private final ListView<ProjectInfo> list = new ListView<>();
    private Stage stage;

    public ArchivedProjectsDialog(Window owner, ProjectManager projectManager, Runnable onChange) {
        this.owner = owner;
        this.projectManager = projectManager;
        this.onChange = onChange;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Archived Projects");

        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(ProjectInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                Label name = new Label(item.name());
                name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                Label date = new Label("Archived project · last modified " + item.lastModified().format(DATE));
                date.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
                setGraphic(new VBox(3, name, date));
            }
        });

        Button restore = new Button("Restore");
        restore.setOnAction(e -> restoreSelected());
        Button delete = new Button("Delete permanently");
        delete.setStyle("-fx-text-fill: #b00020;");
        delete.setOnAction(e -> deleteSelected());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        restore.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, restore, delete, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, new Label("Restore an archived project, or delete it permanently."), list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        root.setPadding(new Insets(16));

        reload();
        stage.setScene(new Scene(root, 560, 460));
        stage.show();
    }

    private void reload() {
        list.getItems().setAll(projectManager.listArchivedProjects());
    }

    private void restoreSelected() {
        ProjectInfo sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        try {
            projectManager.restoreProject(sel.name());
            reload();
            onChange.run();
        } catch (Exception ex) {
            error("Failed to restore project", ex.getMessage());
        }
    }

    private void deleteSelected() {
        ProjectInfo sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Permanently delete “" + sel.name() + "”?\n\nThis removes the project and all its files from "
                        + "disk. This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Delete this project forever?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;
        try {
            projectManager.deleteProject(sel.name());
            reload();
            onChange.run();
        } catch (Exception ex) {
            error("Failed to delete project", ex.getMessage());
        }
    }

    private void error(String header, String body) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(body);
        alert.showAndWait();
    }
}
