package com.botmaker.ui.app;

import com.botmaker.sharing.BotPublisher;
import com.botmaker.sharing.BotSource;
import com.botmaker.sharing.GitHubAuth;
import com.botmaker.sharing.GitHubClient;
import com.botmaker.sharing.GitHubGallery;
import com.botmaker.sharing.SemVer;
import com.botmaker.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes the current project to the federated gallery via {@link BotPublisher}. Authentication (and
 * sign-out / switch-account) is handled by the shared {@link GitHubAccountBar} (OAuth device flow). The
 * version field is pre-filled with the next tag after the repo's latest release; degrades gracefully when
 * publishing is not configured (no OAuth client id).
 */
public class PublishDialog {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final GitHubGallery gallery;
    private final BotPublisher publisher;
    private final String projectName;
    private final Path projectDir;

    private Stage stage;
    private GitHubAccountBar accountBar;
    private final Button publishButton = new Button("Publish");
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final TextField repoField = new TextField();
    private final TextField descriptionField = new TextField();
    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final TextField tagsField = new TextField();

    /** The repo's latest published release tag (""=none); each new version must be strictly greater. */
    private volatile String latestTag = "";

    public PublishDialog(Window owner, GitHubAuth auth, GitHubClient client, GitHubGallery gallery,
                         BotPublisher publisher, String projectName, Path projectDir) {
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.gallery = gallery;
        this.publisher = publisher;
        this.projectName = projectName;
        this.projectDir = projectDir;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Publish to Gallery");

        VBox root = new VBox(14);
        root.setPadding(new Insets(16));

        if (!auth.isConfigured()) {
            root.getChildren().addAll(
                    heading("Publishing is not configured"),
                    wrapped("This build has no GitHub OAuth client id, so publishing is disabled. "
                            + "Browsing and installing bots still works."),
                    closeBar());
            stage.setScene(new Scene(root, 460, 200));
            stage.show();
            return;
        }

        repoField.setText(projectName);
        descriptionField.setPromptText("Short description shown in the gallery");
        tagsField.setPromptText("Comma-separated tags (e.g. clicker, farming)");
        versionCombo.setEditable(true);
        versionCombo.setValue(SemVer.FIRST);
        versionCombo.setMaxWidth(Double.MAX_VALUE);
        versionCombo.valueProperty().addListener((o, was, now) -> refreshPublishEnabled());
        versionCombo.getEditor().textProperty().addListener((o, was, now) -> refreshPublishEnabled());
        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        statusLabel.setWrapText(true);

        accountBar = new GitHubAccountBar(stage, auth, client, this::onAuthChanged);
        // Re-propose a version when the repo name is changed (on focus loss, to avoid per-keystroke calls).
        repoField.focusedProperty().addListener((o, was, focused) -> {
            if (was && !focused) proposeVersion();
        });

        root.getChildren().addAll(accountBar, buildForm(), buildButtonBar());
        stage.setScene(new Scene(root, 520, 400));
        stage.show();

        refreshPublishEnabled();
        proposeVersion();
    }

    // -------------------------------------------------------------------------
    // Auth callbacks
    // -------------------------------------------------------------------------

    private void onAuthChanged() {
        refreshPublishEnabled();
        proposeVersion();
    }

    /** Publish is enabled only when signed in AND the chosen version is valid and beats the last release. */
    private void refreshPublishEnabled() {
        if (!auth.isAuthenticated()) {
            publishButton.setDisable(true);
            return;
        }
        String version = currentVersion();
        if (!SemVer.isValid(version)) {
            publishButton.setDisable(true);
            statusLabel.setText("Version must look like MAJOR.MINOR.PATCH (e.g. 1.0.0).");
        } else if (!SemVer.isGreater(version, latestTag)) {
            publishButton.setDisable(true);
            statusLabel.setText("Version must be higher than the last published version (" + latestTag + ").");
        } else {
            publishButton.setDisable(false);
            statusLabel.setText("");
        }
    }

    private String currentVersion() {
        String v = versionCombo.getValue();
        return v == null ? "" : v.trim();
    }

    /**
     * Resolves the repo's latest release tag (the baseline), seeds the version combo with the
     * patch/minor/major bumps after it, and selects the next patch. Best-effort, off the FX thread.
     */
    private void proposeVersion() {
        if (!auth.isAuthenticated()) return;
        String repo = repoField.getText() == null ? "" : repoField.getText().trim();
        if (repo.isBlank()) return;
        CompletableFuture
                .supplyAsync(() -> {
                    String login = auth.login(client).join();
                    String latest = login.isBlank() ? "" : gallery.latestReleaseTag(login, repo).join();
                    if (latest.isBlank()) {
                        // No GitHub release yet — fall back to local provenance (if any).
                        latest = BotSource.read(projectDir).map(BotSource::tag).filter(SemVer::isValid).orElse("");
                    }
                    return latest;
                })
                .thenAccept(latest -> Platform.runLater(() -> seedVersions(latest)));
    }

    /** Re-seeds the version combo with bump suggestions after {@code baseline} and selects the next patch. */
    private void seedVersions(String baseline) {
        latestTag = baseline == null ? "" : baseline;
        String basis = SemVer.isValid(latestTag) ? latestTag : SemVer.FIRST;
        // When there is a baseline, bump it; when there is none, FIRST itself is the first valid value.
        String patch = SemVer.isValid(latestTag) ? SemVer.next(basis) : SemVer.FIRST;
        List<String> options = new ArrayList<>();
        options.add(patch);
        options.add(SemVer.nextMinor(basis));
        options.add(SemVer.nextMajor(basis));
        versionCombo.getItems().setAll(options.stream().distinct().toList());
        versionCombo.setValue(patch);
        refreshPublishEnabled();
    }

    // -------------------------------------------------------------------------
    // Form + publish
    // -------------------------------------------------------------------------

    private GridPane buildForm() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.addRow(0, new Label("Repository name:"), repoField);
        g.addRow(1, new Label("Description:"), descriptionField);
        g.addRow(2, new Label("Version (tag):"), versionCombo);
        g.addRow(3, new Label("Tags:"), tagsField);
        GridPane.setHgrow(repoField, Priority.ALWAYS);
        GridPane.setHgrow(descriptionField, Priority.ALWAYS);
        GridPane.setHgrow(versionCombo, Priority.ALWAYS);
        GridPane.setHgrow(tagsField, Priority.ALWAYS);
        return g;
    }

    private VBox buildButtonBar() {
        statusLabel.setStyle("-fx-text-fill: #444;");
        publishButton.setDefaultButton(true);
        publishButton.setOnAction(e -> doPublish());

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, spacer, close, publishButton);
        bar.setAlignment(Pos.CENTER_LEFT);

        return new VBox(8, statusLabel, bar);
    }

    private void doPublish() {
        String repo = repoField.getText() == null ? "" : repoField.getText().trim();
        String version = currentVersion();
        String description = descriptionField.getText() == null ? "" : descriptionField.getText().trim();
        List<String> tags = parseTags(tagsField.getText());
        if (repo.isBlank()) {
            statusLabel.setText("Repository name is required.");
            return;
        }
        if (!SemVer.isGreater(version, latestTag)) {
            refreshPublishEnabled();
            return;
        }

        setBusy(true);
        statusLabel.setText("Publishing…");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.publish(projectDir, projectName, repo, description, version, tags);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        statusLabel.setText("Publish failed: " + rootMessage(err));
                    } else {
                        statusLabel.setText("Published " + result.tag() + ".");
                        showResult(result);
                        seedVersions(result.tag()); // the just-published tag becomes the new baseline
                    }
                }));
    }

    /** Splits a comma-separated tag string into trimmed, de-duped, non-blank tags. */
    static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : Arrays.asList(raw.split(","))) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) seen.add(trimmed);
        }
        return new ArrayList<>(seen);
    }

    private void showResult(BotPublisher.PublishResult result) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(stage);
        a.setTitle("Published");
        a.setHeaderText("“" + projectName + "” " + result.tag() + " is published");
        Hyperlink repoLink = new Hyperlink(result.repoUrl());
        repoLink.setOnAction(e -> BrowserLauncher.open(result.repoUrl()));
        Label gallery = new Label(result.galleryStatus());
        gallery.setWrapText(true);
        a.getDialogPane().setContent(new VBox(8, new Label("Repository:"), repoLink, gallery));
        a.showAndWait();
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        publishButton.setDisable(busy || !auth.isAuthenticated());
        repoField.setDisable(busy);
        descriptionField.setDisable(busy);
        versionCombo.setDisable(busy);
        tagsField.setDisable(busy);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HBox closeBar() {
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(spacer, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        return l;
    }

    private static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
