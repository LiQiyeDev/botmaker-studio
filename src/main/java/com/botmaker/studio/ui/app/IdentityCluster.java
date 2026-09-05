package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.function.Consumer;

/**
 * The far-right toolbar cluster: the VCS button, the GitHub account button, the theme dropdown and the (still
 * disabled) Google button.
 *
 * <p>Extracted from {@code UIManager} with its {@link BlockTheme} listener, which is the point: that listener
 * list is <b>static</b>, so a cluster that registered one and was then thrown away with its project kept the
 * whole dead scene graph alive and went on being called on every later theme change. Owning the registration
 * here means {@link #dispose()} can drop it.
 */
final class IdentityCluster {

    private final Stage owner;
    private final GitHubAuth gitHubAuth;
    private final GitHubClient gitHubClient;

    private final HBox node;
    /** Kept so {@link #dispose()} can unregister it from {@link BlockTheme}'s static listener list. */
    private final Consumer<BlockTheme.ThemeType> themeListener;

    /**
     * @param onShowVcs raises the VCS bottom tab (a no-op when there is none — Reader mode has no VCS tab)
     */
    IdentityCluster(Stage owner, GitHubAuth gitHubAuth, GitHubClient gitHubClient, Runnable onShowVcs) {
        this.owner = owner;
        this.gitHubAuth = gitHubAuth;
        this.gitHubClient = gitHubClient;

        Button vcsButton = new Button("⑂ VCS");
        vcsButton.setTooltip(new Tooltip("Show version control (commit, changes, history)"));
        vcsButton.setOnAction(e -> onShowVcs.run());

        Button gitHub = roundButton("GH", "#24292f", "GitHub account");
        gitHub.setOnAction(e -> showGitHubAccountPopup(gitHub));
        refreshGitHubButton(gitHub);

        ComboBox<BlockTheme.ThemeType> theme = themeDropdown();
        this.themeListener = type -> {
            if (theme.getValue() != type) theme.setValue(type);
        };
        BlockTheme.addThemeChangeListener(themeListener);

        this.node = new HBox(6, vcsButton, gitHub, theme, googleButton());
        this.node.setAlignment(Pos.CENTER_RIGHT);
    }

    Node node() {
        return node;
    }

    /** Drops the static theme registration. Idempotent — removing an absent listener is a no-op. */
    void dispose() {
        BlockTheme.removeThemeChangeListener(themeListener);
    }

    /**
     * The round Google button — <em>disabled</em>, with the reason as its tooltip. It used to be clickable and
     * its only action was an alert apologising that the feature doesn't exist; a greyed control with a reason
     * reads as "not yet", a clickable one that only apologises reads as broken. The sign-in plumbing behind it
     * ({@code sharing/GoogleAuth}, {@code GoogleAccountBar}) is finished and correct — it just has no client id
     * and no backend yet (see {@code sharing/GoogleConfig}).
     *
     * <p>Returned wrapped in a container because a disabled JavaFX control receives no mouse events, so a
     * tooltip installed on the button itself would never show; it goes on the (enabled) wrapper instead.
     */
    private static Node googleButton() {
        Button google = roundButton("G", "#4285F4", null);
        google.setDisable(true);
        HBox holder = new HBox(google);
        Tooltip.install(holder, new Tooltip(
                "Google sign-in isn't available yet — reserved for future Tailscale/Drive features."));
        return holder;
    }

    /** A 28px round icon button. A null {@code tooltip} installs none (see {@link #googleButton()}). */
    private static Button roundButton(String glyph, String bg, String tooltip) {
        Button b = new Button(glyph);
        if (tooltip != null) b.setTooltip(new Tooltip(tooltip));
        b.setStyle("-fx-background-radius: 14; -fx-min-width: 28; -fx-min-height: 28; "
                + "-fx-max-width: 28; -fx-max-height: 28; -fx-padding: 0; -fx-font-size: 10px; "
                + "-fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: " + bg + ";");
        return b;
    }

    /** Labels the GitHub round button with the signed-in login initials (or a bare mark when signed out). */
    private void refreshGitHubButton(Button gitHub) {
        if (gitHubAuth != null && gitHubAuth.isAuthenticated()) {
            gitHubAuth.login(gitHubClient).thenAccept(login -> Platform.runLater(() -> {
                if (login != null && !login.isBlank()) {
                    gitHub.setText(login.substring(0, Math.min(2, login.length())).toUpperCase());
                    gitHub.setTooltip(new Tooltip("Signed in to GitHub as " + login));
                }
            }));
        } else {
            gitHub.setText("GH");
            gitHub.setTooltip(new Tooltip("Sign in to GitHub"));
        }
    }

    /** Opens the shared {@link GitHubAccountBar} (device-flow handshake) in a small popup off the round button. */
    private void showGitHubAccountPopup(Button gitHub) {
        Stage popup = new Stage();
        popup.initOwner(owner);
        popup.initModality(Modality.NONE);
        popup.setTitle("GitHub account");
        GitHubAccountBar bar = new GitHubAccountBar(popup, gitHubAuth, gitHubClient,
                () -> refreshGitHubButton(gitHub));
        VBox box = new VBox(bar);
        box.setPadding(new Insets(14));
        popup.setScene(ThemedWindows.scene(box));
        popup.show();
    }

    /**
     * The toolbar's theme picker — a dropdown of all four themes, wired straight to {@link BlockTheme}. Kept
     * in sync with the <b>View ▸ Theme</b> menu ({@link MenuBarManager}) via {@link BlockTheme}'s own listener
     * list (registered by the constructor, dropped by {@link #dispose()}), since both controls read and write
     * the same static state.
     */
    private static ComboBox<BlockTheme.ThemeType> themeDropdown() {
        ComboBox<BlockTheme.ThemeType> box =
                new ComboBox<>(FXCollections.observableArrayList(BlockTheme.ThemeType.values()));
        box.setConverter(new StringConverter<>() {
            @Override public String toString(BlockTheme.ThemeType type) {
                return switch (type) {
                    case DEFAULT -> "Default";
                    case DARK -> "Dark";
                    case BLACK -> "Black";
                    case HIGH_CONTRAST -> "High Contrast";
                };
            }
            @Override public BlockTheme.ThemeType fromString(String s) { return null; }
        });
        box.setValue(BlockTheme.getCurrentThemeType());
        box.setOnAction(e -> BlockTheme.setTheme(box.getValue()));
        box.setTooltip(new Tooltip("Theme"));
        return box;
    }
}
