package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.game.SteamLibraryScanner;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;

/**
 * Editor for the {@code appId} argument of {@code Game.launchSteam(...)}: a button showing the current
 * appId that opens the reusable {@link GameLibraryPickerDialog} (cover-art grid of installed Steam games,
 * with a manual-entry fallback). Picking a game writes its id into the backing string literal via
 * {@link com.botmaker.studio.parser.CodeEditor#replaceLiteralValue}.
 *
 * <p>Selected by {@link ArgumentEditors} only for {@code Game.launchSteam}, so it never hijacks other
 * {@code String} arguments.
 */
public final class SteamGamePicker {

    private SteamGamePicker() {}

    public static Node create(CodeEditorService context, ExpressionBlock arg) {
        Button button = new Button();
        button.getStyleClass().add("steam-game-picker");
        button.setText(label(currentAppId(arg)));

        button.setOnAction(e -> {
            Window owner = button.getScene() != null ? button.getScene().getWindow() : null;
            GameLibraryPickerDialog.show(owner, new SteamLibraryScanner()).ifPresent(game -> {
                if (game.id() == null || game.id().isBlank()) return;
                context.getCodeEditor().replaceLiteralValue(exprLiteral(arg), game.id());
            });
        });
        return button;
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
