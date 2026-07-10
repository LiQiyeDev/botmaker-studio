package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.game.InstalledGame;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.services.CodeEditorService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

/**
 * Editor for the {@code appId} argument of {@code Game.launchSteam(...)}: a button showing the current
 * game (name + cover thumbnail when the appId maps to an installed Steam game, else the raw appId) that
 * opens the reusable {@link GameLibraryPickerDialog} (cover-art grid of installed Steam games, with a
 * manual-entry fallback). Picking a game writes its id into the backing string literal via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>Selected by {@link ArgumentEditors} only for {@code Game.launchSteam}, so it never hijacks other
 * {@code String} arguments.
 */
public final class SteamGamePicker {

    /** Height of the small cover thumbnail shown on the button (portrait art keeps its ratio). */
    private static final double THUMB_H = 28;

    private SteamGamePicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("steam-game-picker");
        String appId = currentAppId(arg);
        button.setText(label(appId));
        // Resolve the appId → name + art off the FX thread so the button reads "Portal 2" not "Steam appId: 620".
        resolveDisplay(button, appId);

        button.setOnAction(e -> {
            Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
            GameLibraryPickerDialog.show(owner, new SteamLibraryScanner()).ifPresent(game -> {
                if (game.id() == null || game.id().isBlank()) return;
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), game.id());
                applyGame(button, game);
            });
        });
        return button;
    }

    /** Asynchronously looks up the appId in the local Steam library and, if installed, shows name + art. */
    private static void resolveDisplay(Button button, String appId) {
        if (appId == null || appId.isBlank()) return;
        Thread t = new Thread(() -> {
            java.util.Optional<InstalledGame> found = new SteamLibraryScanner().findById(appId);
            Platform.runLater(() -> found.ifPresent(g -> applyGame(button, g)));
        }, "steam-resolve");
        t.setDaemon(true);
        t.start();
    }

    /** Shows a game on the button: its name as text, plus a small cover thumbnail when art is available. */
    private static void applyGame(Button button, InstalledGame game) {
        boolean named = game.name() != null && !game.name().isBlank() && !game.name().equals(game.id());
        button.setText(named ? game.name() : label(game.id()));
        if (game.artwork() != null) {
            ImageView iv = new ImageView(new Image(game.artwork().toUri().toString(), 0, THUMB_H, true, true, true));
            iv.setPreserveRatio(true);
            iv.setFitHeight(THUMB_H);
            button.setGraphic(iv);
        } else {
            button.setGraphic(null);
        }
    }

    private static String label(String appId) {
        return (appId == null || appId.isBlank()) ? "Choose Steam game…" : "Steam appId: " + appId;
    }

    /** The current appId if the backing expression is a string literal, else null. */
    private static String currentAppId(ExpressionBlock arg) {
        Expression e = exprLiteral(arg);
        return e instanceof StringLiteral lit ? lit.getLiteralValue() : null;
    }

    private static Expression exprLiteral(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
