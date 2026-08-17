package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.ImageTemplateLibrary;
import com.botmaker.studio.services.TagCatalog;
import com.botmaker.studio.services.TemplateManifest;
import com.botmaker.studio.ui.render.components.TagPicklist;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place the project's tags are edited — "declared, not invented" needs somewhere the declaring
 * happens, and this is it.
 *
 * <p>It shows both kinds together because the useful question is "what can I file a template under", and the
 * answer is one list. What differs is what may be done to a row: a <b>custom</b> tag is the user's, so it can
 * be renamed or deleted here; an <b>activity</b> tag is a rendering of {@code activities.json} and has no
 * independent existence, so it is shown with its origin and both buttons refuse it. Renaming one means
 * renaming the activity, which is the flow editor's job — offering it here would be offering an edit that
 * either lies (the tag comes back on reload) or reaches across into a different model.
 *
 * <p>Deleting a custom tag also strips it from every template that carried it: the alternative leaves
 * assignments nobody can see, which come back to life the day someone declares the same name again.
 */
public final class TagManagerDialog {

    private final Window owner;
    private final ProjectConfig config;
    private final Runnable onChanged;

    private final ListView<TagCatalog.Tag> list = new ListView<>();
    private final Label status = new Label();
    private Stage stage;

    public TagManagerDialog(Window owner, ProjectConfig config, Runnable onChanged) {
        this.owner = owner;
        this.config = config;
        this.onChanged = onChanged;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Tags");

        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(TagCatalog.Tag item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                int count = countFor(item.name());
                String suffix = count == 1 ? " · 1 template" : " · " + count + " templates";
                setText(item.isManaged()
                        ? item.name() + suffix + "   (activity)"
                        : item.name() + suffix);
            }
        });

        Button create = new Button("New tag…");
        create.setOnAction(e -> createTag());
        Button rename = new Button("Rename…");
        rename.setOnAction(e -> renameSelected());
        Button delete = new Button("Delete");
        delete.setOnAction(e -> deleteSelected());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, create, rename, delete, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Every activity has a tag of its own, created and removed with the activity. "
                + "Custom tags are yours.");
        hint.setWrapText(true);

        VBox root = new VBox(10, hint, list, status, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        root.setPadding(new Insets(16));

        reload();
        stage.setScene(ThemedWindows.scene(root, 460, 420));
        stage.show();
    }

    private void reload() {
        TagCatalog.Tag selected = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(ImageTemplateLibrary.tagCatalog(config).tags());
        if (selected != null) {
            list.getItems().stream().filter(t -> t.name().equalsIgnoreCase(selected.name())).findFirst()
                    .ifPresent(t -> list.getSelectionModel().select(t));
        }
    }

    /** How many templates carry {@code tag} — the number that makes a delete's consequence visible. */
    private int countFor(String tag) {
        Map<String, List<java.nio.file.Path>> byTag = ImageTemplateLibrary.listByTag(config);
        List<java.nio.file.Path> files = byTag.get(tag);
        return files == null ? 0 : files.size();
    }

    private void createTag() {
        TagPicklist.promptNewTag(stage, config).ifPresent(tag -> {
            ImageTemplateLibrary.declareTag(config, tag);
            changed("Created \"" + tag + "\".");
        });
    }

    private void renameSelected() {
        TagCatalog.Tag tag = list.getSelectionModel().getSelectedItem();
        if (tag == null) {
            status.setText("Select a tag to rename.");
            return;
        }
        if (tag.isManaged()) {
            status.setText("\"" + tag.name() + "\" follows its activity — rename the activity to rename it.");
            return;
        }
        promptRename(tag.name()).ifPresent(renamed -> {
            ImageTemplateLibrary.saveManifest(config,
                    ImageTemplateLibrary.manifest(config).renamedTag(tag.name(), renamed));
            changed("Renamed to \"" + renamed + "\".");
        });
    }

    private Optional<String> promptRename(String current) {
        TagCatalog catalog = ImageTemplateLibrary.tagCatalog(config);
        while (true) {
            Dialog<String> dialog = new Dialog<>();
            ThemedWindows.apply(dialog);
            dialog.initOwner(stage);
            dialog.setTitle("Rename tag");
            dialog.setHeaderText(null);
            ButtonType ok = new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

            TextField field = new TextField(current);
            VBox box = new VBox(8, new Label("New name for \"" + current + "\":"), field);
            box.setPadding(new Insets(10));
            dialog.getDialogPane().setContent(box);
            dialog.setResultConverter(bt -> bt == ok ? field.getText() : null);

            Optional<String> raw = dialog.showAndWait();
            if (raw.isEmpty()) return Optional.empty();
            String name = TemplateManifest.sanitizeTag(raw.get());
            if (name.equalsIgnoreCase(current)) return Optional.empty();
            if (name.isBlank() || TemplateManifest.isSyntheticTag(name)) {
                warn("Please choose a name that isn't blank or a built-in group.");
                continue;
            }
            if (catalog.isDeclared(name)) {
                warn("There is already a tag called \"" + name + "\".");
                continue;
            }
            return Optional.of(name);
        }
    }

    private void deleteSelected() {
        TagCatalog.Tag tag = list.getSelectionModel().getSelectedItem();
        if (tag == null) {
            status.setText("Select a tag to delete.");
            return;
        }
        if (tag.isManaged()) {
            status.setText("\"" + tag.name() + "\" belongs to its activity — delete the activity to remove it.");
            return;
        }
        int count = countFor(tag.name());
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                count == 0
                        ? "Delete the tag \"" + tag.name() + "\"?"
                        : "Delete the tag \"" + tag.name() + "\"? It will be removed from " + count
                                + " template(s). The templates themselves are not deleted.");
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        ImageTemplateLibrary.saveManifest(config, ImageTemplateLibrary.manifest(config).undeclaring(tag.name()));
        changed("Deleted \"" + tag.name() + "\".");
    }

    private void changed(String message) {
        status.setText(message);
        reload();
        if (onChanged != null) onChanged.run();
    }

    private void warn(String message) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING, message);
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
