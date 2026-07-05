package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents.ResourcesChangedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.ScreenCaptureService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Manages the project's saved image templates (PNGs under {@code src/main/resources/images}): preview,
 * rename, delete, and capture a new one by cropping the screen ({@link ScreenCaptureService}). Publishes
 * {@link ResourcesChangedEvent} after any change so open template pickers can refresh.
 */
public class ResourceManagerDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final EventBus eventBus;
    private final ScreenCaptureService capture;

    private final ObservableList<Path> templates = FXCollections.observableArrayList();
    private final ImageView preview = new ImageView();
    private final Label statusLabel = new Label();
    private Stage stage;

    public ResourceManagerDialog(Window owner, ProjectConfig config, EventBus eventBus, ScreenCaptureService capture) {
        this.owner = owner;
        this.config = config;
        this.eventBus = eventBus;
        this.capture = capture;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Resource Manager — Image Templates");

        ListView<Path> list = new ListView<>(templates);
        list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : ImageTemplateLibrary.baseName(item));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, old, sel) -> showPreview(sel));

        preview.setPreserveRatio(true);
        VBox previewBox = new VBox(6, new Label("Preview"), preview);
        previewBox.setPadding(new Insets(0, 0, 0, 12));
        previewBox.setMinWidth(380);
        // Let the preview grow with the window rather than a fixed 220px box.
        preview.fitWidthProperty().bind(previewBox.widthProperty().subtract(12));
        preview.fitHeightProperty().bind(previewBox.heightProperty().subtract(28));

        HBox content = new HBox(8, list, previewBox);
        HBox.setHgrow(list, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);

        Button captureBtn = new Button("Capture new...");
        captureBtn.setOnAction(e -> captureNew());
        Button renameBtn = new Button("Rename");
        renameBtn.setOnAction(e -> rename(list.getSelectionModel().getSelectedItem()));
        Button deleteBtn = new Button("Delete");
        deleteBtn.setOnAction(e -> delete(list.getSelectionModel().getSelectedItem()));
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, captureBtn, renameBtn, deleteBtn, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, content, statusLabel, buttons);
        root.setPadding(new Insets(16));

        reload();
        stage.setScene(new Scene(root, 760, 560));
        stage.show();
    }

    private void reload() {
        templates.setAll(ImageTemplateLibrary.list(config));
    }

    private void showPreview(Path file) {
        if (file == null) { preview.setImage(null); return; }
        try {
            preview.setImage(new Image(file.toUri().toString()));
        } catch (Exception e) {
            preview.setImage(null);
        }
    }

    private void captureNew() {
        stage.setIconified(true); // get the dialog out of the way of the capture overlay
        capture.captureRegion(owner, img -> Platform.runLater(() -> {
            stage.setIconified(false);
            Optional<String> name = promptName("accept_button");
            if (name.isEmpty()) return;
            Path target = config.imagesRoot().resolve(name.get() + ".png");
            try {
                capture.savePng(img, target);
                published();
                reload();
            } catch (IOException e) {
                statusLabel.setText("Failed to save: " + e.getMessage());
            }
        }));
    }

    private void rename(Path file) {
        if (file == null) return;
        Optional<String> name = promptName(ImageTemplateLibrary.baseName(file));
        if (name.isEmpty()) return;
        Path target = config.imagesRoot().resolve(name.get() + ".png");
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            published();
            reload();
        } catch (IOException e) {
            statusLabel.setText("Failed to rename: " + e.getMessage());
        }
    }

    private void delete(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
            published();
            reload();
        } catch (IOException e) {
            statusLabel.setText("Failed to delete: " + e.getMessage());
        }
    }

    /** Prompts for a sanitized base name (letters/digits/_/-), defaulting to {@code suggested}. */
    private Optional<String> promptName(String suggested) {
        TextInputDialog dialog = new TextInputDialog(suggested);
        dialog.initOwner(stage);
        dialog.setTitle("Template name");
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        return dialog.showAndWait()
                .map(s -> s.trim().replaceAll("[^A-Za-z0-9_-]", "_"))
                .filter(s -> !s.isBlank());
    }

    private void published() {
        statusLabel.setText("");
        eventBus.publish(new ResourcesChangedEvent());
    }
}
