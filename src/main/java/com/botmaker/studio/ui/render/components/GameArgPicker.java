package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.game.GameLibraryProvider;
import com.botmaker.studio.game.InstalledGame;
import com.botmaker.studio.services.CodeEditorService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.util.function.Supplier;

/**
 * Editor for the launch-id argument of a store launch call (e.g. {@code Game.launchSteam(appId)} or
 * {@code Game.launchEpic(appName)}): a button showing the current game (name + cover thumbnail when the id
 * maps to an installed game, else the raw id) that opens the reusable {@link GameLibraryPickerDialog}
 * (cover-art grid of installed games, with a manual-entry fallback). Picking a game writes its id into the
 * backing string literal via {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>Parameterized by a {@link GameLibraryProvider} supplier so one widget serves every launcher — the
 * former Steam-only {@code SteamGamePicker}, now provider-agnostic. Selected by {@link ArgumentEditors}
 * only for the matching launch method, so it never hijacks other {@code String} arguments.
 */
public final class GameArgPicker {

    /** Height of the small cover thumbnail shown on the button (portrait art keeps its ratio). */
    private static final double THUMB_H = 28;

    private GameArgPicker() {}

    /**
     * @param providerFactory builds a fresh {@link GameLibraryProvider} for each scan (providers are
     *                        stateless best-effort readers); its {@link GameLibraryProvider#displayName()}
     *                        also drives the button labels.
     */
    public static Node create(CodeEditorService context, ExpressionBlock arg,
                              Supplier<GameLibraryProvider> providerFactory) {
        GameLibraryProvider labelProvider = providerFactory.get();
        String launcher = labelProvider.displayName();

        Button button = new Button();
        button.getStyleClass().add("game-arg-picker");
        String id = currentId(arg);
        button.setText(label(launcher, id));
        // Resolve the id → name + art off the FX thread so the button reads "Portal 2" not "Steam game: 620".
        resolveDisplay(button, launcher, id, providerFactory);

        button.setOnAction(e -> {
            Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
            GameLibraryPickerDialog.show(owner, providerFactory.get()).ifPresent(game -> {
                if (game.id() == null || game.id().isBlank()) return;
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), game.id());
                applyGame(button, launcher, game);
            });
        });
        return button;
    }

    /** Asynchronously looks up the id in the local library and, if installed, shows name + art. */
    private static void resolveDisplay(Button button, String launcher, String id,
                                       Supplier<GameLibraryProvider> providerFactory) {
        if (id == null || id.isBlank()) return;
        Thread t = new Thread(() -> {
            java.util.Optional<InstalledGame> found = providerFactory.get().findById(id);
            Platform.runLater(() -> found.ifPresent(g -> applyGame(button, launcher, g)));
        }, "game-arg-resolve");
        t.setDaemon(true);
        t.start();
    }

    /** Shows a game on the button: its name as text, plus a small cover thumbnail when art is available. */
    private static void applyGame(Button button, String launcher, InstalledGame game) {
        boolean named = game.name() != null && !game.name().isBlank() && !game.name().equals(game.id());
        button.setText(named ? game.name() : label(launcher, game.id()));
        if (game.artwork() != null) {
            ImageView iv = new ImageView(new Image(game.artwork().toUri().toString(), 0, THUMB_H, true, true, true));
            iv.setPreserveRatio(true);
            iv.setFitHeight(THUMB_H);
            button.setGraphic(iv);
        } else {
            button.setGraphic(null);
        }
    }

    private static String label(String launcher, String id) {
        return (id == null || id.isBlank()) ? "Choose " + launcher + " game…" : launcher + " game: " + id;
    }

    /** The current id if the backing expression is a string literal, else null. */
    private static String currentId(ExpressionBlock arg) {
        Expression e = exprLiteral(arg);
        return e instanceof StringLiteral lit ? lit.getLiteralValue() : null;
    }

    private static Expression exprLiteral(ExpressionBlock arg) {
        return (Expression) ((AbstractCodeBlock) arg).getAstNode();
    }
}
