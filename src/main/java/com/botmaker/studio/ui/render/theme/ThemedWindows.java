package com.botmaker.studio.ui.render.theme;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.net.URL;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one way a Studio window gets a theme. A themed window needs <em>two</em> things and each is useless
 * alone: the {@code /css/blocks.css} stylesheet, and the current theme's style class on the scene root —
 * {@code .dark-theme}/{@code .black-theme} redefine Modena's own chrome tokens ({@code -fx-base},
 * {@code -fx-background}, …), so a dialog missing either shows a white Modena pane in a dark app.
 *
 * <p>Only the main window used to get both (a private helper in {@code UIManager}), which is why every
 * secondary Stage, Dialog and Alert stayed light. Each JavaFX window owns a separate {@link Scene} and
 * inherits nothing from its owner, so there is no styling a dialog by parenting it — it has to be told, and
 * this is the single place that tells it.
 *
 * <p>Applying also subscribes the window to {@link BlockTheme} changes so a theme switched while it is open
 * reaches it, and unsubscribes on hide so a dialog opened and closed all afternoon doesn't accumulate
 * listeners in the static list. Re-showing the same window re-subscribes.
 */
public final class ThemedWindows {

    private ThemedWindows() {}

    /** Every class this helper may have added — removed wholesale before the current one goes on. */
    private static final List<String> THEME_CLASSES =
            List.of("default-theme", "dark-theme", "black-theme", "high-contrast-theme", "light-theme");

    /** The style class for a theme, i.e. the selector {@code blocks.css} defines its token overrides under. */
    public static String styleClass(BlockTheme.ThemeType theme) {
        return switch (theme) {
            case DEFAULT -> "default-theme";
            case DARK -> "dark-theme";
            case BLACK -> "black-theme";
            case HIGH_CONTRAST -> "high-contrast-theme";
        };
    }

    /** Swaps {@code node}'s theme class for the current one. The stylesheet is the caller's business. */
    public static void applyThemeClass(Parent node) {
        if (node == null) return;
        node.getStyleClass().removeAll(THEME_CLASSES);
        node.getStyleClass().add(styleClass(BlockTheme.getCurrentThemeType()));
    }

    /**
     * Adds {@code blocks.css} to a scene without subscribing it to theme changes — for the main window, whose
     * listener is owned by {@code UIManager} and released by its {@code dispose()} (a reload swaps the scene
     * without ever hiding the Stage, so hide-based unsubscription would not fire there).
     */
    public static void addStylesheet(Scene scene) {
        if (scene != null) addBlocksCss(scene.getStylesheets());
    }

    /** Themes a scene: stylesheet, current class on its root, and live theme switches while it is shown. */
    public static void apply(Scene scene) {
        if (scene == null) return;
        addBlocksCss(scene.getStylesheets());
        Parent root = scene.getRoot();
        applyThemeClass(root);
        if (root != null) live(root, () -> applyThemeClass(scene.getRoot()));
    }

    /** Themes a dialog pane — the {@link Dialog} counterpart of {@link #apply(Scene)}. */
    public static void apply(DialogPane pane) {
        if (pane == null) return;
        addBlocksCss(pane.getStylesheets());
        applyThemeClass(pane);
        live(pane, () -> applyThemeClass(pane));
    }

    /** Themes a dialog through its pane. */
    public static void apply(Dialog<?> dialog) {
        if (dialog != null) apply(dialog.getDialogPane());
    }

    /** Themes a stage's scene, if it has one yet. */
    public static void apply(Stage stage) {
        if (stage != null) apply(stage.getScene());
    }

    /** A themed scene — the replacement for {@code new Scene(root)} in a secondary window. */
    public static Scene scene(Parent root) {
        Scene scene = new Scene(root);
        apply(scene);
        return scene;
    }

    /** A themed scene at a given size — the replacement for {@code new Scene(root, w, h)}. */
    public static Scene scene(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        apply(scene);
        return scene;
    }

    /**
     * The themed replacement for {@code new Alert(...)} — the reason every alert in Studio is built here
     * rather than constructed inline, since a bare {@code Alert} carries neither the stylesheet nor the class
     * and so shows up white in a dark app.
     */
    public static Alert alert(Alert.AlertType type) {
        Alert alert = new Alert(type);
        apply(alert);
        return alert;
    }

    /** An {@link #alert(Alert.AlertType)} with its content text, and optionally its buttons, given up front. */
    public static Alert alert(Alert.AlertType type, String message, ButtonType... buttons) {
        Alert alert = new Alert(type, message, buttons);
        apply(alert);
        return alert;
    }

    private static void addBlocksCss(List<String> stylesheets) {
        URL css = ThemedWindows.class.getResource("/css/blocks.css");
        if (css == null) return;
        String url = css.toExternalForm();
        if (!stylesheets.contains(url)) stylesheets.add(url);
    }

    /**
     * Keeps {@code reapply} subscribed to {@link BlockTheme} for exactly as long as {@code node}'s window is
     * showing. The window is resolved lazily: a scene is routinely themed before it is handed to a Stage, so
     * at call time there is usually neither scene nor window to hook yet.
     */
    private static void live(Node node, Runnable reapply) {
        Consumer<BlockTheme.ThemeType> listener = t -> reapply.run();
        onWindow(node, window -> {
            BlockTheme.removeThemeChangeListener(listener);
            BlockTheme.addThemeChangeListener(listener);
            reapply.run();
            // addEventHandler, not setOnHidden: the owner of a dialog routinely sets that property itself,
            // and unsubscribing must not be something a caller can silently take over.
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> BlockTheme.removeThemeChangeListener(listener));
            window.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> {
                BlockTheme.removeThemeChangeListener(listener);
                BlockTheme.addThemeChangeListener(listener);
                reapply.run();
            });
        });
    }

    /** Runs {@code action} once, on the window that ends up showing {@code node}. */
    private static void onWindow(Node node, Consumer<Window> action) {
        once(node.sceneProperty(), node.getScene(), scene -> once(scene.windowProperty(), scene.getWindow(), action));
    }

    /** Runs {@code action} on {@code now} if it is already set, otherwise on the property's first value. */
    private static <T> void once(ObservableValue<T> property, T now, Consumer<T> action) {
        if (now != null) {
            action.accept(now);
            return;
        }
        property.addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends T> obs, T old, T value) {
                if (value == null) return;
                property.removeListener(this);
                action.accept(value);
            }
        });
    }
}
