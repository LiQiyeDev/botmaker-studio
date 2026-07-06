package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.MavenService;
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

    /** Sentinel version that resolves to the newest concrete version at apply time (see {@link #resolveLatest}). */
    private static final String LATEST = "latest";

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
        /**
         * True while the combo's items/value are being mutated programmatically (initial seed + async
         * version load). Setting a {@link ComboBox}'s value fires its {@code onAction}; without this guard
         * the async {@code setValue} would call {@link #commitEdit} and tear down the editor the instant
         * versions finished loading — making the version look impossible to change.
         */
        private boolean loading;

        VersionCell() {
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> {
                if (!loading && isEditing() && combo.getValue() != null) commitEdit(combo.getValue().trim());
            });
            combo.getEditor().setOnAction(e -> {
                if (!loading && isEditing()) commitEdit(combo.getEditor().getText().trim());
            });
            // Also commit a typed-but-not-Enter'd version when focus leaves the cell (e.g. the user
            // types a version and clicks Apply). Without this the edit is silently cancelled and the
            // old version is what gets written to the pom. Guarded on the popup being closed so opening
            // the dropdown to pick a value doesn't prematurely commit.
            combo.getEditor().focusedProperty().addListener((obs, was, focused) -> {
                if (!loading && !focused && isEditing() && !combo.isShowing()) {
                    String text = combo.getEditor().getText();
                    if (text != null && !text.isBlank()) commitEdit(text.trim());
                }
            });
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) return;
            loading = true;
            combo.getItems().setAll(getItem() == null ? List.of() : List.of(getItem()));
            combo.setValue(getItem());
            loading = false;
            setText(null);
            setGraphic(combo);
            combo.requestFocus();
            UserLibrary lib = getCurrentRow();
            if (lib != null) loadVersions(lib);
        }

        /** Populates the combo with available versions for {@code lib} (newest first, with a "latest"
         * option on top), preserving the current selection and suppressing spurious commits. */
        private void loadVersions(UserLibrary lib) {
            String current = combo.getValue();
            CompletableFuture<List<String>> future = lib.groupId().startsWith("com.github.")
                    ? jitpack.fetchVersions(lib.groupId(), lib.artifactId())
                    : search.fetchVersions(lib.groupId(), lib.artifactId(), VERSION_LIMIT);
            future.thenAccept(versions -> Platform.runLater(() -> {
                loading = true;
                combo.getItems().setAll(withLatest(versions));
                if (current != null && !current.isBlank()) combo.setValue(current);
                loading = false;
            }));
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

    /** Prepends the {@link #LATEST} sentinel to a fetched (newest-first) version list. */
    private static List<String> withLatest(List<String> versions) {
        List<String> items = new java.util.ArrayList<>(versions.size() + 1);
        items.add(LATEST);
        items.addAll(versions);
        return items;
    }

    /**
     * Resolves a library's version, mapping the {@link #LATEST} sentinel to the newest concrete version
     * (JitPack for {@code com.github.*}, otherwise Maven Central — both list newest first). Non-sentinel
     * versions pass through unchanged. Resolves to {@code ""} if no version could be determined.
     */
    private CompletableFuture<String> resolveLatest(UserLibrary lib) {
        String version = lib.version() == null ? "" : lib.version().trim();
        if (!LATEST.equalsIgnoreCase(version)) {
            return CompletableFuture.completedFuture(version);
        }
        return lib.groupId().startsWith("com.github.")
                ? jitpack.fetchLatestVersion(lib.groupId(), lib.artifactId())
                : search.fetchVersions(lib.groupId(), lib.artifactId(), 1)
                        .thenApply(v -> v.isEmpty() ? "" : v.get(0));
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
                    versionCombo.getItems().setAll(withLatest(versions));
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

        // Resolve any "latest" versions to their concrete newest before writing the pom, so the pom stays
        // pinned to a real version. Each row resolves independently and off the FX thread.
        List<CompletableFuture<UserLibrary>> resolved = libraries.stream()
                .map(lib -> resolveLatest(lib)
                        .thenApply(v -> new UserLibrary(lib.groupId(), lib.artifactId(), v)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(resolved.toArray(new CompletableFuture[0]))
                .thenAccept(ignored -> {
                    List<UserLibrary> libs = resolved.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.toList());

                    UserLibrary unresolved = libs.stream()
                            .filter(l -> l.version() == null || l.version().isBlank())
                            .findFirst()
                            .orElse(null);
                    if (unresolved != null) {
                        Platform.runLater(() -> {
                            setBusy(apply, cancel, false);
                            error("Could not resolve a version for " + unresolved.groupArtifact() + ".");
                        });
                        return;
                    }

                    String sdkVersion = libs.stream()
                            .filter(ManageLibrariesDialog::isSdk)
                            .map(UserLibrary::version)
                            .findFirst()
                            .orElse("");
                    List<UserLibrary> userLibs = libs.stream()
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
                });
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
