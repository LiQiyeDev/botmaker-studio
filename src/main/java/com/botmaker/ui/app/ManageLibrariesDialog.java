package com.botmaker.ui.app;

import com.botmaker.project.UserLibrary;
import com.botmaker.services.JitPackSearch;
import com.botmaker.services.LibraryService;
import com.botmaker.services.MavenCentralSearch;
import com.botmaker.services.MavenService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Modal dialog for adding / removing the project's user libraries. Coordinates are entered in a single
 * {@code group:artifact[:version]} field with IntelliJ-style suggestions fetched live from Maven Central
 * ({@link MavenCentralSearch}); applying changes delegates to {@link LibraryService}, which rewrites the
 * pom, re-resolves the classpath and refreshes the type index off the FX thread.
 */
public class ManageLibrariesDialog {

    private static final int SUGGESTION_LIMIT = 15;
    private static final int VERSION_LIMIT = 40;

    private final Window owner;
    private final LibraryService libraryService;
    private final MavenCentralSearch search;
    private final JitPackSearch jitpack;

    /** All rows incl. the pinned SDK row (always first); the SDK is identified by its coordinate. */
    private final ObservableList<UserLibrary> libraries = FXCollections.observableArrayList();
    private final TextField coordinateField = new TextField();
    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final ContextMenu suggestions = new ContextMenu();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private Stage stage;

    public ManageLibrariesDialog(Window owner, LibraryService libraryService,
                                 MavenCentralSearch search, JitPackSearch jitpack) {
        this.owner = owner;
        this.libraryService = libraryService;
        this.search = search;
        this.jitpack = jitpack;
    }

    /** True for the pinned BotMaker SDK row, which can be re-versioned but not removed. */
    private static boolean isSdk(UserLibrary lib) {
        return lib != null
                && MavenService.SDK_GROUP_ID.equals(lib.groupId())
                && MavenService.SDK_ARTIFACT_ID.equals(lib.artifactId());
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Manage Libraries");

        // The pinned SDK row is always first, followed by the user libraries.
        UserLibrary sdkRow = new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, libraryService.currentSdkVersion());
        libraries.add(sdkRow);
        libraries.addAll(libraryService.currentLibraries());

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().addAll(buildTable(), buildAddRow(), buildButtonBar());

        stage.setScene(new Scene(root, 560, 460));
        stage.show();
    }

    // -------------------------------------------------------------------------
    // Current libraries table
    // -------------------------------------------------------------------------

    private VBox buildTable() {
        TableView<UserLibrary> table = new TableView<>(libraries);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setEditable(true);
        table.setPlaceholder(new Label("No additional libraries. Built-in dependencies are managed automatically."));

        TableColumn<UserLibrary, String> groupCol = new TableColumn<>("Group");
        groupCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().groupId()));
        TableColumn<UserLibrary, String> artifactCol = new TableColumn<>("Artifact");
        artifactCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().artifactId() + (isSdk(c.getValue()) ? "  (SDK)" : "")));
        TableColumn<UserLibrary, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().version()));
        versionCol.setCellFactory(col -> new VersionCell());
        table.getColumns().addAll(List.of(groupCol, artifactCol, versionCol));

        Button removeBtn = new Button("Remove");
        removeBtn.setDisable(true);
        // Removable only when a non-SDK row is selected (the SDK is pinned).
        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> removeBtn.setDisable(sel == null || isSdk(sel)));
        removeBtn.setOnAction(e -> {
            UserLibrary sel = table.getSelectionModel().getSelectedItem();
            if (sel != null && !isSdk(sel)) libraries.remove(sel);
        });

        HBox tableButtons = new HBox(removeBtn);
        tableButtons.setAlignment(Pos.CENTER_RIGHT);

        Label heading = new Label("Project libraries");
        heading.setStyle("-fx-font-weight: bold;");
        Label hint = new Label("Double-click a version to change it. The BotMaker SDK is pinned and cannot be removed.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        VBox box = new VBox(6, heading, table, hint, tableButtons);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    /**
     * Editable version cell backed by a {@link ComboBox} that lazily loads available versions on edit —
     * from JitPack for {@code com.github.*} coordinates (incl. the SDK), otherwise Maven Central. Committing
     * replaces the immutable {@link UserLibrary} at this row with the chosen version.
     */
    private final class VersionCell extends javafx.scene.control.TableCell<UserLibrary, String> {
        private final ComboBox<String> combo = new ComboBox<>();

        VersionCell() {
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> {
                if (isEditing() && combo.getValue() != null) commitEdit(combo.getValue().trim());
            });
            combo.getEditor().setOnAction(e -> {
                if (isEditing()) commitEdit(combo.getEditor().getText().trim());
            });
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            combo.getItems().setAll(getItem() == null ? List.of() : List.of(getItem()));
            combo.setValue(getItem());
            setText(null);
            setGraphic(combo);
            combo.requestFocus();
            UserLibrary lib = getCurrentRow();
            if (lib != null) loadVersionsInto(combo, lib);
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem());
            setGraphic(null);
        }

        @Override
        public void commitEdit(String newVersion) {
            super.commitEdit(newVersion);
            int idx = getIndex();
            if (newVersion != null && !newVersion.isBlank() && idx >= 0 && idx < libraries.size()) {
                UserLibrary lib = libraries.get(idx);
                if (!newVersion.equals(lib.version())) {
                    libraries.set(idx, new UserLibrary(lib.groupId(), lib.artifactId(), newVersion));
                }
            }
            setText(getItem());
            setGraphic(null);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                combo.setValue(item);
                setText(null);
                setGraphic(combo);
            } else {
                setText(item);
                setGraphic(null);
            }
        }

        private UserLibrary getCurrentRow() {
            int idx = getIndex();
            return (idx >= 0 && idx < libraries.size()) ? libraries.get(idx) : null;
        }
    }

    /** Populates the combo with available versions for {@code lib}, preserving the current selection. */
    private void loadVersionsInto(ComboBox<String> combo, UserLibrary lib) {
        String current = combo.getValue();
        CompletableFuture<List<String>> future = lib.groupId().startsWith("com.github.")
                ? jitpack.fetchVersions(lib.groupId(), lib.artifactId())
                : search.fetchVersions(lib.groupId(), lib.artifactId(), VERSION_LIMIT);
        future.thenAccept(versions -> Platform.runLater(() -> {
            combo.getItems().setAll(versions);
            if (current != null && !current.isBlank()) combo.setValue(current);
        }));
    }

    // -------------------------------------------------------------------------
    // Add row with Maven Central autocomplete
    // -------------------------------------------------------------------------

    private HBox buildAddRow() {
        coordinateField.setPromptText("group:artifact (start typing to search Maven Central)");
        HBox.setHgrow(coordinateField, Priority.ALWAYS);
        versionCombo.setEditable(true);
        versionCombo.setPromptText("version");
        versionCombo.setPrefWidth(160);

        debounce.setOnFinished(e -> runSearch(coordinateField.getText()));
        coordinateField.textProperty().addListener((obs, old, text) -> debounce.playFromStart());

        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> addCurrent());

        HBox row = new HBox(8, coordinateField, versionCombo, addBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void runSearch(String query) {
        if (query == null || query.isBlank() || query.contains(":") && query.split(":").length >= 3) {
            suggestions.hide();
            return;
        }
        search.searchArtifacts(query.trim(), SUGGESTION_LIMIT)
                .thenAccept(results -> Platform.runLater(() -> showSuggestions(results)));
    }

    private void showSuggestions(List<UserLibrary> results) {
        suggestions.getItems().clear();
        if (results.isEmpty()) {
            suggestions.hide();
            return;
        }
        for (UserLibrary lib : results) {
            MenuItem item = new MenuItem(lib.groupArtifact());
            item.setOnAction(e -> selectSuggestion(lib));
            suggestions.getItems().add(item);
        }
        if (!suggestions.isShowing()) {
            suggestions.show(coordinateField, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    private void selectSuggestion(UserLibrary lib) {
        suggestions.hide();
        coordinateField.setText(lib.groupArtifact());
        coordinateField.positionCaret(coordinateField.getLength());
        loadVersions(lib.groupId(), lib.artifactId(), lib.version());
    }

    private void loadVersions(String groupId, String artifactId, String preferred) {
        versionCombo.getItems().clear();
        search.fetchVersions(groupId, artifactId, VERSION_LIMIT)
                .thenAccept(versions -> Platform.runLater(() -> {
                    versionCombo.getItems().setAll(versions);
                    if (!preferred.isBlank()) {
                        versionCombo.setValue(preferred);
                    } else if (!versions.isEmpty()) {
                        versionCombo.setValue(versions.get(0));
                    }
                }));
    }

    private void addCurrent() {
        statusLabel.setText("");
        UserLibrary lib;
        try {
            String coord = coordinateField.getText() == null ? "" : coordinateField.getText().trim();
            String version = versionCombo.getValue() == null ? "" : versionCombo.getValue().trim();
            if (coord.split(":").length >= 3) {
                lib = UserLibrary.parse(coord);
            } else {
                lib = UserLibrary.parse(coord + ":" + version);
            }
        } catch (IllegalArgumentException ex) {
            error("Enter a group:artifact and a version.");
            return;
        }

        boolean exists = libraries.stream().anyMatch(l -> l.groupArtifact().equals(lib.groupArtifact()));
        if (exists) {
            error(lib.groupArtifact() + " is already in the list.");
            return;
        }
        libraries.add(lib);
        coordinateField.clear();
        versionCombo.getItems().clear();
        versionCombo.setValue(null);
    }

    // -------------------------------------------------------------------------
    // Apply / Cancel
    // -------------------------------------------------------------------------

    private HBox buildButtonBar() {
        progress.setVisible(false);
        progress.setPrefSize(20, 20);
        statusLabel.setStyle("-fx-text-fill: #b00020;");

        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> stage.close());

        Button apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> apply(apply, cancel));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, statusLabel, spacer, cancel, apply);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void apply(Button apply, Button cancel) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText("");
        setBusy(apply, cancel, true);

        String sdkVersion = libraries.stream()
                .filter(ManageLibrariesDialog::isSdk)
                .map(UserLibrary::version)
                .findFirst()
                .orElse("");
        List<UserLibrary> userLibs = libraries.stream()
                .filter(l -> !isSdk(l))
                .collect(Collectors.toList());

        libraryService.updateLibraries(userLibs, sdkVersion)
                .whenComplete((ok, err) -> Platform.runLater(() -> {
                    setBusy(apply, cancel, false);
                    if (err != null) {
                        error(rootMessage(err));
                    } else {
                        stage.close();
                    }
                }));
    }

    private void setBusy(Button apply, Button cancel, boolean busy) {
        progress.setVisible(busy);
        apply.setDisable(busy);
        cancel.setDisable(busy);
        coordinateField.setDisable(busy);
        versionCombo.setDisable(busy);
    }

    private void error(String message) {
        statusLabel.setStyle("-fx-text-fill: #b00020;");
        statusLabel.setText(message);
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
