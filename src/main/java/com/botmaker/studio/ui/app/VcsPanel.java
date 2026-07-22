package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.util.BrowserLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;

/**
 * The reusable VCS surface — IntelliJ's Commit tool window, reduced to what BotMaker needs. The left column is
 * the commit box (message + Commit / Publish… / propose-or-sync + the branch/provenance line); the right column
 * is a tree of changed files over a Diff/History tab pane. Selecting a changed file shows its unified diff;
 * right-clicking one offers Discard. Rolling back from history rewrites the working tree, so it publishes a
 * {@link CoreApplicationEvents.ProjectReloadRequestedEvent} — the in-memory ASTs are stale afterwards.
 *
 * <p>Extracted from {@code VcsDialog} so the bottom "VCS" tab and the standalone dialog host one implementation.
 * All git work runs off the FX thread.
 *
 * <p><b>Push is backup, not publishing.</b> This class once documented a deliberate absence of a Push button on
 * the grounds that a BotMaker project has no remote; it now creates one on demand — the first Push offers to
 * make a <em>private</em> repo and set it as {@code origin}, later pushes just go. That is the whole feature:
 * no release, no gallery entry, no provenance file. Publish / Propose still go through the GitHub Data API and
 * are unaffected — a project whose {@code origin} Push created is exactly the state Publish would have made
 * itself.
 */
public final class VcsPanel {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Window owner;
    private final String projectName;
    private final Path projectDir;
    private final BotPublisher publisher;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final EventBus eventBus;
    private final Runnable openPublish;
    private final Optional<BotSource> origin;

    private final BorderPane root = new BorderPane();
    private final ObservableList<ProjectVcs.CommitInfo> commits = FXCollections.observableArrayList();
    private final ListView<ProjectVcs.CommitInfo> history = new ListView<>(commits);
    private final TreeView<ChangedFile> changes = new TreeView<>();
    private final TextArea diffArea = new TextArea();
    private final TextArea messageField = new TextArea();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLine = new Label();

    // Commit author identity — refined to the signed-in GitHub login once resolved (3f.5).
    private volatile String authorName;
    private volatile String authorEmail;

    public VcsPanel(Window owner, String projectName, Path projectDir, BotPublisher publisher,
                    GitHubAuth auth, GitHubClient client, EventBus eventBus, Runnable openPublish) {
        this.owner = owner;
        this.projectName = projectName;
        this.projectDir = projectDir;
        this.publisher = publisher;
        this.auth = auth;
        this.client = client;
        this.eventBus = eventBus;
        this.openPublish = openPublish;
        this.origin = BotSource.read(projectDir);
        build();
        resolveIdentity();
        refresh();
    }

    /** The panel's root node — host it in a tab or a dialog scene. */
    public Region getView() {
        return root;
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void build() {
        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.HORIZONTAL);
        split.getItems().addAll(commitColumn(), changesColumn());
        split.setDividerPositions(0.34);
        root.setCenter(split);
    }

    private Region commitColumn() {
        messageField.setPromptText("Describe this change…");
        messageField.setWrapText(true);
        messageField.setPrefRowCount(4);

        Button commit = new Button("Commit");
        commit.setMaxWidth(Double.MAX_VALUE);
        commit.setOnAction(e -> doCommit());

        Button push = new Button("Push");
        push.setMaxWidth(Double.MAX_VALUE);
        push.setTooltip(new javafx.scene.control.Tooltip(
                "Back this project's history up to a private GitHub repo. Not the same as Publish — nothing "
                        + "is released or listed."));
        push.setOnAction(e -> doPush());

        Button publish = new Button("Publish…");
        publish.setMaxWidth(Double.MAX_VALUE);
        publish.setOnAction(e -> { if (openPublish != null) openPublish.run(); });

        VBox buttons = new VBox(6, commit, push, publish);

        // Community bots (provenance present) get the propose / sync-from-upstream pair instead of a bare push.
        if (origin.isPresent()) {
            Button propose = new Button("Propose your changes…");
            propose.setMaxWidth(Double.MAX_VALUE);
            propose.setTooltip(new javafx.scene.control.Tooltip(
                    "Open (or update) a pull request to " + origin.get().slug() + "."));
            propose.setOnAction(e -> doPropose());

            Button sync = new Button("Get latest from original");
            sync.setMaxWidth(Double.MAX_VALUE);
            sync.setTooltip(new javafx.scene.control.Tooltip(
                    "Sync your fork of " + origin.get().slug() + " with the upstream default branch."));
            sync.setOnAction(e -> doSync());
            buttons.getChildren().addAll(propose, sync);
        }

        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        statusLine.setWrapText(true);
        statusLine.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        HBox progressRow = new HBox(6, progress, statusLine);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label provenance = new Label(origin
                .map(s -> "Based on " + s.slug() + " @ " + s.tag())
                .orElse("Local project — no upstream."));
        provenance.setWrapText(true);
        provenance.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");

        VBox column = new VBox(8,
                new Label("Commit message"), messageField, buttons, progressRow,
                new javafx.scene.control.Separator(), provenance);
        column.setPadding(new Insets(10));
        column.setMinWidth(220);
        return column;
    }

    private Region changesColumn() {
        changes.setShowRoot(false);
        changes.setCellFactory(tv -> new ChangeCell());
        changes.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            ChangedFile f = now == null ? null : now.getValue();
            if (f != null && f.path != null) showDiff(f.path);
        });
        VBox.setVgrow(changes, Priority.ALWAYS);
        VBox changesBox = new VBox(4, new Label("Changes"), changes);
        changesBox.setPadding(new Insets(6));

        diffArea.setEditable(false);
        diffArea.getStyleClass().add("console-area");
        diffArea.setStyle("-fx-font-family: monospace;");

        history.setPlaceholder(new Label("No history yet."));
        history.setCellFactory(lv -> new CommitCell());
        history.setContextMenu(rollbackMenu());

        TabPane detail = new TabPane();
        Tab diffTab = new Tab("Diff", new ScrollPane(diffArea)); diffTab.setClosable(false);
        Tab historyTab = new Tab("History", history); historyTab.setClosable(false);
        detail.getTabs().addAll(diffTab, historyTab);

        SplitPane vertical = new SplitPane();
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.getItems().addAll(changesBox, detail);
        vertical.setDividerPositions(0.5);
        return vertical;
    }

    private ContextMenu rollbackMenu() {
        MenuItem rollback = new MenuItem("Roll back to this commit…");
        rollback.setOnAction(e -> doRollback());
        return new ContextMenu(rollback);
    }

    // -------------------------------------------------------------------------
    // Changed-files tree
    // -------------------------------------------------------------------------

    /** A node in the changes tree: a directory (path segment) or a changed file with a status label. */
    private record ChangedFile(String label, String path, String status) {
        boolean isFile() { return path != null; }
    }

    private void rebuildChanges(ProjectVcs.FileStatus fs) {
        TreeItem<ChangedFile> rootItem = new TreeItem<>(new ChangedFile("", null, null));
        SortedMap<String, String> labelled = fs.labelled();
        for (Map.Entry<String, String> e : labelled.entrySet()) {
            insertPath(rootItem, e.getKey(), e.getValue());
        }
        expandAll(rootItem);
        changes.setRoot(rootItem);
        if (labelled.isEmpty()) {
            TreeItem<ChangedFile> placeholder = new TreeItem<>(
                    new ChangedFile("No changes — the working tree matches the last commit.", null, null));
            TreeItem<ChangedFile> empty = new TreeItem<>(new ChangedFile("", null, null));
            empty.getChildren().add(placeholder);
            empty.setExpanded(true);
            changes.setRoot(empty);
        }
    }

    /** Grafts {@code path} (POSIX segments) under {@code parent}, grouping by directory like IntelliJ's tree. */
    private void insertPath(TreeItem<ChangedFile> parent, String path, String status) {
        String[] segments = path.split("/");
        TreeItem<ChangedFile> node = parent;
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            boolean leaf = i == segments.length - 1;
            acc.append(i == 0 ? "" : "/").append(segments[i]);
            String childLabel = segments[i];
            TreeItem<ChangedFile> existing = leaf ? null : childDir(node, childLabel);
            if (existing != null) {
                node = existing;
                continue;
            }
            TreeItem<ChangedFile> child = new TreeItem<>(leaf
                    ? new ChangedFile(childLabel, path, status)
                    : new ChangedFile(childLabel, null, null));
            node.getChildren().add(child);
            node = child;
        }
    }

    private TreeItem<ChangedFile> childDir(TreeItem<ChangedFile> parent, String label) {
        for (TreeItem<ChangedFile> c : parent.getChildren()) {
            if (!c.getValue().isFile() && c.getValue().label().equals(label)) return c;
        }
        return null;
    }

    private void expandAll(TreeItem<ChangedFile> item) {
        item.setExpanded(true);
        item.getChildren().forEach(this::expandAll);
    }

    private final class ChangeCell extends TreeCell<ChangedFile> {
        @Override
        protected void updateItem(ChangedFile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (!item.isFile()) {
                setText(item.label());
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            Label name = new Label(item.label());
            Label tag = new Label(item.status());
            tag.setStyle("-fx-text-fill: " + colorFor(item.status()) + "; -fx-font-size: 10px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(6, name, spacer, tag);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            setContextMenu(discardMenu(item));
        }

        private ContextMenu discardMenu(ChangedFile file) {
            MenuItem discard = new MenuItem("new".equals(file.status()) ? "Delete this new file…" : "Discard changes…");
            discard.setOnAction(e -> doDiscard(file));
            return new ContextMenu(discard);
        }

        private String colorFor(String status) {
            return switch (status) {
                case "new" -> "#1a7f37";
                case "modified" -> "#9a6700";
                case "deleted" -> "#cf222e";
                default -> "gray";
            };
        }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void doCommit() {
        String message = messageField.getText() == null ? "" : messageField.getText().trim();
        run(() -> vcs().commit(message.isBlank() ? "Update" : message), sha -> {
            messageField.clear();
            status(sha == null ? "Nothing to commit — the project is unchanged." : "Committed " + sha + ".");
        });
    }

    private void doRollback() {
        ProjectVcs.CommitInfo target = history.getSelectionModel().getSelectedItem();
        if (target == null) return;
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "Roll the project back to “" + target.message() + "” (" + target.shortSha() + ")?\n\n"
                        + "Your current state is snapshotted first, so nothing is lost. The project will "
                        + "reload from disk.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText("Roll back to this commit?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        run(() -> { vcs().restoreTo(target.sha()); return null; }, ignored -> {
            status("Rolled back to " + target.shortSha() + ".");
            requestReload();
        });
    }

    private void doDiscard(ChangedFile file) {
        boolean isNew = "new".equals(file.status());
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                (isNew ? "Delete the new file “" : "Discard your changes to “") + file.path() + "”?"
                        + (isNew ? "" : "\n\nIt will be restored to its last committed content."),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText(isNew ? "Delete new file?" : "Discard changes?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        run(() -> {
            if (isNew) {
                java.nio.file.Files.deleteIfExists(projectDir.resolve(file.path()));
            } else {
                vcs().discard(file.path());
            }
            return null;
        }, ignored -> {
            status((isNew ? "Deleted " : "Discarded changes to ") + file.path() + ".");
            requestReload();
        });
    }

    /**
     * Backs the project's history up to GitHub. First push creates a <b>private</b> repo (after confirming) and
     * sets it as {@code origin}; later pushes go straight through. Deliberately separate from Publish: this
     * creates no release, no gallery entry and no provenance file — it is the "keep a copy off my machine"
     * button, which is why it doesn't reuse the publish flow's Data-API tree upload.
     */
    private void doPush() {
        if (!auth.isAuthenticated()) {
            status("Sign in to GitHub to push — opening the sign-in…");
            showGitHubSignIn();
            return;
        }
        String existingRemote = new ProjectVcs(projectDir).remoteUrl();
        String repoName = backupRepoName();
        if (existingRemote == null && !confirmRepoCreation(repoName)) return;

        run(() -> {
            ProjectVcs vcs = vcs();
            vcs.ensureInitialized();
            String token = auth.token();
            String remote = vcs.remoteUrl();
            String webUrl = null;
            if (remote == null) {
                String login = auth.login(client).join();
                if (login == null || login.isBlank()) throw new java.io.IOException("Could not read your GitHub account.");
                JsonNode repo = createBackupRepo(login, repoName, token);
                webUrl = repo.path("html_url").asText("https://github.com/" + login + "/" + repoName);
                vcs.setRemote(repo.path("clone_url").asText(webUrl + ".git"));
            }
            String branch = vcs.push(token);
            return webUrl == null
                    ? "Pushed " + branch + " to " + shortRemote(vcs.remoteUrl()) + "."
                    : "Pushed to your new private repo: " + webUrl;
        }, this::status);
    }

    /**
     * The project name as a GitHub repo name: anything outside {@code [A-Za-z0-9._-]} becomes a dash, which is
     * what GitHub itself does to a name it accepts. Unlike the Publish dialog the user isn't asked to pick one —
     * a backup repo's name is an implementation detail, not a published identity.
     */
    private String backupRepoName() {
        String cleaned = projectName == null ? "" : projectName.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isBlank() ? "botmaker-project" : cleaned;
    }

    /** Creates the private backup repo, translating a scope-related refusal into an actionable message. */
    private JsonNode createBackupRepo(String login, String repoName, String token) throws java.io.IOException {
        try {
            return client.ensureRepo(login, repoName,
                    "BotMaker project backup — pushed from BotMaker Studio.", true, false, token);
        } catch (Exception ex) {
            String message = rootMessage(ex);
            if (message.contains("HTTP 403") || message.contains("HTTP 404")) {
                throw new java.io.IOException("GitHub refused to create a private repo. Your sign-in probably "
                        + "predates private-repo support — sign out and back in from the GitHub button, then "
                        + "try again.");
            }
            throw new java.io.IOException("Could not create the backup repo: " + message);
        }
    }

    private boolean confirmRepoCreation(String repoName) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "This project has no GitHub remote yet.\n\nCreate a private repository “" + repoName
                        + "” on your account and push the project's history to it?\n\n"
                        + "Private means only you can see it. This is a backup, not a publish — no release is "
                        + "created and the bot is not listed anywhere.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText("Create a private backup repo?");
        return confirm.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Opens the shared GitHub device-flow bar in a popup, so Push can recover from "not signed in" in place. */
    private void showGitHubSignIn() {
        javafx.stage.Stage popup = new javafx.stage.Stage();
        if (owner != null) popup.initOwner(owner);
        popup.setTitle("GitHub account");
        GitHubAccountBar bar = new GitHubAccountBar(popup, auth, client,
                () -> status(auth.isAuthenticated() ? "Signed in — press Push again." : "Not signed in."));
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(new javafx.scene.Scene(box));
        popup.show();
    }

    /** {@code owner/repo} out of a clone URL, for a status line that isn't a wall of URL. */
    private static String shortRemote(String url) {
        if (url == null) return "origin";
        String trimmed = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int slash = trimmed.lastIndexOf('/', trimmed.lastIndexOf('/') - 1);
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private void doPropose() {
        if (origin.isEmpty()) return;
        BotSource src = origin.get();
        if (!auth.isAuthenticated()) {
            status("Sign in to GitHub first (top-right) to propose changes.");
            return;
        }
        String title = messageField.getText() == null || messageField.getText().isBlank()
                ? "Changes from BotMaker Studio" : messageField.getText().trim();
        setBusy(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.submitPatch(projectDir, src, title, "Proposed from BotMaker Studio.");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        status("Propose failed: " + rootMessage(err));
                    } else if (result != null && !result.pullRequestUrl().isBlank()) {
                        status("Pull request ready: " + result.pullRequestUrl());
                        BrowserLauncher.open(result.pullRequestUrl());
                    } else {
                        status("Changes proposed upstream.");
                    }
                }));
    }

    private void doSync() {
        if (origin.isEmpty()) return;
        if (!auth.isAuthenticated()) {
            status("Sign in to GitHub first (top-right) to sync from the original.");
            return;
        }
        setBusy(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.syncFork(origin.get());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((msg, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        String m = rootMessage(err);
                        status(m);
                        if (m.toLowerCase().contains("open it on github")) {
                            BrowserLauncher.open("https://github.com/" + origin.get().slug());
                        }
                    } else {
                        status(msg);
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private ProjectVcs vcs() {
        return new ProjectVcs(projectDir, authorName, authorEmail);
    }

    private void requestReload() {
        if (eventBus != null) eventBus.publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
    }

    private void resolveIdentity() {
        if (auth == null || client == null || !auth.isAuthenticated()) return;
        auth.login(client).thenAccept(login -> {
            if (login != null && !login.isBlank()) {
                authorName = login;
                authorEmail = login + "@users.noreply.github.com";
            }
        });
    }

    private void showDiff(String relativePath) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return vcs().diff(relativePath);
                    } catch (Exception ex) {
                        return "Could not diff " + relativePath + ": " + rootMessage(ex);
                    }
                })
                .thenAccept(text -> Platform.runLater(() ->
                        diffArea.setText(text == null || text.isBlank()
                                ? "(no textual diff — new, binary, or unchanged file)" : text)));
    }

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

    /** Reloads the changed-files tree and the history list off the FX thread. */
    public void refresh() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return Map.entry(vcs().status(), vcs().history());
                    } catch (Exception ex) {
                        return Map.entry(ProjectVcs.FileStatus.empty(), List.<ProjectVcs.CommitInfo>of());
                    }
                })
                .thenAccept(pair -> Platform.runLater(() -> {
                    rebuildChanges(pair.getKey());
                    commits.setAll(pair.getValue());
                }));
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
    }

    private void status(String text) {
        statusLine.setText(text);
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

    /** The project's display name — the standalone {@code VcsDialog} uses it for the window title. */
    public String projectName() {
        return projectName;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
