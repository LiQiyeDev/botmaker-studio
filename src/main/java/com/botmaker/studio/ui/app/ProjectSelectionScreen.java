package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectInfo;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubGallery;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Project selection screen shown on startup with project creation capability
 */
public class ProjectSelectionScreen {

    private final ProjectManager projectManager;
    private final ProjectCreator projectCreator;
    private final JitPackSearch jitPackSearch = new JitPackSearch();

    /** Convenience SDK version option that resolves to the newest concrete version on create. */
    private static final String SDK_VERSION_LATEST = "latest";

    private final GitHubClient gitHubClient = new GitHubClient();
    private final GitHubAuth gitHubAuth = new GitHubAuth();
    /** The signed-in GitHub login, resolved lazily; used to tell "published by you" from "imported". */
    private volatile String myLogin;

    // Changed to BiConsumer to pass (ProjectName, ShouldClearCache)
    private final BiConsumer<String, Boolean> onProjectSelected;

    private final Stage stage;
    private ListView<Row> projectListView;
    private CheckBox showArchivedCheckbox;
    private CheckBox myProjectsCheckbox;
    private ComboBox<SortMode> sortCombo;
    private Button openButton;
    private Button createButton;
    private Button galleryButton;
    private Button archiveButton;
    private Button restoreButton;

    /** Where a project came from, derived from its provenance + the signed-in login. */
    private enum Ownership { LOCAL, MINE, IMPORTED }

    /** A row in the list: either a non-selectable group header or a project. */
    private sealed interface Row permits HeaderRow, ProjectRow {}
    private record HeaderRow(String title) implements Row {}
    private record ProjectRow(ProjectInfo info, Ownership owner) implements Row {}

    /** Sort order offered in the footer dropdown. */
    private enum SortMode {
        NAME_ASC("Name ↑"), NAME_DESC("Name ↓"), DATE_ASC("Oldest first"), DATE_DESC("Newest first");
        private final String label;
        SortMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public ProjectSelectionScreen(Stage stage, BiConsumer<String, Boolean> onProjectSelected) {
        this.stage = stage;
        this.projectManager = new ProjectManager();
        this.projectCreator = new ProjectCreator();
        this.onProjectSelected = onProjectSelected;
    }

    public Scene createScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));

        // Header
        Label titleLabel = new Label("Select a Project");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label versionLabel = new Label("v" + com.botmaker.studio.config.AppVersion.get());
        versionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        GitHubAccountBar accountBar = new GitHubAccountBar(stage, gitHubAuth, gitHubClient, this::onAuthChanged);
        VBox header = new VBox(10, titleLabel, versionLabel, accountBar);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(0, 0, 20, 0));

        // Project list
        projectListView = new ListView<>();
        projectListView.setPrefHeight(400);
        projectListView.setCellFactory(lv -> new RowCell());
        // Header rows aren't a meaningful selection — bounce selection to the nearest project row.
        projectListView.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now instanceof HeaderRow) Platform.runLater(this::selectNearestProjectRow);
        });

        resolveLogin();

        // Controls Area
        openButton = new Button("Open Project");
        openButton.setPrefWidth(150);
        openButton.setDefaultButton(true);
        openButton.setOnAction(e -> openSelectedProject());

        createButton = new Button("Create New Project");
        createButton.setPrefWidth(150);
        createButton.setOnAction(e -> showCreateProjectDialog());

        galleryButton = new Button("Browse Gallery");
        galleryButton.setPrefWidth(150);
        galleryButton.setOnAction(e -> showGallery());

        archiveButton = new Button("Archive");
        archiveButton.setPrefWidth(110);
        archiveButton.setOnAction(e -> archiveSelectedProject());

        restoreButton = new Button("Restore");
        restoreButton.setPrefWidth(110);
        restoreButton.setOnAction(e -> restoreSelectedProject());

        sortCombo = new ComboBox<>();
        sortCombo.getItems().setAll(SortMode.values());
        sortCombo.setValue(SortMode.NAME_ASC);
        sortCombo.valueProperty().addListener((o, was, now) -> rebuildRows());

        myProjectsCheckbox = new CheckBox("My projects only");
        myProjectsCheckbox.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        myProjectsCheckbox.setTooltip(new Tooltip("Sign in with GitHub to filter to your own projects."));
        myProjectsCheckbox.setDisable(!gitHubAuth.isAuthenticated());
        myProjectsCheckbox.selectedProperty().addListener((o, was, now) -> rebuildRows());

        showArchivedCheckbox = new CheckBox("Show archived projects");
        showArchivedCheckbox.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        showArchivedCheckbox.selectedProperty().addListener((o, was, now) -> updateMode());

        projectListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !showArchivedCheckbox.isSelected()) {
                openSelectedProject();
            }
        });

        HBox sortRow = new HBox(10, new Label("Sort:"), sortCombo, myProjectsCheckbox, showArchivedCheckbox);
        sortRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonBox = new HBox(10, openButton, createButton, galleryButton, archiveButton, restoreButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox footer = new VBox(15, sortRow, buttonBox);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(20, 0, 0, 0));

        VBox center = new VBox(10, projectListView, footer);
        root.setTop(header);
        root.setCenter(center);

        updateMode();

        return new Scene(root, 620, 600);
    }

    /** Renders a row: a bold group header (non-selectable) or the project card. */
    private final class RowCell extends ListCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().remove("group-header");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                setDisable(false);
                return;
            }
            if (row instanceof HeaderRow header) {
                setGraphic(buildHeaderCell(header.title()));
                setDisable(true); // not a selectable target
            } else if (row instanceof ProjectRow projectRow) {
                setGraphic(buildProjectCell(projectRow));
                setDisable(false);
            }
        }
    }

    private Label buildHeaderCell(String title) {
        Label label = new Label(title);
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555; "
                + "-fx-padding: 6 0 2 0;");
        return label;
    }

    /** The graphic for one project row: name, badge, path and last-modified. */
    private VBox buildProjectCell(ProjectRow row) {
        ProjectInfo project = row.info();
        Label nameLabel = new Label(project.name());
        nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        HBox nameRow = new HBox(8, nameLabel, ownershipBadge(project, row.owner()));
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label pathLabel = new Label(project.projectPath().toString());
        pathLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Label dateLabel = new Label("Last modified: " +
                project.lastModified().format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")));
        dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        return new VBox(5, nameRow, pathLabel, dateLabel);
    }

    /** A small coloured pill saying whether a project is local, published by you, or imported. */
    private Label ownershipBadge(ProjectInfo project, Ownership owner) {
        String text;
        String bg;
        switch (owner) {
            case LOCAL -> { text = "Local"; bg = "#5b7fff"; }
            case MINE -> { text = "Published by you @ " + tagOf(project); bg = "#1a7f37"; }
            default -> { text = "Imported from " + ownerOf(project) + " @ " + tagOf(project); bg = "#8250df"; }
        }
        Label badge = new Label(text);
        badge.setStyle("-fx-font-size: 10px; -fx-text-fill: white; -fx-background-radius: 3; "
                + "-fx-padding: 1 6 1 6; -fx-background-color: " + bg + ";");
        return badge;
    }

    private static String ownerOf(ProjectInfo p) {
        return BotSource.read(p.projectPath()).map(BotSource::owner).orElse("");
    }

    private static String tagOf(ProjectInfo p) {
        return BotSource.read(p.projectPath()).map(BotSource::tag).orElse("");
    }

    /** Ownership of a project from its provenance and the signed-in login. */
    private Ownership ownershipOf(ProjectInfo project) {
        Optional<BotSource> source = BotSource.read(project.projectPath());
        if (source.isEmpty()) return Ownership.LOCAL;
        boolean mine = myLogin != null && myLogin.equalsIgnoreCase(source.get().owner());
        return mine ? Ownership.MINE : Ownership.IMPORTED;
    }

    private void selectNearestProjectRow() {
        List<Row> rows = projectListView.getItems();
        for (Row row : rows) {
            if (row instanceof ProjectRow) {
                projectListView.getSelectionModel().select(row);
                return;
            }
        }
        projectListView.getSelectionModel().clearSelection();
    }

    private ProjectInfo selectedProject() {
        Row row = projectListView.getSelectionModel().getSelectedItem();
        return row instanceof ProjectRow projectRow ? projectRow.info() : null;
    }

    private void openSelectedProject() {
        ProjectInfo selected = selectedProject();
        if (selected != null) {
            onProjectSelected.accept(selected.name(), false);
        }
    }

    /**
     * Rebuilds the list rows from the current source (live vs archived), applying the "My projects"
     * filter, the chosen sort, and Local/Imported group headers.
     */
    private void rebuildRows() {
        if (projectListView == null) return;
        boolean archived = showArchivedCheckbox != null && showArchivedCheckbox.isSelected();
        List<ProjectInfo> projects = archived
                ? projectManager.listArchivedProjects()
                : projectManager.listProjects();

        boolean mineOnly = myProjectsCheckbox != null && myProjectsCheckbox.isSelected();
        List<ProjectRow> local = new ArrayList<>();
        List<ProjectRow> imported = new ArrayList<>();
        for (ProjectInfo p : projects) {
            Ownership owner = ownershipOf(p);
            if (owner == Ownership.IMPORTED) {
                if (mineOnly) continue;
                imported.add(new ProjectRow(p, owner));
            } else {
                local.add(new ProjectRow(p, owner));
            }
        }

        Comparator<ProjectRow> cmp = sortComparator();
        local.sort(cmp);
        imported.sort(cmp);

        List<Row> rows = new ArrayList<>();
        if (!local.isEmpty()) {
            rows.add(new HeaderRow("Local"));
            rows.addAll(local);
        }
        if (!imported.isEmpty()) {
            rows.add(new HeaderRow("Imported"));
            rows.addAll(imported);
        }
        projectListView.getItems().setAll(rows);
        selectNearestProjectRow();
    }

    private Comparator<ProjectRow> sortComparator() {
        SortMode mode = sortCombo == null ? SortMode.NAME_ASC : sortCombo.getValue();
        Comparator<ProjectRow> byName = Comparator.comparing(r -> r.info().name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<ProjectRow> byDate = Comparator.comparing(r -> r.info().lastModified());
        return switch (mode) {
            case NAME_ASC -> byName;
            case NAME_DESC -> byName.reversed();
            case DATE_ASC -> byDate;
            case DATE_DESC -> byDate.reversed();
        };
    }

    /** Backwards-compatible alias used by callers that just need the list refreshed. */
    private void refreshProjectList() {
        rebuildRows();
    }

    /** Swaps which actions are available depending on whether the archived view is showing. */
    private void updateMode() {
        boolean archived = showArchivedCheckbox.isSelected();
        setShown(openButton, !archived);
        setShown(createButton, !archived);
        setShown(galleryButton, !archived);
        setShown(archiveButton, !archived);
        setShown(restoreButton, archived);
        rebuildRows();
    }

    private void archiveSelectedProject() {
        ProjectInfo selected = selectedProject();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Archive “" + selected.name() + "”?\n\nIt will be moved to the archive and hidden from "
                        + "the project list. You can restore it later via “Show archived projects”.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Archive this project?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;
        try {
            projectManager.archiveProject(selected.name());
            rebuildRows();
        } catch (Exception ex) {
            error("Failed to archive project", ex.getMessage());
        }
    }

    private void restoreSelectedProject() {
        ProjectInfo selected = selectedProject();
        if (selected == null) return;
        try {
            projectManager.restoreProject(selected.name());
            rebuildRows();
        } catch (Exception ex) {
            error("Failed to restore project", ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // GitHub account
    // -------------------------------------------------------------------------

    private void onAuthChanged() {
        resolveLogin();
    }

    /** Resolves (or clears) the signed-in login, then re-groups the list and gates the "My projects" filter. */
    private void resolveLogin() {
        if (!gitHubAuth.isAuthenticated()) {
            myLogin = null;
            updateMyProjectsGate();
            rebuildRows();
            return;
        }
        gitHubAuth.login(gitHubClient).thenAccept(login -> Platform.runLater(() -> {
            myLogin = login.isBlank() ? null : login;
            updateMyProjectsGate();
            rebuildRows();
        }));
    }

    /** Enables the "My projects" filter only when signed in; unchecks it when signing out. */
    private void updateMyProjectsGate() {
        if (myProjectsCheckbox == null) return;
        boolean authed = gitHubAuth.isAuthenticated();
        myProjectsCheckbox.setDisable(!authed);
        if (!authed) myProjectsCheckbox.setSelected(false);
    }

    private void showCreateProjectDialog() {
        Dialog<CreateRequest> dialog = new Dialog<>();
        dialog.setTitle("Create New Project");
        dialog.setHeaderText("Enter project name");

        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        TextField projectNameField = new TextField();
        projectNameField.setPromptText("ProjectName");

        Label instructionLabel = new Label("Project name must:");
        instructionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Label rule1 = new Label("• Start with an uppercase letter");
        rule1.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label rule2 = new Label("• Contain only letters and numbers");
        rule2.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label rule3 = new Label("• Be between 2-50 characters");
        rule3.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label exampleLabel = new Label("Example: MyFirstProject");
        exampleLabel.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: gray;");

        // BotMaker SDK version picker (latest from JitPack, but selectable)
        ComboBox<String> sdkVersionCombo = new ComboBox<>();
        sdkVersionCombo.setEditable(true);
        sdkVersionCombo.setMaxWidth(Double.MAX_VALUE);
        // Seed with the fallback so the field is never empty (and works offline); the JitPack fetch then
        // replaces the list with the real versions and preselects the latest.
        sdkVersionCombo.getItems().add(MavenService.SDK_FALLBACK_VERSION);
        sdkVersionCombo.setValue(MavenService.SDK_FALLBACK_VERSION);
        loadSdkVersions(sdkVersionCombo);

        content.getChildren().addAll(
                new Label("Project Name:"),
                projectNameField,
                instructionLabel,
                rule1,
                rule2,
                rule3,
                exampleLabel,
                new Label("BotMaker SDK version:"),
                sdkVersionCombo
        );

        dialog.getDialogPane().setContent(content);

        Button createButton = (Button) dialog.getDialogPane().lookupButton(createButtonType);
        createButton.setDisable(true);

        projectNameField.textProperty().addListener((observable, oldValue, newValue) -> {
            boolean isValid = isValidProjectName(newValue);
            createButton.setDisable(!isValid);
            if (newValue.isEmpty()) projectNameField.setStyle("");
            else if (isValid) projectNameField.setStyle("-fx-border-color: green; -fx-border-width: 2px;");
            else projectNameField.setStyle("-fx-border-color: red; -fx-border-width: 2px;");
        });

        javafx.application.Platform.runLater(projectNameField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == createButtonType) {
                String version = sdkVersionCombo.getValue() == null ? "" : sdkVersionCombo.getValue().trim();
                // "latest" is a convenience option — resolve it to the newest concrete version already loaded
                // in the combo (newest first), so the new project's pom is pinned to a real version.
                if (SDK_VERSION_LATEST.equalsIgnoreCase(version)) {
                    version = sdkVersionCombo.getItems().stream()
                            .filter(v -> !SDK_VERSION_LATEST.equalsIgnoreCase(v))
                            .findFirst()
                            .orElse(MavenService.SDK_FALLBACK_VERSION);
                }
                return new CreateRequest(projectNameField.getText(), version);
            }
            return null;
        });

        Optional<CreateRequest> result = dialog.showAndWait();
        result.ifPresent(req -> createProject(req.projectName(), req.sdkVersion()));
    }

    /** Replaces the combo's items with the JitPack versions and preselects the latest (keeps the seeded
     * fallback if the fetch returns nothing, e.g. offline). */
    private void loadSdkVersions(ComboBox<String> combo) {
        jitPackSearch.fetchVersions(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID)
                .thenAccept(versions -> Platform.runLater(() -> {
                    if (versions.isEmpty()) return; // keep the pre-seeded fallback
                    List<String> items = new java.util.ArrayList<>(versions.size() + 1);
                    items.add(SDK_VERSION_LATEST);
                    items.addAll(versions);
                    combo.getItems().setAll(items);
                    combo.setValue(versions.get(0)); // newest first
                }));
    }

    /** Result of the create-project dialog. */
    private record CreateRequest(String projectName, String sdkVersion) {}

    private void showGallery() {
        GitHubGallery gallery = new GitHubGallery(gitHubClient);
        BotInstaller installer = new BotInstaller(gitHubClient, gallery);
        GalleryDialog dialog = new GalleryDialog(stage, gallery, installer);
        // Installs land new projects in PROJECTS_ROOT; reflect them when the gallery closes.
        dialog.show(this::refreshProjectList);
    }

    private boolean isValidProjectName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        if (!name.matches("^[A-Z][a-zA-Z0-9]*$")) return false;
        if (name.length() < 2 || name.length() > 50) return false;
        if (projectCreator.projectExists(name)) return false;
        return true;
    }

    private void createProject(String projectName, String sdkVersion) {
        try {
            projectCreator.createProject(projectName, sdkVersion);
            rebuildRows();
            for (Row row : projectListView.getItems()) {
                if (row instanceof ProjectRow projectRow && projectRow.info().name().equals(projectName)) {
                    projectListView.getSelectionModel().select(row);
                    break;
                }
            }
        } catch (Exception e) {
            error("Failed to create project", e.getMessage());
        }
    }

    private static void setShown(Region node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void error(String header, String body) {
        Alert errorAlert = new Alert(Alert.AlertType.ERROR);
        errorAlert.initOwner(stage);
        errorAlert.setTitle("Error");
        errorAlert.setHeaderText(header);
        errorAlert.setContentText(body);
        errorAlert.showAndWait();
    }
}
