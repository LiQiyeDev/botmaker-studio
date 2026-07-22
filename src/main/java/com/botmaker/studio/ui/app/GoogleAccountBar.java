package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GoogleAuth;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Reusable "Sign in with Google" control (OAuth device flow via {@link GoogleAuth}) — the sibling of
 * {@link GitHubAccountBar}. Shows the signed-in email with <b>Sign out</b>, or a single <b>Sign in</b> button.
 * There is no backend behind the Google identity yet, so the whole bar is hidden until
 * {@link GoogleAuth#isConfigured()} (a client id is set); this is the sign-in plumbing only.
 */
public final class GoogleAccountBar extends HBox {

    private final Window owner;
    private final GoogleAuth auth;
    private final Runnable onAuthChanged;

    private final Label statusLabel = new Label();
    private final Button signInButton = new Button("Sign in with Google");
    private final Button signOutButton = new Button("Sign out");

    public GoogleAccountBar(Window owner, GoogleAuth auth, Runnable onAuthChanged) {
        super(10);
        this.owner = owner;
        this.auth = auth;
        this.onAuthChanged = onAuthChanged;

        setAlignment(Pos.CENTER_LEFT);
        statusLabel.setStyle("-fx-text-fill: #444;");
        signInButton.setOnAction(e -> startDeviceFlow());
        signOutButton.setOnAction(e -> {
            auth.signOut();
            refresh();
            notifyChanged();
        });

        getChildren().addAll(statusLabel, signInButton, signOutButton);
        refresh();
    }

    public boolean isAuthenticated() {
        return auth.isAuthenticated();
    }

    /** Re-reads auth state and updates which controls/labels are shown. The whole bar hides until configured. */
    public void refresh() {
        boolean configured = auth.isConfigured();
        setVisible(configured);
        setManaged(configured);
        if (!configured) return;

        boolean authed = auth.isAuthenticated();
        show(signInButton, !authed);
        show(signOutButton, authed);
        if (!authed) {
            statusLabel.setText("Not signed in to Google.");
            return;
        }
        statusLabel.setText("Signed in to Google.");
        auth.email().thenAccept(email -> Platform.runLater(() -> {
            if (auth.isAuthenticated() && !email.isBlank()) {
                statusLabel.setText("Signed in as " + email + ".");
            }
        }));
    }

    private void notifyChanged() {
        if (onAuthChanged != null) onAuthChanged.run();
    }

    private void startDeviceFlow() {
        signInButton.setDisable(true);
        statusLabel.setText("Requesting a sign-in code…");
        auth.requestDeviceCode().whenComplete((code, err) -> Platform.runLater(() -> {
            if (err != null || code == null) {
                signInButton.setDisable(false);
                refresh();
                statusLabel.setText("Sign-in failed: " + rootMessage(err));
                return;
            }
            Alert dialog = showDeviceCode(code);
            auth.pollForToken(code).whenComplete((token, perr) -> Platform.runLater(() -> {
                signInButton.setDisable(false);
                dialog.setResult(ButtonType.OK);
                dialog.close();
                refresh();
                if (perr != null) {
                    statusLabel.setText("Sign-in failed: " + rootMessage(perr));
                } else {
                    notifyChanged();
                }
            }));
        }));
    }

    private Alert showDeviceCode(GoogleAuth.DeviceCode code) {
        statusLabel.setText("Enter code " + code.userCode() + " at " + code.verificationUri()
                + " — waiting for authorization…");
        BrowserLauncher.open(code.verificationUri());

        TextArea codeArea = new TextArea(code.userCode());
        codeArea.setEditable(false);
        codeArea.setPrefRowCount(1);
        codeArea.setMaxWidth(160);

        Alert a = new Alert(Alert.AlertType.INFORMATION);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Sign in with Google");
        a.setHeaderText("Authorize BotMaker Studio");
        Hyperlink link = new Hyperlink(code.verificationUri());
        link.setOnAction(e -> BrowserLauncher.open(code.verificationUri()));
        VBox content = new VBox(8,
                new Label("1. A browser is opening " + code.verificationUri() + " (or click below)."),
                link,
                new Label("2. Enter this one-time code:"),
                codeArea,
                new Label("This closes automatically once you authorize."));
        a.getDialogPane().setContent(content);
        a.show();
        return a;
    }

    private static void show(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        if (t instanceof java.net.UnknownHostException
                || t instanceof java.net.ConnectException
                || t instanceof java.nio.channels.UnresolvedAddressException) {
            return "No internet connection — check your network and try again.";
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
