package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectInfo;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Project selection screen shown on startup with project creation capability.
 *
 * <p>A {@link ProjectWindow} like the other two, though it shows no project: it is the third scene the shell's
 * one stage holds, and the reason it is under the contract is that it had grown a {@code createScene()} of
 * exactly the same shape without any of the same guarantees — nothing released what it acquired, and its scene
 * carried a size onto a window the user had already sized.
 */
public class ProjectSelectionScreen implements ProjectWindow {

    private final ProjectManager projectManager;
    private final ProjectCreator projectCreator;

    // The JitPackSearch and the "latest" convenience option went with the SDK version combo on 2026-09-04:
    // this screen no longer asks for any version, so it reaches no version index. ManageLibrariesDialog
    // still has its own.

    private final GitHubClient gitHubClient = new GitHubClient();
    private final GitHubAuth gitHubAuth = new GitHubAuth();
    /** The signed-in GitHub login, resolved lazily; used to tell "published by you" from "imported". */
    private volatile String myLogin;

    /**
     * Notified to open a project. {@code freshlyCreated} is true only for a brand-new project (from
     * {@link #createProject}), so the caller can auto-open the Project Setup wizard on creation but not on a
     * plain open.
     */
    @FunctionalInterface
    public interface OpenHandler {
        void open(String projectName, boolean clearCache, boolean freshlyCreated);
    }

    private final OpenHandler onProjectSelected;

    private final Stage stage;
    private ListView<Row> projectListView;
    private CheckBox myProjectsCheckbox;
    private ComboBox<SortMode> sortCombo;
    private Button openButton;
    private Button createButton;
    private Button galleryButton;
    private Button archiveButton;
    private Button archivedButton;

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

    /**
     * Newest first. The project you just made, or were last working on, is the one you almost certainly want —
     * and it is the one an alphabetical list is most likely to bury somewhere off-screen.
     */
    private static final SortMode DEFAULT_SORT = SortMode.DATE_DESC;

    /** The user's saved sort order, or {@link #DEFAULT_SORT} if they've never chosen one. */
    private static SortMode savedSortMode() {
        String saved = ProjectPreferences.getSortMode();
        if (saved == null) return DEFAULT_SORT;
        try {
            return SortMode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return DEFAULT_SORT;   // a preference from an older build that no longer names anything
        }
    }

    public ProjectSelectionScreen(Stage stage, OpenHandler onProjectSelected) {
        this.stage = stage;
        this.projectManager = new ProjectManager();
        this.projectCreator = new ProjectCreator();
        this.onProjectSelected = onProjectSelected;
    }

    /**
     * Nothing to release. Both HTTP clients here are the JDK's, whose executor is daemon-threaded and
     * collected with the client, and the screen registers no listener outside its own scene — but the method
     * is the contract's, and a future field that <em>does</em> need releasing now has the place to be released
     * in rather than a reason to opt out of the interface.
     */
    @Override
    public void dispose() {}

    @Override
    public Scene createScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));

        // Header
        Label titleLabel = new Label("Select a Project");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label versionLabel = new Label("v" + com.botmaker.studio.config.AppVersion.get());
        versionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        GitHubAccountBar accountBar = new GitHubAccountBar(stage, gitHubAuth, gitHubClient, this::onAuthChanged);
        // No Google bar here: GoogleConfig.OAUTH_CLIENT_ID is blank, so the bar could only ever render itself
        // invisible. The plumbing stays (sharing/GoogleAuth, ui/app/GoogleAccountBar) for when a client id and a
        // backend exist; until then the editor's round G button carries the "not available yet" reason.
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

        archivedButton = new Button("View Archived…");
        archivedButton.setPrefWidth(130);
        archivedButton.setOnAction(e -> showArchivedProjects());

        sortCombo = new ComboBox<>();
        sortCombo.getItems().setAll(SortMode.values());
        sortCombo.setValue(savedSortMode());
        sortCombo.valueProperty().addListener((o, was, now) -> {
            if (now != null) ProjectPreferences.updateSortMode(now.name());
            rebuildRows();
        });

        myProjectsCheckbox = new CheckBox("My projects only");
        myProjectsCheckbox.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        myProjectsCheckbox.setTooltip(new Tooltip("Sign in with GitHub to filter to your own projects."));
        myProjectsCheckbox.setDisable(!gitHubAuth.isAuthenticated());
        myProjectsCheckbox.selectedProperty().addListener((o, was, now) -> rebuildRows());

        projectListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelectedProject();
            }
        });

        HBox sortRow = new HBox(10, new Label("Sort:"), sortCombo, myProjectsCheckbox);
        sortRow.setAlignment(Pos.CENTER_LEFT);

        HBox buttonBox = new HBox(10, openButton, createButton, galleryButton, archiveButton, archivedButton);
        buttonBox.setAlignment(Pos.CENTER);

        VBox footer = new VBox(15, sortRow, buttonBox);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(20, 0, 0, 0));

        VBox center = new VBox(10, projectListView, footer);
        root.setTop(header);
        root.setCenter(center);

        rebuildRows();

        // Unsized: this is one of four scenes swapped onto the *same* shell stage (selector → loading →
        // editor → Runner), and a scene that carries a size imposes it on a window the user has already
        // sized — or, when the window is maximized and refuses, keeps that size for itself and lays the
        // screen out in a 620×600 corner of it.
        return ThemedWindows.scene(root);
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
            onProjectSelected.open(selected.name(), false, false);
        }
    }

    /**
     * Rebuilds the list rows from the current source (live vs archived), applying the "My projects"
     * filter, the chosen sort, and Local/Imported group headers.
     */
    private void rebuildRows() {
        if (projectListView == null) return;
        List<ProjectInfo> projects = projectManager.listProjects();

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
        SortMode mode = sortCombo == null || sortCombo.getValue() == null ? DEFAULT_SORT : sortCombo.getValue();
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

    private void archiveSelectedProject() {
        ProjectInfo selected = selectedProject();
        if (selected == null) return;
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "Archive “" + selected.name() + "”?\n\nIt will be moved to the archive and hidden from "
                        + "the project list. You can restore or permanently delete it later via “View Archived…”.",
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

    /** Opens the dedicated archived-projects page (restore / permanent delete), refreshing on any change. */
    private void showArchivedProjects() {
        new ArchivedProjectsDialog(stage, projectManager, this::rebuildRows).show();
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
        ThemedWindows.apply(dialog);
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

        Label rule1 = new Label("• Start with a letter");
        rule1.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label rule2 = new Label("• Contain only letters and numbers");
        rule2.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label rule3 = new Label("• Be between 2-50 characters");
        rule3.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label exampleLabel = new Label("Example: MyFirstProject");
        exampleLabel.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: gray;");

        // There is no SDK version row here any more (2026-09-04), and its absence is the visible half of the
        // blank-project split. A blank project names no plugin at all, so there is no version to pin; a
        // template brings its author's own pom, versions and libraries, and keeping it is the point of a
        // template being a real published bot. Neither shape has a question to ask, so the control, its
        // JitPack fetch, the local-build decoration and the show/hide listener that toggled it against the
        // template row are all gone. A project's SDK version is Project ▸ Manage Libraries' business, which
        // is where every other version already lives.

        // The standard-resolution dropdown and its landscape/portrait toggle were here until 2026-09-01.
        // The size a project's pictures are captured at is the capturing plugin's setting, seeded by its own
        // toolbar item on the first capture — so New Project no longer asks for it, and a fresh project has
        // no capture.width/capture.height until a picture is taken.

        // The starting point. "Blank" is Studio's own and is always here, which is what makes New Project work
        // with no network; every other row is a published bot tagged `template` in the gallery, fetched in the
        // background so an unreachable index costs nothing but the extra rows.
        ComboBox<TemplateChoice> templateCombo = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(TemplateChoice.blank()));
        templateCombo.setValue(TemplateChoice.blank());
        templateCombo.setMaxWidth(Double.MAX_VALUE);
        templateCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TemplateChoice t) {
                return t == null ? "" : t.label();
            }
            @Override public TemplateChoice fromString(String s) { return null; }
        });
        loadTemplates(templateCombo);

        // One line under the dropdown, saying what the chosen row actually gives you. It matters more than
        // it looks: "Blank" now means a plain Java project with no bot API in it, and a user who picks it
        // expecting the old behaviour would find an empty palette and no explanation.
        Label startNote = new Label();
        startNote.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        startNote.setWrapText(true);
        Runnable describeChoice = () -> {
            TemplateChoice now = templateCombo.getValue();
            startNote.setText(now == null || now.isBlank()
                    ? "A plain Java project — a pom, a source folder and a main(). Add the BotMaker SDK, or "
                            + "any other plugin, from Project ▸ Manage Plugins."
                    : "This template brings its own SDK and libraries — change them later in "
                            + "Project ▸ Manage Libraries.");
        };
        templateCombo.valueProperty().addListener((o, was, now) -> describeChoice.run());
        describeChoice.run();

        content.getChildren().addAll(
                new Label("Project Name:"),
                projectNameField,
                instructionLabel,
                rule1,
                rule2,
                rule3,
                exampleLabel,
                new Label("Start from:"),
                templateCombo,
                startNote
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
                TemplateChoice choice = templateCombo.getValue() == null
                        ? TemplateChoice.blank() : templateCombo.getValue();
                return new CreateRequest(projectNameField.getText(), choice);
            }
            return null;
        });

        Optional<CreateRequest> result = dialog.showAndWait();
        result.ifPresent(req -> {
            if (req.template().isBlank()) {
                createProject(req.projectName());
            } else {
                createFromTemplate(req.projectName(), req.template().entry());
            }
        });
    }

    /**
     * One row of the "Start from" list: Studio's blank project, or a published template.
     *
     * <p>{@code entry} is null for the blank one rather than there being two types, because the list is one
     * list to the user and the difference is a branch at creation time, not a kind of thing.
     */
    private record TemplateChoice(GalleryEntry entry) {
        static TemplateChoice blank() {
            return new TemplateChoice(null);
        }

        boolean isBlank() {
            return entry == null;
        }

        String label() {
            if (entry == null) return "Blank — a main() and nothing else";
            String description = entry.description() == null || entry.description().isBlank()
                    ? "" : " — " + entry.description();
            return entry.name() + description + "  (" + entry.owner() + ")";
        }
    }

    /**
     * Appends the gallery's templates to {@code combo}, off the FX thread.
     *
     * <p>Failure is silent on purpose: the list already holds the blank project, which is a complete answer to
     * "what can I start from", and an error dialog in front of a working New Project dialog would be the
     * network's problem presented as the user's.
     */
    private void loadTemplates(ComboBox<TemplateChoice> combo) {
        new GitHubGallery(gitHubClient, gitHubAuth).browse().thenAccept(entries -> Platform.runLater(() -> {
            for (GalleryEntry entry : entries) {
                if (entry.isTemplate()) combo.getItems().add(new TemplateChoice(entry));
            }
        }));
    }

    // loadSdkVersions and decorateLocalBuilds were here until 2026-09-04, with the SDK version combo they
    // filled: a JitPack fetch, a "latest" convenience row and a cell factory badging ~/.m2 dev builds. New
    // Project asks for no SDK version now, so they had nothing to fill. MavenService.localSdkVersions()
    // survives with one caller, ManageLibrariesDialog, which is where a project's SDK version is chosen.

    /** Result of the create-project dialog. */
    private record CreateRequest(String projectName, TemplateChoice template) {}

    private void showGallery() {
        GitHubGallery gallery = new GitHubGallery(gitHubClient, gitHubAuth);
        BotInstaller installer = new BotInstaller(gitHubClient, gallery);
        GalleryDialog dialog = new GalleryDialog(stage, gallery, installer, gitHubAuth, gitHubClient);
        // Installs land new projects in PROJECTS_ROOT; reflect them when the gallery closes.
        dialog.show(this::refreshProjectList);
    }

    /**
     * Mirrors {@code ProjectCreator.validateProjectName}, plus the length/uniqueness rules the dialog owns.
     *
     * <p>A lowercase first letter is fine: the Java class name is derived from the project name
     * ({@code ProjectConfig.toClassName}) rather than being it, so {@code myBot} yields {@code class MyBot}
     * while the project stays called what the user called it.
     */
    private boolean isValidProjectName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        if (!name.matches("^[A-Za-z][a-zA-Z0-9]*$")) return false;
        if (name.length() < 2 || name.length() > 50) return false;
        if (projectCreator.projectExists(name)) return false;
        return true;
    }

    /**
     * Creates the project and opens it.
     *
     * <p>It used to rebuild the list and select the new row, which left the user looking at a list of projects
     * having just said which one they wanted — and if the list was long or sorted by name, their new project
     * was somewhere off-screen. Creating a project <em>is</em> asking to work on it.
     */
    private void createProject(String projectName) {
        try {
            projectCreator.createProject(projectName, com.botmaker.studio.project.ProjectTemplate.EMPTY);
            onProjectSelected.open(projectName, false, true);
        } catch (Exception e) {
            // No version is a failure here any more: the floor went on 2026-08-25 with Studio's generation,
            // and so did the too-new probe with the scaffold contract the day before. Whatever refusal is
            // left, ProjectCreator's own sentence says it better than a header could.
            error("Could not create the project", e.getMessage());
        }
    }

    /**
     * Downloads {@code entry}'s newest release and makes it {@code projectName}.
     *
     * <p>The download and the unpack are the slow, failable part and they run off the FX thread; everything
     * the user sees about a failure is one dialog, because there is nothing half-created to explain — a
     * failed template creation deletes its own directory (see {@code ProjectCreator.createFromTemplate}).
     */
    private void createFromTemplate(String projectName, GalleryEntry entry) {
        GitHubGallery gallery = new GitHubGallery(gitHubClient, gitHubAuth);
        BotInstaller installer = new BotInstaller(gitHubClient, gallery);
        new Thread(() -> {
            try {
                String tag = gallery.latestReleaseTag(entry.owner(), entry.repo()).join();
                if (tag == null || tag.isBlank()) {
                    throw new java.io.IOException(entry.name() + " has no published release yet, so there is "
                            + "nothing to start from. Ask its author to cut one.");
                }
                projectCreator.createFromTemplate(projectName,
                        dest -> installer.unpackTemplate(entry, tag, dest));
                Platform.runLater(() -> onProjectSelected.open(projectName, false, true));
            } catch (Exception e) {
                Platform.runLater(() -> error("Could not create the project", e.getMessage()));
            }
        }, "template-create").start();
    }

    private void error(String header, String body) {
        Alert errorAlert = ThemedWindows.alert(Alert.AlertType.ERROR);
        errorAlert.initOwner(stage);
        errorAlert.setTitle("Error");
        errorAlert.setHeaderText(header);
        errorAlert.setContentText(body);
        errorAlert.showAndWait();
    }
}
