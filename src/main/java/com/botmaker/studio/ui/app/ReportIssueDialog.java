package com.botmaker.studio.ui.app;

import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.sharing.GitHubAuth;
import com.botmaker.studio.sharing.GitHubClient;
import com.botmaker.studio.sharing.GitHubConfig;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Help ▸ Report Issue… — collects a title, description and optional screenshots and files a GitHub issue
 * against the umbrella repo ({@link GitHubConfig#ISSUE_OWNER}/{@link GitHubConfig#ISSUE_REPO}).
 *
 * <p><b>No token is ever stored for this feature.</b> If the user is already signed in to GitHub (the same
 * device-flow sign-in used for gallery publishing), the issue is created directly via the REST API. Otherwise
 * it opens a prefilled "New Issue" page in the browser, where the reporter submits it from their own
 * github.com session.
 *
 * <p>GitHub's issue API has no attachment-upload endpoint, so when screenshots are attached the
 * created/opened issue is shown in the browser with a note to drag the images into the comment box (GitHub
 * web supports paste/drag upload).
 */
public final class ReportIssueDialog {

    private final Window owner;

    public ReportIssueDialog(Window owner) {
        this.owner = owner;
    }

    public void show() {
        GitHubAuth auth = new GitHubAuth();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Report an Issue");
        if (owner != null) dialog.initOwner(owner);
        dialog.setHeaderText("Found a bug or have a suggestion? Send it to the BotMaker team.");

        ButtonType submitType = new ButtonType("Submit", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        TextField titleField = new TextField();
        titleField.setPromptText("Short summary");

        TextArea descArea = new TextArea();
        descArea.setPromptText("What happened? What did you expect? Steps to reproduce…");
        descArea.setPrefRowCount(8);
        descArea.setWrapText(true);

        List<File> screenshots = new ArrayList<>();
        Label shotsLabel = new Label("No screenshots attached.");
        shotsLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        javafx.scene.control.Button addShot = new javafx.scene.control.Button("Attach screenshots…");
        addShot.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Attach screenshots");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));
            List<File> chosen = fc.showOpenMultipleDialog(owner);
            if (chosen != null && !chosen.isEmpty()) {
                screenshots.addAll(chosen);
                shotsLabel.setText(screenshots.size()
                        + " screenshot(s) attached — you'll be asked to drop them into the issue page.");
            }
        });

        Label channel = new Label(auth.isAuthenticated()
                ? "Signed in to GitHub — the issue will be posted directly."
                : "Not signed in — this opens a prefilled GitHub issue in your browser. A free GitHub account "
                        + "is required to submit it there.");
        channel.setWrapText(true);
        channel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(4));
        grid.add(new Label("Title:"), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label("Details:"), 0, 1);
        grid.add(descArea, 1, 1);
        grid.add(addShot, 1, 2);
        grid.add(shotsLabel, 1, 3);
        grid.add(channel, 1, 4);
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setFillWidth(true);
        grid.getColumnConstraints().addAll(c0, c1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(560);

        Node submitBtn = dialog.getDialogPane().lookupButton(submitType);
        submitBtn.disableProperty().bind(titleField.textProperty().isEmpty());

        dialog.showAndWait().ifPresent(bt -> {
            if (bt == submitType) {
                submit(auth, titleField.getText().trim(), descArea.getText().trim(), screenshots);
            }
        });
    }

    private void submit(GitHubAuth auth, String title, String description, List<File> screenshots) {
        String body = buildBody(description, screenshots);
        boolean hasShots = !screenshots.isEmpty();

        if (auth.isAuthenticated()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            new GitHubClient().post(GitHubConfig.issuesApiUrl(), payload, auth.token())
                    .whenComplete((node, ex) -> Platform.runLater(() -> {
                        if (ex != null) {
                            // Posting failed (revoked token, network, …) — fall back to the browser so the
                            // report is never lost.
                            openBrowserIssue(title, body);
                            info("Opened in your browser",
                                    "Couldn't post the issue directly (" + rootMessage(ex) + ").\n\n"
                                            + "A prefilled issue was opened in your browser instead — submit it there.");
                            return;
                        }
                        String url = node != null ? node.path("html_url").asText("") : "";
                        if (hasShots && !url.isBlank()) {
                            BrowserLauncher.open(url);
                            info("Thanks — issue created",
                                    "Your issue was created. We opened it in your browser: drag your "
                                            + "screenshot(s) into the comment box to attach them.");
                        } else {
                            info("Thanks — issue created",
                                    "Your issue was created." + (url.isBlank() ? "" : "\n\n" + url));
                        }
                    }));
        } else {
            openBrowserIssue(title, body);
            info("Opened in your browser",
                    hasShots
                            ? "A prefilled issue opened in your browser. Sign in to GitHub there to submit it, "
                              + "and drag your screenshot(s) into the description to attach them."
                            : "A prefilled issue opened in your browser. Sign in to GitHub there to submit it.");
        }
    }

    /** Builds the markdown body: the description plus an auto-collected diagnostics footer. */
    private static String buildBody(String description, List<File> screenshots) {
        StringBuilder sb = new StringBuilder();
        sb.append(description.isBlank() ? "_No description provided._" : description);
        if (!screenshots.isEmpty()) {
            sb.append("\n\n_").append(screenshots.size()).append(" screenshot(s) to be attached._");
        }
        sb.append("\n\n---\n");
        sb.append("- BotMaker Studio: ").append(AppVersion.get()).append('\n');
        sb.append("- OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- Java: ").append(System.getProperty("java.version"));
        return sb.toString();
    }

    private static void openBrowserIssue(String title, String body) {
        String url = GitHubConfig.newIssueBrowserUrl()
                + "?title=" + enc(title) + "&body=" + enc(body);
        BrowserLauncher.open(url);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = (t instanceof java.util.concurrent.CompletionException && t.getCause() != null)
                ? t.getCause() : t;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void info(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle(header);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
