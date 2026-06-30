package com.botmaker.ui.app;

import com.botmaker.sharing.GitHubAuth;
import com.botmaker.sharing.GitHubClient;
import com.botmaker.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Reusable "Sign in with GitHub" control (OAuth device flow via {@link GitHubAuth}). Shows the signed-in
 * login with <b>Sign out</b> / <b>Switch account</b> actions, or a single <b>Sign in</b> button when
 * signed out. Owns the whole device-flow handshake (request code → browser + code alert → poll), so the
 * publish dialog and the project-selection screen can share one implementation.
 *
 * <p>{@code onAuthChanged} fires on every state transition (signed in / out) so the host can refresh
 * dependent UI (enable Publish, re-resolve ownership badges, …).
 */
public final class GitHubAccountBar extends HBox {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Runnable onAuthChanged;

    private final Label statusLabel = new Label();
    private final Button signInButton = new Button("Sign in with GitHub");
    private final Button signOutButton = new Button("Sign out");
    private final Button switchButton = new Button("Switch account");

    public GitHubAccountBar(Window owner, GitHubAuth auth, GitHubClient client, Runnable onAuthChanged) {
        super(10);
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.onAuthChanged = onAuthChanged;

        setAlignment(Pos.CENTER_LEFT);
        statusLabel.setStyle("-fx-text-fill: #444;");
        signInButton.setOnAction(e -> startDeviceFlow(false));
        signOutButton.setOnAction(e -> {
            auth.signOut();
            refresh();
            notifyChanged();
        });
        switchButton.setOnAction(e -> {
            auth.signOut();
            startDeviceFlow(true);
        });

        getChildren().addAll(statusLabel, signInButton, signOutButton, switchButton);
        refresh();
    }

    /** True once a token is held (browse/install never needs this; publish does). */
    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    /** Re-reads auth state and updates which controls/labels are shown. */
    public void refresh() {
        boolean authed = auth.isAuthenticated();
        show(signInButton, !authed);
        show(signOutButton, authed);
        show(switchButton, authed);
        if (!authed) {
            statusLabel.setText("Not signed in to GitHub.");
            return;
        }
        statusLabel.setText("Signed in to GitHub.");
        auth.login(client).thenAccept(login -> Platform.runLater(() -> {
            if (auth.isAuthenticated() && !login.isBlank()) {
                statusLabel.setText("Signed in as " + login + ".");
            }
        }));
    }

    private void notifyChanged() {
        if (onAuthChanged != null) onAuthChanged.run();
    }

    // -------------------------------------------------------------------------
    // Device flow
    // -------------------------------------------------------------------------

    private void startDeviceFlow(boolean switching) {
        signInButton.setDisable(true);
        switchButton.setDisable(true);
        statusLabel.setText("Requesting a sign-in code…");
        auth.requestDeviceCode().whenComplete((code, err) -> Platform.runLater(() -> {
            if (err != null || code == null) {
                signInButton.setDisable(false);
                switchButton.setDisable(false);
                refresh();
                statusLabel.setText("Sign-in failed: " + rootMessage(err));
                return;
            }
            showDeviceCode(code);
            auth.pollForToken(code).whenComplete((token, perr) -> Platform.runLater(() -> {
                signInButton.setDisable(false);
                switchButton.setDisable(false);
                refresh();
                if (perr != null) {
                    statusLabel.setText("Sign-in failed: " + rootMessage(perr));
                } else {
                    notifyChanged();
                }
            }));
        }));
    }

    private void showDeviceCode(GitHubAuth.DeviceCode code) {
        statusLabel.setText("Enter code " + code.userCode() + " at " + code.verificationUri()
                + " — waiting for authorization…");
        BrowserLauncher.open(code.verificationUri());

        TextArea codeArea = new TextArea(code.userCode());
        codeArea.setEditable(false);
        codeArea.setPrefRowCount(1);
        codeArea.setMaxWidth(160);

        Alert a = new Alert(Alert.AlertType.INFORMATION);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Sign in with GitHub");
        a.setHeaderText("Authorize BotMaker Studio");
        Hyperlink link = new Hyperlink(code.verificationUri());
        link.setOnAction(e -> BrowserLauncher.open(code.verificationUri()));
        VBox content = new VBox(8,
                new Label("1. A browser is opening " + code.verificationUri() + " (or click below)."),
                link,
                new Label("2. Enter this one-time code:"),
                codeArea,
                new Label("Leave this open; the buttons update once you authorize."));
        a.getDialogPane().setContent(content);
        a.show();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void show(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
